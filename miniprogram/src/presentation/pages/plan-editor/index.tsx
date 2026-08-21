import { Button, Input, ScrollView, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type {
  PlanDayOption,
  PlanExerciseOption,
  PlanExerciseReplacementOption,
} from '../../../application/models'
import { ApplicationError } from '../../../application/errors'
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
const bodyPartDefinitions = [
  { name: '胸部', muscles: ['CHEST'] },
  { name: '背部', muscles: ['BACK', 'LATS'] },
  { name: '肩部', muscles: ['SHOULDERS'] },
  { name: '手臂', muscles: ['BICEPS', 'TRICEPS', 'FOREARMS'] },
  { name: '臀腿', muscles: ['GLUTES', 'QUADRICEPS', 'HAMSTRINGS', 'CALVES'] },
  { name: '核心', muscles: ['CORE', 'ABS'] },
] as const
const patternBodyParts: Readonly<Record<string, string>> = {
  HORIZONTAL_PUSH: '胸部',
  VERTICAL_PUSH: '肩部',
  SHOULDER_ABDUCTION: '肩部',
  SHOULDER_HORIZONTAL_ABDUCTION: '肩部',
  SCAPULAR_ELEVATION: '肩部',
  HORIZONTAL_PULL: '背部',
  VERTICAL_PULL: '背部',
  ELBOW_FLEXION: '手臂',
  ELBOW_EXTENSION: '手臂',
  SQUAT: '臀腿',
  HINGE: '臀腿',
  CALF_RAISE: '臀腿',
  CORE: '核心',
}

type ExerciseOption = PlanExerciseOption | PlanExerciseReplacementOption
type ExercisePickerMenu = 'BODY_PART' | 'EXERCISE' | null

function exerciseBodyPart(option: ExerciseOption): string {
  if (option.movementPattern && patternBodyParts[option.movementPattern]) {
    return patternBodyParts[option.movementPattern]
  }
  return bodyPartDefinitions.find((definition) =>
    definition.muscles.some((muscle) => option.primaryMuscles?.includes(muscle)),
  )?.name ?? '其他'
}

function exerciseBodyParts(options: readonly ExerciseOption[]): string[] {
  const available = new Set(options.map(exerciseBodyPart))
  const preferred = options.find((option) => 'matchReason' in option)
  return [...new Set([
    ...(preferred ? [exerciseBodyPart(preferred)] : []),
    ...bodyPartDefinitions.map((definition) => definition.name),
    '其他',
  ])].filter((name) => available.has(name))
}

function mergeExerciseOptions(
  preferred: readonly ExerciseOption[],
  additional: readonly ExerciseOption[],
): ExerciseOption[] {
  const merged = new Map<string, ExerciseOption>()
  preferred.forEach((option) => merged.set(option.exerciseCode, option))
  additional.forEach((option) => {
    if (!merged.has(option.exerciseCode)) merged.set(option.exerciseCode, option)
  })
  return [...merged.values()]
}

function reviewedReplacementsUnavailable(reason: unknown): boolean {
  return reason instanceof ApplicationError
    && (reason.code === 'RESOURCE_NOT_FOUND' || reason.code === 'INSUFFICIENT_REPLACEMENTS')
}

function editableValueError(field: EditableNumericField, raw: string): string {
  if (!raw.trim()) return `请输入${fieldLabels[field]}`
  const value = Number(raw)
  if (!Number.isFinite(value)) return `${fieldLabels[field]}必须是有效数字`
  if (field === 'targetWeightKg') {
    return value >= 0 && Math.abs(value * 100 - Math.round(value * 100)) < 1e-8
      ? ''
      : '目标重量必须是非负数，最多保留两位小数'
  }
  return Number.isInteger(value) && value > 0 ? '' : `${fieldLabels[field]}必须是正整数`
}

export default function PlanEditorPage() {
  const [editor, setEditor] = useState<PlanEditorState | null>(() => application.getPlanEditor())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [optionPicker, setOptionPicker] = useState<{ dayCode: string; replacing?: string } | null>(null)
  const [exerciseOptions, setExerciseOptions] = useState<readonly (
    PlanExerciseOption | PlanExerciseReplacementOption
  )[]>([])
  const [selectedBodyPart, setSelectedBodyPart] = useState('')
  const [selectedExerciseCode, setSelectedExerciseCode] = useState('')
  const [openExercisePickerMenu, setOpenExercisePickerMenu] = useState<ExercisePickerMenu>(null)
  const [dayOptions, setDayOptions] = useState<readonly PlanDayOption[]>([])
  const [showDayOptions, setShowDayOptions] = useState(false)
  const [pendingRemove, setPendingRemove] = useState('')
  const [rawValues, setRawValues] = useState<Record<string, string>>({})
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [expandedDayCode, setExpandedDayCode] = useState('')
  const [expandedExerciseKey, setExpandedExerciseKey] = useState('')
  const [showAdvancedTools, setShowAdvancedTools] = useState(false)
  const editorLoadInFlight = useRef(false)
  const editorLoadRequestId = useRef(0)
  const mounted = useRef(true)
  const mutationInFlight = useRef(false)

  async function loadEditor(): Promise<void> {
    if (editorLoadInFlight.current || editor) return
    editorLoadInFlight.current = true
    const requestId = ++editorLoadRequestId.current
    setError('')
    try {
      await application.loadActivePlan()
      const loaded = application.openPlanEditor()
      if (!mounted.current || requestId !== editorLoadRequestId.current) return
      setEditor(loaded)
      if (!loaded) setError('活动计划已变更，请重新读取。')
    } catch {
      if (mounted.current && requestId === editorLoadRequestId.current) {
        setError('活动计划加载失败，请检查网络后重试。')
      }
    } finally {
      editorLoadInFlight.current = false
    }
  }

  useEffect(() => {
    mounted.current = true
    if (!editor) void loadEditor()
    return () => {
      mounted.current = false
      editorLoadRequestId.current += 1
    }
  }, [])

  function edit(dayCode: string, exerciseCode: string, field: EditableNumericField, raw: string): void {
    const path = numericFieldPath(dayCode, exerciseCode, field)
    setRawValues((values) => ({ ...values, [path]: raw }))
    const fieldError = editableValueError(field, raw)
    setFieldErrors((errors) => {
      const next = { ...errors }
      if (fieldError) next[path] = fieldError
      else delete next[path]
      return next
    })
    if (fieldError) return
    const value = Number(raw)
    const next = application.editPlanNumber(dayCode, exerciseCode, field, value)
    setEditor(next)
    if (next !== editor) application.telemetry.track('plan_edited', { fieldKind: 'prescription' })
  }

  function rawValuesAreValid(): boolean {
    const errors: Record<string, string> = {}
    Object.entries(rawValues).forEach(([path, raw]) => {
      const segments = path.split('/')
      const field = segments[segments.length - 1] as EditableNumericField
      const fieldError = editableValueError(field, raw)
      if (fieldError) errors[path] = fieldError
    })
    setFieldErrors(errors)
    if (Object.keys(errors).length === 0) return true
    setError('请先修正标出的计划数值')
    return false
  }

  async function run(action: () => Promise<PlanEditorState>): Promise<void> {
    if (mutationInFlight.current) return
    if (!rawValuesAreValid()) return
    mutationInFlight.current = true
    setBusy(true)
    setError('')
    try {
      setEditor(await action())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请稍后重试')
    } finally {
      mutationInFlight.current = false
      setBusy(false)
    }
  }

  async function save(): Promise<void> {
    if (mutationInFlight.current) return
    if (!rawValuesAreValid()) return
    mutationInFlight.current = true
    const previousVersion = editor?.baseVersion ?? 0
    setBusy(true)
    setError('')
    try {
      const current = await application.saveEditor()
      setEditor(current)
      setRawValues({})
      setFieldErrors({})
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
      mutationInFlight.current = false
      setBusy(false)
    }
  }

  async function openExerciseOptions(dayCode: string, replacing?: string): Promise<void> {
    if (mutationInFlight.current) return
    mutationInFlight.current = true
    setBusy(true)
    setError('')
    try {
      let options: ExerciseOption[]
      if (replacing) {
        const [reviewed, additional] = await Promise.all([
          application.listPlanExerciseReplacements(dayCode, replacing).catch((reason) => {
            if (reviewedReplacementsUnavailable(reason)) return []
            throw reason
          }),
          application.listPlanExerciseOptions(dayCode),
        ])
        options = mergeExerciseOptions(reviewed, additional)
      } else {
        options = [...await application.listPlanExerciseOptions(dayCode)]
      }
      setExerciseOptions(options)
      setOptionPicker({ dayCode, ...(replacing ? { replacing } : {}) })
      setOpenExercisePickerMenu(null)
      const initialBodyPart = exerciseBodyParts(options)[0] ?? ''
      setSelectedBodyPart(initialBodyPart)
      setSelectedExerciseCode(options.find((option) => exerciseBodyPart(option) === initialBodyPart)?.exerciseCode ?? '')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '动作候选加载失败')
    } finally {
      mutationInFlight.current = false
      setBusy(false)
    }
  }

  async function openDayOptions(): Promise<void> {
    if (mutationInFlight.current) return
    mutationInFlight.current = true
    setBusy(true)
    setError('')
    try {
      const options = await application.listPlanDayOptions()
      setDayOptions(options)
      setShowDayOptions(true)
      if (options.length === 0) setError('当前模板没有可恢复的训练日；可先调整现有训练日和动作')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '训练日候选加载失败')
    } finally {
      mutationInFlight.current = false
      setBusy(false)
    }
  }

  function applyStructureEdit(action: () => PlanEditorState): void {
    if (mutationInFlight.current) return
    try {
      setEditor(action())
      application.telemetry.track('plan_edited', { fieldKind: 'structure' })
      setOptionPicker(null)
      setExerciseOptions([])
      setSelectedBodyPart('')
      setSelectedExerciseCode('')
      setOpenExercisePickerMenu(null)
      setShowDayOptions(false)
      setDayOptions([])
      setPendingRemove('')
      setRawValues({})
      setFieldErrors({})
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
    return (
      <View className='screen'>
        <View className='card'>
          <Text>{error || '正在加载编辑器…'}</Text>
          {error && <Button className='secondary-action' onClick={() => void loadEditor()}>重新读取计划</Button>}
        </View>
      </View>
    )
  }

  const expandedDay = editor.workingCopy.days.find((day) => day.code === expandedDayCode)
    ?? editor.workingCopy.days[0]
  const activeExercise = expandedDay?.exercises.find(
    (exercise) => `${expandedDay.code}:${exercise.exerciseCode}` === expandedExerciseKey,
  ) ?? expandedDay?.exercises[0]
  const availableBodyParts = exerciseBodyParts(exerciseOptions)
  const selectedBodyPartOptions = exerciseOptions.filter((option) => exerciseBodyPart(option) === selectedBodyPart)
  const selectedExercise = selectedBodyPartOptions.find((option) => option.exerciseCode === selectedExerciseCode)

  return (
    <View className='screen'>
      <View className='card'>
        <Text className='title'>{editor.workingCopy.name}</Text>
        <Text className='subtitle'>
          {editor.baseVersion === 0
            ? '这是启用前的候选方案。保存后会生成第一版计划。'
            : `正在基于第 ${editor.baseVersion} 版调整；保存会生成新版本，已有训练记录不会改变。`}
          你修改并锁定的数值会在后续优化中保留；安全边界内的固定项不能手动修改。
        </Text>
      </View>

      <View className='editor-day-list' aria-label='选择要编辑的训练日'>
        {editor.workingCopy.days.map((day) => {
          const selected = day.code === expandedDay?.code
          return (
            <Button
              key={day.code}
              className={`editor-day-summary ${selected ? 'editor-day-summary--active' : ''}`.trim()}
              aria-label={`${selected ? '当前训练日，' : ''}${day.name}，${day.exercises.length}个动作`}
              onClick={() => {
                setExpandedDayCode(day.code)
                setExpandedExerciseKey(day.exercises[0]
                  ? `${day.code}:${day.exercises[0].exerciseCode}`
                  : '')
                setPendingRemove('')
                setOptionPicker(null)
                setOpenExercisePickerMenu(null)
              }}
            >
              <Text className='editor-day-summary__name'>{day.name}</Text>
              <Text className='code-label'>{day.exercises.length} 个动作</Text>
            </Button>
          )
        })}
      </View>

      {expandedDay && (
        <View className='card editor-day-detail'>
          <View className='editor-exercise__heading editor-day-heading'>
            <View>
              <Text className='section-title'>{expandedDay.name} · 动作设置</Text>
              <Text className='code-label'>先选动作，再调整训练处方</Text>
            </View>
            <View className='editor-structure-actions'>
              {editor.workingCopy.days.indexOf(expandedDay) > 0 && (
                <Button
                  className='editor-structure-action'
                  aria-label={`将${expandedDay.name}上移`}
                  onClick={() => applyStructureEdit(() => application.movePlanDay(expandedDay.code, -1))}
                >上移日</Button>
              )}
              {editor.workingCopy.days.indexOf(expandedDay) < editor.workingCopy.days.length - 1 && (
                <Button
                  className='editor-structure-action'
                  aria-label={`将${expandedDay.name}下移`}
                  onClick={() => applyStructureEdit(() => application.movePlanDay(expandedDay.code, 1))}
                >下移日</Button>
              )}
              <Button
                className={`editor-structure-action ${pendingRemove === `day:${expandedDay.code}` ? 'danger-action' : ''}`.trim()}
                aria-label={`${pendingRemove === `day:${expandedDay.code}` ? '确认删除' : '删除'}${expandedDay.name}`}
                onClick={() => {
                  const key = `day:${expandedDay.code}`
                  if (pendingRemove !== key) return setPendingRemove(key)
                  applyStructureEdit(() => application.removePlanDay(expandedDay.code))
                }}
              >{pendingRemove === `day:${expandedDay.code}` ? '确认删除日' : '删除日'}</Button>
            </View>
          </View>

          <View className='editor-exercise-list' aria-label={`${expandedDay.name}动作列表`}>
            {expandedDay.exercises.map((exercise) => {
              const selected = exercise.exerciseCode === activeExercise?.exerciseCode
              return (
                <Button
                  key={exercise.exerciseCode}
                  className={`editor-exercise-summary ${selected ? 'editor-exercise-summary--active' : ''}`.trim()}
                  aria-label={`${selected ? '当前动作，' : ''}${exerciseDisplayName(exercise.exerciseCode)}，${exercise.workSets}组，每组${exercise.repMin}到${exercise.repMax}次`}
                  onClick={() => {
                    setExpandedExerciseKey(`${expandedDay.code}:${exercise.exerciseCode}`)
                    setPendingRemove('')
                    setOptionPicker(null)
                    setOpenExercisePickerMenu(null)
                  }}
                >
                  <Text className='editor-exercise-summary__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                  <Text className='code-label'>{exercise.workSets} 组 · {exercise.repMin}～{exercise.repMax} 次</Text>
                </Button>
              )
            })}
          </View>

          {activeExercise && (() => {
            const exercise = activeExercise
            const exerciseIndex = expandedDay.exercises.indexOf(exercise)
            return (
              <View className='editor-exercise'>
                <View className='editor-exercise__heading'>
                  <View>
                    <Text className='section-title'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                    <Text className='code-label'>训练处方与锁定项</Text>
                  </View>
                  <View className='editor-structure-actions'>
                    {exerciseIndex > 0 && (
                      <Button className='editor-structure-action' onClick={() => applyStructureEdit(() => application.movePlanExercise(expandedDay.code, exercise.exerciseCode, -1))}>上移</Button>
                    )}
                    {exerciseIndex < expandedDay.exercises.length - 1 && (
                      <Button className='editor-structure-action' onClick={() => applyStructureEdit(() => application.movePlanExercise(expandedDay.code, exercise.exerciseCode, 1))}>下移</Button>
                    )}
                    <Button className='editor-structure-action' loading={busy} onClick={() => void openExerciseOptions(expandedDay.code, exercise.exerciseCode)}>替换</Button>
                    <Button
                      className={`editor-structure-action ${pendingRemove === `${expandedDay.code}:${exercise.exerciseCode}` ? 'danger-action' : ''}`.trim()}
                      onClick={() => {
                        const key = `${expandedDay.code}:${exercise.exerciseCode}`
                        if (pendingRemove !== key) return setPendingRemove(key)
                        applyStructureEdit(() => application.removePlanExercise(expandedDay.code, exercise.exerciseCode))
                      }}
                    >{pendingRemove === `${expandedDay.code}:${exercise.exerciseCode}` ? '确认删除' : '删除'}</Button>
                  </View>
                </View>
                {editableFields.map((field) => {
                  const path = numericFieldPath(expandedDay.code, exercise.exerciseCode, field)
                  const lock = editor.locks[path] ?? 'UNLOCKED'
                  const weightUnavailable = field === 'targetWeightKg' && exercise.weightStatus === 'BODYWEIGHT'
                  const value = exercise[field]
                  const rawValue = rawValues[path]
                  return (
                    <View key={field} className='editor-field'>
                      <Text>{fieldLabels[field]}</Text>
                      {weightUnavailable
                        ? <Text className='editor-field__hint'>自重动作</Text>
                        : (
                          <View className='editor-field__input-group'>
                            <Input
                              className='editor-field__input'
                              type='digit'
                              value={rawValue ?? (typeof value === 'number' ? String(value) : '')}
                              placeholder={field === 'targetWeightKg' ? '先校准' : ''}
                              disabled={lock === 'RULE_LOCKED'}
                              aria-label={`${exerciseDisplayName(exercise.exerciseCode)}${fieldLabels[field]}`}
                              onInput={(event) => edit(expandedDay.code, exercise.exerciseCode, field, event.detail.value)}
                            />
                            {fieldErrors[path] && <Text className='editor-field__error'>{fieldErrors[path]}</Text>}
                          </View>
                        )}
                      {weightUnavailable
                        ? <Text className='editor-field__lock'>无需锁定</Text>
                        : lock === 'RULE_LOCKED'
                        ? <Text className='editor-field__lock'>安全边界固定</Text>
                        : field === 'targetWeightKg' && typeof value !== 'number'
                          ? <Text className='editor-field__lock'>填写后可锁定</Text>
                        : <Button
                            className='secondary-action editor-lock-action'
                            disabled={Boolean(fieldErrors[path])}
                            aria-label={`${lock === 'USER_LOCKED' ? '解锁' : '锁定'}${exerciseDisplayName(exercise.exerciseCode)}${fieldLabels[field]}`}
                            onClick={() => setEditor(application.setPlanFieldLock(path, lock === 'USER_LOCKED' ? 'UNLOCKED' : 'USER_LOCKED'))}
                          >{lock === 'USER_LOCKED' ? '解锁' : '锁定'}</Button>}
                    </View>
                  )
                })}
              </View>
            )
          })()}

          <Button className='secondary-action editor-add-action' loading={busy} onClick={() => void openExerciseOptions(expandedDay.code)}>添加动作</Button>
          {optionPicker?.dayCode === expandedDay.code && (
            <View className='exercise-option-panel'>
              <View className='editor-exercise__heading'>
                <Text className='section-title'>{optionPicker.replacing ? '选择替换动作' : '选择新增动作'}</Text>
                <Button onClick={() => {
                  setOptionPicker(null)
                  setExerciseOptions([])
                  setSelectedBodyPart('')
                  setSelectedExerciseCode('')
                  setOpenExercisePickerMenu(null)
                }}>取消</Button>
              </View>
              {exerciseOptions.length === 0 && (
                <View className='exercise-picker__empty'>
                  <Text className='exercise-picker__empty-title'>
                    {optionPicker.replacing ? '当前没有可替换的动作' : '当前没有可添加的动作'}
                  </Text>
                  <Text className='exercise-picker__hint'>
                    已按本训练日分化、器械和排除偏好筛选。请调整可用器械、排除动作，或切换训练日后重试。
                  </Text>
                </View>
              )}
              {exerciseOptions.length > 0 && (
                <>
                  <Text className='exercise-picker__hint'>先选部位，再选动作</Text>
                  <View className='exercise-picker__field'>
                    <Text className='exercise-picker__label'>1. 训练部位</Text>
                    <Button
                      className={`exercise-picker__control ${openExercisePickerMenu === 'BODY_PART' ? 'exercise-picker__control--open' : ''}`.trim()}
                      aria-label='选择训练部位'
                      aria-expanded={openExercisePickerMenu === 'BODY_PART'}
                      onClick={() => setOpenExercisePickerMenu(
                        openExercisePickerMenu === 'BODY_PART' ? null : 'BODY_PART',
                      )}
                    >
                      <View className='exercise-picker__control-copy'>
                        <Text className='exercise-picker__control-value'>{selectedBodyPart || '请选择训练部位'}</Text>
                        <Text className='exercise-picker__control-meta'>{availableBodyParts.length} 个部位可选</Text>
                      </View>
                      <View className={`exercise-picker__chevron ${openExercisePickerMenu === 'BODY_PART' ? 'exercise-picker__chevron--open' : ''}`.trim()} />
                    </Button>
                    {openExercisePickerMenu === 'BODY_PART' && (
                      <View className='exercise-picker__menu exercise-picker__body-parts' aria-label='训练部位选项'>
                        {availableBodyParts.map((bodyPart) => (
                          <Button
                            key={bodyPart}
                            className={`exercise-picker__body-part ${bodyPart === selectedBodyPart ? 'exercise-picker__body-part--active' : ''}`.trim()}
                            aria-label={`选择训练部位：${bodyPart}`}
                            onClick={() => {
                              const firstOption = exerciseOptions.find((option) => exerciseBodyPart(option) === bodyPart)
                              setSelectedBodyPart(bodyPart)
                              setSelectedExerciseCode(firstOption ? firstOption.exerciseCode : '')
                              setOpenExercisePickerMenu('EXERCISE')
                            }}
                          >{bodyPart}</Button>
                        ))}
                      </View>
                    )}
                  </View>
                  <View className='exercise-picker__field'>
                    <Text className='exercise-picker__label'>2. 动作</Text>
                    <Button
                      className={`exercise-picker__control ${openExercisePickerMenu === 'EXERCISE' ? 'exercise-picker__control--open' : ''}`.trim()}
                      aria-label='选择动作'
                      aria-expanded={openExercisePickerMenu === 'EXERCISE'}
                      disabled={selectedBodyPartOptions.length === 0}
                      onClick={() => setOpenExercisePickerMenu(
                        openExercisePickerMenu === 'EXERCISE' ? null : 'EXERCISE',
                      )}
                    >
                      <View className='exercise-picker__control-copy'>
                        <Text className='exercise-picker__control-value'>{selectedExercise ? selectedExercise.name : '请选择动作'}</Text>
                        <Text className='exercise-picker__control-meta'>{selectedBodyPartOptions.length} 个动作可选</Text>
                      </View>
                      <View className={`exercise-picker__chevron ${openExercisePickerMenu === 'EXERCISE' ? 'exercise-picker__chevron--open' : ''}`.trim()} />
                    </Button>
                    {openExercisePickerMenu === 'EXERCISE' && (
                      <ScrollView scrollY className='exercise-picker__menu exercise-picker__actions' aria-label={`${selectedBodyPart}动作选项`}>
                        <View className='exercise-picker__action-list'>
                          {selectedBodyPartOptions.map((option) => {
                            const active = option.exerciseCode === selectedExerciseCode
                            return (
                              <Button
                                key={option.exerciseCode}
                                className={`exercise-picker__action ${active ? 'exercise-picker__action--active' : ''}`.trim()}
                                aria-label={`选择动作：${option.name}`}
                                onClick={() => {
                                  setSelectedExerciseCode(option.exerciseCode)
                                  setOpenExercisePickerMenu(null)
                                }}
                              >
                                <View className='exercise-picker__action-copy'>
                                  <Text className='exercise-picker__action-name'>{option.name}</Text>
                                  <Text className='exercise-picker__action-meta'>
                                    {option.workSets} 组 · {option.repMin}～{option.repMax} 次 · 休息 {option.restSeconds} 秒
                                  </Text>
                                </View>
                                {active && <Text className='exercise-picker__selected-mark'>已选</Text>}
                              </Button>
                            )
                          })}
                        </View>
                      </ScrollView>
                    )}
                  </View>
                  {selectedExercise && (
                    <View className='exercise-picker__preview'>
                      <Text>{selectedExercise.workSets} 组 · {selectedExercise.repMin}～{selectedExercise.repMax} 次 · 休息 {selectedExercise.restSeconds} 秒</Text>
                      <Text className='code-label'>使用所选动作的安全处方；器械动作会按需要重新校准重量</Text>
                      {'matchReason' in selectedExercise && (
                        <Text className='code-label'>优先推荐：同动作模式、同主要肌群、同难度</Text>
                      )}
                    </View>
                  )}
                  <Button
                    className='primary-action exercise-picker__confirm'
                    disabled={!selectedExercise}
                    onClick={() => selectedExercise && chooseExercise(selectedExercise)}
                  >{optionPicker.replacing ? '确认替换' : '确认添加'}</Button>
                </>
              )}
            </View>
          )}
        </View>
      )}

      <View className='card'>
        <Button className='secondary-action editor-add-action' loading={busy} onClick={() => void openDayOptions()}>恢复训练日</Button>
        {showDayOptions && (
          <View className='exercise-option-panel'>
            <View className='editor-exercise__heading'>
              <Text className='section-title'>选择训练日</Text>
              <Button onClick={() => setShowDayOptions(false)}>取消</Button>
            </View>
            {dayOptions.map((option) => (
              <Button key={option.code} className='exercise-option' onClick={() => applyStructureEdit(() => application.addPlanDay(option))}>
                <Text>{option.name}</Text>
                <Text className='code-label'>{option.exercises.length} 个模板动作</Text>
              </Button>
            ))}
          </View>
        )}
      </View>

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
      <View className='card editor-advanced-tools'>
        <Button
          className='editor-advanced-toggle'
          aria-label={`${showAdvancedTools ? '收起' : '展开'}高级检查与调整`}
          onClick={() => setShowAdvancedTools((visible) => !visible)}
        >{showAdvancedTools ? '收起高级检查与调整' : '高级检查与调整'}</Button>
        {showAdvancedTools && (
          <View className='editor-advanced-actions'>
            <Button className='secondary-action' loading={busy} onClick={() => void run(() => application.validateEditor())}>校验计划</Button>
            <Button className='secondary-action' loading={busy} onClick={() => void run(() => application.previewRebalance())}>查看系统调整建议</Button>
          </View>
        )}
      </View>
      <View className='action-row action-row--sticky'>
        {editor.warningConfirmationToken && !editor.warningConfirmed
          ? <Button className='primary-action editor-sticky-primary' onClick={() => setEditor(application.confirmEditorWarnings())}>确认警告</Button>
          : <Button className='primary-action editor-sticky-primary' loading={busy} onClick={() => void save()}>保存新版本</Button>}
      </View>
    </View>
  )
}
