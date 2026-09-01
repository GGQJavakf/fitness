import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { components } from '../../../infrastructure/api/schema.generated'
import { getProgressApplication } from '../../../platform/weapp/featureRoots/progressCompositionRoot'
import { evidenceFieldDisplayName, evidenceRows, evidenceValueDisplayName } from '../../copy'

type Conflict = components['schemas']['SyncConflictData']
type Resolution = components['schemas']['ResolveSyncConflictRequest']['resolution']
export default function SyncConflictsPage() {
  const application = getProgressApplication()
  const [items, setItems] = useState<Conflict[]>([])
  const [message, setMessage] = useState('正在检查训练记录…')
  const [busyId, setBusyId] = useState('')
  const [loading, setLoading] = useState(false)
  const reloadActiveRef = useRef(false)
  const reloadRequestIdRef = useRef(0)
  const resolutionActiveRef = useRef(false)

  async function reload(): Promise<void> {
    if (reloadActiveRef.current) return
    reloadActiveRef.current = true
    const requestId = ++reloadRequestIdRef.current
    setLoading(true)
    try {
      const value = await application.reconcileSyncConflicts()
      if (requestId !== reloadRequestIdRef.current) return
      setItems(value)
      setMessage(value.length ? '发现不同版本的训练记录，请选择要保留的内容。' : '所有训练记录都已同步。')
    } catch {
      if (requestId !== reloadRequestIdRef.current) return
      setMessage('暂时无法完成上次选择或检查同步状态，设备中的训练记录仍会保留。')
    } finally {
      if (requestId === reloadRequestIdRef.current) {
        reloadActiveRef.current = false
        setLoading(false)
      }
    }
  }

  useEffect(() => {
    void reload()
    return () => {
      reloadRequestIdRef.current += 1
      reloadActiveRef.current = false
      resolutionActiveRef.current = false
    }
  }, [])

  async function resolve(conflict: Conflict, resolution: Resolution): Promise<void> {
    if (resolutionActiveRef.current) return
    resolutionActiveRef.current = true
    setBusyId(conflict.id)
    setMessage('正在保存你的选择…')
    try {
      await application.resolveSyncConflictWithLocalState({
        conflictId: conflict.id,
        clientKey: conflict.entityKey,
        resolution,
        expectedConflictVersion: conflict.version,
      })
      application.telemetry.track('sync_conflict_resolved', {
        resolution: ({ KEEP_LOCAL: 'keep_local', KEEP_SERVER: 'keep_server', KEEP_BOTH: 'keep_both' } as const)[resolution],
      })
      await reload()
    } catch {
      setMessage('服务端裁决或本机记录更新尚未完整完成；记录不会丢失，请稍后重试同一选择。')
    } finally {
      resolutionActiveRef.current = false
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

      {items.length === 0 && !loading && !message.includes('无法') && (
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
            <Text>系统会按你的选择完成服务端裁决，并使用返回的权威记录同步更新本机训练状态。</Text>
          </View>
          <View className='conflict-actions'>
            <Button className='primary-action' loading={busyId === item.id} disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_SERVER')}>使用已同步记录</Button>
            <View className='conflict-actions__alternatives'>
              <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_LOCAL')}>尝试保留设备记录</Button>
              <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_BOTH')}>尝试两份都保留</Button>
            </View>
          </View>
        </View>
      ))}

      {message.includes('无法') && <Button className='secondary-action conflict-retry' loading={loading} onClick={() => void reload()}>重新检查</Button>}
    </View>
  )
}
