import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

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
}
const editableFields = Object.keys(fieldLabels) as EditableNumericField[]

export default function PlanEditorPage() {
  const [editor, setEditor] = useState<PlanEditorState | null>(() => application.getPlanEditor())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

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
                <Text className='section-title'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                <Text className='code-label'>{exercise.exerciseCode}</Text>
              </View>
              {editableFields.map((field) => {
                const path = numericFieldPath(day.code, exercise.exerciseCode, field)
                const lock = editor.locks[path] ?? 'UNLOCKED'
                return (
                  <View key={field} className='editor-field'>
                    <Text>{fieldLabels[field]}</Text>
                    <Input
                      className='editor-field__input'
                      type='number'
                      value={String(exercise[field])}
                      disabled={lock === 'RULE_LOCKED'}
                      onInput={(event) => edit(day.code, exercise.exerciseCode, field, event.detail.value)}
                    />
                    {lock === 'RULE_LOCKED'
                      ? <Text className='editor-field__lock'>🔒 规则锁</Text>
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
