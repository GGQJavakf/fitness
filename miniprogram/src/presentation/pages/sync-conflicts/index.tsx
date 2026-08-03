import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { components } from '../../../infrastructure/api/schema.generated'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { evidenceFieldDisplayName, evidenceRows, evidenceValueDisplayName } from '../../copy'

import './index.scss'

type Conflict = components['schemas']['SyncConflictData']
type Resolution = components['schemas']['ResolveSyncConflictRequest']['resolution']
const application = getWeappApplication()

export default function SyncConflictsPage() {
  const [items, setItems] = useState<Conflict[]>([])
  const [message, setMessage] = useState('正在检查训练记录…')
  const [busyId, setBusyId] = useState('')
  const [loading, setLoading] = useState(false)

  async function reload(): Promise<void> {
    setLoading(true)
    try {
      const value = await application.listSyncConflicts()
      setItems(value)
      setMessage(value.length ? '发现不同版本的训练记录，请选择要保留的内容。' : '所有训练记录都已同步。')
    } catch {
      setMessage('暂时无法检查同步状态，设备中的训练记录仍会保留。')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void reload() }, [])

  async function resolve(conflict: Conflict, resolution: Resolution): Promise<void> {
    if (busyId) return
    setBusyId(conflict.id)
    setMessage('正在保存你的选择…')
    try {
      await application.resolveSyncConflict(conflict.id, { resolution, expectedVersion: conflict.version })
      application.telemetry.track('sync_conflict_resolved', {
        resolution: ({ KEEP_LOCAL: 'keep_local', KEEP_SERVER: 'keep_server', KEEP_BOTH: 'keep_both' } as const)[resolution],
      })
      await reload()
    } catch {
      setMessage('这次选择暂未保存，两份记录都还在，请稍后重试。')
    } finally {
      setBusyId('')
    }
  }

  return (
    <View className='screen sync-conflicts-page'>
      <View className='page-hero conflict-hero'>
        <Text className='page-hero__eyebrow'>RECORD RECOVERY</Text>
        <Text className='page-hero__title'>训练记录待确认</Text>
        <Text className='page-hero__description'>{message}</Text>
        {items.length > 0 && <Text className='conflict-hero__count data-number'>{items.length} 项待处理</Text>}
      </View>

      {items.length === 0 && !loading && (
        <View className='surface-card empty-state conflict-empty'>
          <View className='conflict-empty__mark'>
            <View className='conflict-empty__check' />
          </View>
          <Text className='section-title'>记录状态正常</Text>
          <Text className='subtitle'>当前没有需要你确认的训练记录。</Text>
          <Button className='primary-action' onClick={() => void application.navigation.back()}>返回训练</Button>
        </View>
      )}

      {items.map((item, index) => (
        <View className='surface-card conflict-card' key={item.id}>
          <View className='conflict-card__heading'>
            <View>
              <Text className='conflict-card__eyebrow'>待确认记录 {String(index + 1).padStart(2, '0')}</Text>
              <Text className='conflict-card__title'>同一训练组有两份记录</Text>
            </View>
            <View className='status-pill'>请选择</View>
          </View>

          <View className='conflict-compare'>
            <View className='evidence evidence--device'>
              <View className='evidence__heading'>
                <View className='evidence__dot' />
                <Text>设备中的记录</Text>
              </View>
              {evidenceRows(item.localEvidence).map(([key, value], rowIndex) => (
                <View className='evidence__row' key={`${key}-${rowIndex}`}>
                  <Text>{evidenceFieldDisplayName(key)}</Text>
                  <Text>{evidenceValueDisplayName(value)}</Text>
                </View>
              ))}
            </View>
            <View className='evidence evidence--synced'>
              <View className='evidence__heading'>
                <View className='evidence__dot' />
                <Text>已同步的记录</Text>
              </View>
              {evidenceRows(item.serverEvidence).map(([key, value], rowIndex) => (
                <View className='evidence__row' key={`${key}-${rowIndex}`}>
                  <Text>{evidenceFieldDisplayName(key)}</Text>
                  <Text>{evidenceValueDisplayName(value)}</Text>
                </View>
              ))}
            </View>
          </View>

          <View className='conflict-recommendation'>
            <View className='conflict-recommendation__line' />
            <Text>如果不确定，建议两份都保留，之后再根据训练回顾判断。</Text>
          </View>
          <View className='conflict-actions'>
            <Button className='primary-action' loading={busyId === item.id} disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_BOTH')}>两份都保留</Button>
            <View className='conflict-actions__alternatives'>
              <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_LOCAL')}>使用设备记录</Button>
              <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_SERVER')}>使用已同步记录</Button>
            </View>
          </View>
        </View>
      ))}

      {message.includes('无法检查') && <Button className='secondary-action conflict-retry' loading={loading} onClick={() => void reload()}>重新检查</Button>}
    </View>
  )
}
