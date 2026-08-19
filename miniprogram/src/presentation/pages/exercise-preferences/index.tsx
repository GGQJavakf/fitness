import { Button, Text, View } from '@tarojs/components'
import { useEffect, useMemo, useRef, useState } from 'react'

import type { ExerciseContent } from '../../../application/content'
import type { ExercisePreference } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()

export default function ExercisePreferencesPage() {
  const [exercises, setExercises] = useState<readonly ExerciseContent[]>([])
  const [preferences, setPreferences] = useState<readonly ExercisePreference[]>([])
  const [version, setVersion] = useState(0)
  const [message, setMessage] = useState('正在读取可选动作…')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const loadRequestIdRef = useRef(0)
  const loadInFlightRef = useRef(false)
  const saveInFlightRef = useRef(false)

  const excluded = useMemo(
    () => new Set(preferences.filter((item) => item.preferenceType === 'EXCLUDED').map((item) => item.exerciseId)),
    [preferences],
  )

  async function load(): Promise<void> {
    if (loadInFlightRef.current) return
    loadInFlightRef.current = true
    const requestId = ++loadRequestIdRef.current
    setLoading(true)
    setMessage('正在读取可选动作…')
    try {
      const [available, profile] = await Promise.all([
        application.listExercises(),
        application.getExercisePreferences(),
      ])
      if (requestId !== loadRequestIdRef.current) return
      setExercises(available)
      setPreferences(profile.items)
      setVersion(profile.version)
      setMessage('')
    } catch {
      if (requestId !== loadRequestIdRef.current) return
      setMessage('动作偏好暂时无法读取，请检查网络后重试。')
    } finally {
      loadInFlightRef.current = false
      if (requestId === loadRequestIdRef.current) setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    return () => { loadRequestIdRef.current += 1 }
  }, [])

  function toggle(exerciseId: string): void {
    setPreferences((current) => {
      const retained = current.filter(
        (item) => !(item.exerciseId === exerciseId && item.preferenceType === 'EXCLUDED'),
      )
      return excluded.has(exerciseId)
        ? retained
        : [...retained, { exerciseId, preferenceType: 'EXCLUDED' }]
    })
  }

  async function savePreferences(): Promise<void> {
    if (saveInFlightRef.current) return
    saveInFlightRef.current = true
    setSaving(true)
    setMessage('')
    try {
      const saved = await application.saveExercisePreferences({
        items: [...preferences],
        expectedVersion: version,
      })
      setVersion(saved.version)
      setMessage('已保存。下次生成或调整计划时会避开这些动作。')
    } catch {
      setMessage('保存失败，档案可能已在其他设备更新，请返回后重新进入。')
    } finally {
      saveInFlightRef.current = false
      setSaving(false)
    }
  }

  return (
    <View className='screen exercise-preferences-page'>
      <View className='page-hero preference-hero'>
        <Text className='page-hero__eyebrow'>TRAINING PREFERENCE</Text>
        <Text className='page-hero__title'>不推荐这些动作</Text>
        <Text className='page-hero__description'>只选择你明确不想练或当前不适合的动作。后续推荐会自动避开。</Text>
      </View>

      <View className='preference-note'>
        <Text className='preference-note__title'>疼痛不是普通偏好</Text>
        <Text className='preference-note__text'>如果动作引起疼痛或明显不适，请停止训练并按需要咨询专业人员。</Text>
      </View>

      <View className='surface-card preference-list'>
        {exercises.map((exercise) => {
          const selected = excluded.has(exercise.id)
          return (
            <Button
              key={exercise.id}
              className={selected ? 'preference-item preference-item--selected' : 'preference-item'}
              onClick={() => toggle(exercise.id)}
            >
              <View className='preference-item__copy'>
                <Text className='preference-item__name'>{exercise.name}</Text>
                <Text className='preference-item__description'>{exercise.plainLanguage}</Text>
              </View>
              <View className='preference-item__check'>{selected ? '✓' : ''}</View>
            </Button>
          )
        })}
        {!exercises.length && <Text className='preference-empty'>{message}</Text>}
        {!exercises.length && message.includes('无法读取') && (
          <Button className='secondary-action' loading={loading} onClick={() => void load()}>重新加载动作偏好</Button>
        )}
      </View>

      {message && exercises.length > 0 && <View className='profile-message'><Text>{message}</Text></View>}
      <View className='action-row action-row--sticky'>
        <Button className='primary-action' loading={saving} disabled={saving || !exercises.length} onClick={() => void savePreferences()}>
          保存动作偏好
        </Button>
      </View>
    </View>
  )
}
