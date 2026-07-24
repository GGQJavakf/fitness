import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { PlanExerciseOption } from '../../../application/models'
import { numericFieldPath, type EditableNumericField, type PlanEditorState } from '../../../application/planEditor'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName, planFieldDisplayName, planIssueDisplayMessage } from '../../copy'

import './index.scss'

const application = getWeappApplication()
const fieldLabels: Record<EditableNumericField, string> = {
  workSets: '工作组',
  repMin: '最少次数',
  repMax: '最多次数',
  restSeconds: '休息秒数',
  targetWeightKg: '目标重量（KG）',
}
const editableFields = Object.keys(fieldLabels) as EditableNumericField[]

export default function PlanEditorPage() {
  const [editor, setEditor] = useState<PlanEditorState | null>(() => application.getPlanEditor())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [optionPicker, setOptionPicker] = useState<{ dayCode: string; replacing?: string } | null>(null)
  const [exerciseOptions, setExerciseOptions] = useState<readonly PlanExerciseOption[]>([])
  const [pendingRemove, setPendingRemove] = useState('')

  useEffect(() => {
    if (editor) return
    void application.loadActivePlan()
      .then(() => setEditor(application.openPlanEditor()))
      .catch(() => setError('活动计划加载失败，请返回计划页重试'))
  }, [editor])

  function edit(dayCode: string, exerciseCode: string, field: EditableNumericField, raw: string): void {
    const value = Number(raw)
    const next = application.editPlanNumber(dayCode, exerciseCode, field, value)
    setEditor(next)
    if (next !== editor) application.telemetry.track('plan_edited', { fieldKind: 'prescription' })
  }

  async function run(action: () => Promise<PlanEditorState>): Promise<void> {
    setBusy(true)
    setError('')
    try {
      setEditor(await action())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  async function save(): Promise<void> {
    const previousVersion = editor?.baseVersion ?? 0
    setBusy(true)
    setError('')
    try {
      const current = await application.saveEditor()
      setEditor(current)
      if (current.baseVersion > previousVersion
        && !current.warningConfirmationToken
        && !current.conflict
        && !current.validationResult.validationIssues.some((issue) => issue.severity === 'ERROR')) {
        await application.navigation.replace('PLAN')
      }
      if (current.baseVersion > previousVersion) {
        application.telemetry.track('plan_confirmed', { versionNumber: current.baseVersion })
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  async function openExerciseOptions(dayCode: string, replacing?: string): Promise<void> {
    setBusy(true)
    setError('')
    try {
      const options = await application.listPlanExerciseOptions(dayCode)
      setExerciseOptions(options)
      setOptionPicker({ dayCode, ...(replacing ? { replacing } : {}) })
      if (options.length === 0) setError('当前器械、排除偏好和模板下没有更多可用动作')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '动作候选加载失败')
    } finally {
      setBusy(false)
    }
  }

  function applyStructureEdit(action: () => PlanEditorState): void {
    try {
      setEditor(action())
      application.telemetry.track('plan_edited', { fieldKind: 'structure' })
      setOptionPicker(null)
      setExerciseOptions([])
      setPendingRemove('')
      setError('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '动作结构调整失败')
    }
  }

  function chooseExercise(option: PlanExerciseOption): void {
    if (!optionPicker) return
    applyStructureEdit(() => optionPicker.replacing
      ? application.replacePlanExercise(optionPicker.dayCode, optionPicker.replacing, option)
      : application.addPlanExercise(optionPicker.dayCode, option))
  }

  if (!editor) {
    return <View className='screen'><View className='card'><Text>{error || '正在加载编辑器…'}</Text></View></View>
  }

  return (
    <View className='screen'>
      <View className='card'>
        <Text className='title'>{editor.workingCopy.name}</Text>
        <Text className='subtitle'>{editor.baseVersion === 0 ? '候选草稿尚未生效。' : `基于不可变版本 v${editor.baseVersion}。`}手改关键数值会自动 USER_LOCKED；RULE_LOCKED 不能由客户端修改。</Text>
      </View>

      {editor.workingCopy.days.map((day, dayIndex) => (
        <View key={day.code} className='card'>
          <Text className='section-title'>{day.name}</Text>
          {day.exercises.map((exercise, exerciseIndex) => (
            <View key={exercise.exerciseCode} className='editor-exercise'>
              <View className='editor-exercise__heading'>
                <View>
                  <Text className='section-title'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                  <Text className='code-label'>{exercise.exerciseCode}</Text>
                </View>
                <View className='editor-structure-actions'>
                  <Button size='mini' disabled={exerciseIndex === 0} onClick={() => applyStructureEdit(() => application.movePlanExercise(day.code, exercise.exerciseCode, -1))}>上移</Button>
                  <Button size='mini' disabled={exerciseIndex === day.exercises.length - 1} onClick={() => applyStructureEdit(() => application.movePlanExercise(day.code, exercise.exerciseCode, 1))}>下移</Button>
                  <Button size='mini' loading={busy} onClick={() => void openExerciseOptions(day.code, exercise.exerciseCode)}>替换</Button>
                  <Button
                    size='mini'
                    className={pendingRemove === `${day.code}:${exercise.exerciseCode}` ? 'danger-action' : ''}
                    onClick={() => {
                      const key = `${day.code}:${exercise.exerciseCode}`
                      if (pendingRemove !== key) return setPendingRemove(key)
                      applyStructureEdit(() => application.removePlanExercise(day.code, exercise.exerciseCode))
                    }}
                  >{pendingRemove === `${day.code}:${exercise.exerciseCode}` ? '确认删除' : '删除'}</Button>
                </View>
              </View>
              {editableFields.map((field) => {
                const path = numericFieldPath(day.code, exercise.exerciseCode, field)
                const lock = editor.locks[path] ?? 'UNLOCKED'
                const weightUnavailable = field === 'targetWeightKg' && exercise.weightStatus === 'BODYWEIGHT'
                const value = exercise[field]
                return (
                  <View key={field} className='editor-field'>
                    <Text>{fieldLabels[field]}</Text>
                    {weightUnavailable
                      ? <Text className='editor-field__hint'>自重动作</Text>
                      : <Input
                          className='editor-field__input'
                          type='digit'
                          value={typeof value === 'number' ? String(value) : ''}
                          placeholder={field === 'targetWeightKg' ? '先校准' : ''}
                          disabled={lock === 'RULE_LOCKED'}
                          onInput={(event) => {
                            if (event.detail.value.trim()) edit(day.code, exercise.exerciseCode, field, event.detail.value)
                          }}
                        />}
                    {weightUnavailable
                      ? <Text className='editor-field__lock'>无需锁定</Text>
                      : lock === 'RULE_LOCKED'
                      ? <Text className='editor-field__lock'>🔒 规则锁</Text>
                      : field === 'targetWeightKg' && typeof value !== 'number'
                        ? <Text className='editor-field__lock'>填写后可锁定</Text>
                      : <Button
                          size='mini'
                          className='secondary-action'
                          onClick={() => setEditor(application.setPlanFieldLock(path, lock === 'USER_LOCKED' ? 'UNLOCKED' : 'USER_LOCKED'))}
                        >{lock === 'USER_LOCKED' ? '解锁' : '锁定'}</Button>}
                  </View>
                )
              })}
            </View>
          ))}
          <Button className='secondary-action editor-add-action' loading={busy} onClick={() => void openExerciseOptions(day.code)}>＋ 添加模板动作</Button>
          {optionPicker?.dayCode === day.code && (
            <View className='exercise-option-panel'>
              <View className='editor-exercise__heading'>
                <Text className='section-title'>{optionPicker.replacing ? '选择替换动作' : '选择新增动作'}</Text>
                <Button size='mini' onClick={() => setOptionPicker(null)}>取消</Button>
              </View>
              {exerciseOptions.map((option) => (
                <Button key={option.exerciseCode} className='exercise-option' onClick={() => chooseExercise(option)}>
                  <Text>{option.name}</Text>
                  <Text className='code-label'>{option.workSets} 组 · {option.repMin}～{option.repMax} 次 · 休息 {option.restSeconds} 秒</Text>
                </Button>
              ))}
            </View>
          )}
        </View>
      ))}

      {editor.validationResult.validationIssues.map((issue) => (
        <View key={`${issue.fieldPath}-${issue.reasonCode}`} className={issue.severity === 'ERROR' ? 'error-box' : 'warning-box'}>
          <Text>{planIssueDisplayMessage(issue.reasonCode)}</Text>
          <Text className='code-label'>{issue.severity === 'ERROR' ? '保存前需修正' : '请确认'} · {planFieldDisplayName(issue.fieldPath)}</Text>
        </View>
      ))}
      {Object.entries(editor.lockedFieldOutcomes).map(([path, outcome]) => (
        <View key={path} className='info-box'>{planFieldDisplayName(path)}：{outcome === 'RULE_LOCKED' ? '由规则锁定，不能手动修改' : '已按你的选择锁定'}</View>
      ))}
      {editor.rebalanceDiffs.map((diff) => (
        <View key={diff.fieldPath} className='editor-diff'>{planFieldDisplayName(diff.fieldPath)}：{diff.before} → {diff.after}</View>
      ))}
      {editor.conflict && <View className='error-box'>{editor.conflict.message}</View>}
      {error && <View className='error-box'>{error}</View>}

      {editor.warningConfirmationToken && !editor.warningConfirmed && (
        <View className='warning-box'>服务端返回警告。请阅读上述问题并显式确认，第二次保存将携带一次性确认 token。</View>
      )}
      <View className='action-row action-row--sticky'>
        <Button className='secondary-action' loading={busy} onClick={() => void run(() => application.validateEditor())}>校验</Button>
        <Button className='secondary-action' loading={busy} onClick={() => void run(() => application.previewRebalance())}>重新优化预览</Button>
        {editor.warningConfirmationToken && !editor.warningConfirmed
          ? <Button className='primary-action' onClick={() => setEditor(application.confirmEditorWarnings())}>确认警告</Button>
          : <Button className='primary-action' loading={busy} onClick={() => void save()}>保存新版本</Button>}
      </View>
    </View>
  )
}
