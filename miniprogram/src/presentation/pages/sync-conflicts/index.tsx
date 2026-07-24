import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { components } from '../../../infrastructure/api/schema.generated'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { evidenceRows } from '../../copy'

import './index.scss'

type Conflict = components['schemas']['SyncConflictData']
type Resolution = components['schemas']['ResolveSyncConflictRequest']['resolution']
const application = getWeappApplication()

export default function SyncConflictsPage() {
  const [items, setItems] = useState<Conflict[]>([])
  const [message, setMessage] = useState('正在读取待处理冲突…')
  const [busyId, setBusyId] = useState('')
  const reload = () => application.listSyncConflicts().then((value) => {
    setItems(value); setMessage(value.length ? '请选择保留方式；系统不会静默覆盖。' : '当前没有待处理冲突。')
  }).catch(() => setMessage('冲突列表暂时无法连接；本地证据仍保留。'))
  useEffect(() => { void reload() }, [])

  async function resolve(conflict: Conflict, resolution: Resolution): Promise<void> {
    if (busyId) return
    setBusyId(conflict.id)
    setMessage('正在保存你的选择…')
    try {
      await application.resolveSyncConflict(conflict.id, { resolution, expectedVersion: conflict.version })
      await reload()
    } catch {
      setMessage('处理失败，两份证据都已保留，请稍后重试。')
    } finally {
      setBusyId('')
    }
  }

  return <View className='screen'><View className='card'><Text className='title'>同步冲突</Text><Text className='subtitle'>{message}</Text></View>
    {items.map((item) => <View className='card' key={item.id}>
      <Text className='section-title'>训练组 {item.entityKey}</Text>
      <View className='evidence'><Text className='field-label'>本地记录</Text>{evidenceRows(item.localEvidence).map(([key, value]) => <View className='evidence__row' key={key}><Text>{key}</Text><Text>{value}</Text></View>)}</View>
      <View className='evidence'><Text className='field-label'>服务端记录</Text>{evidenceRows(item.serverEvidence).map(([key, value]) => <View className='evidence__row' key={key}><Text>{key}</Text><Text>{value}</Text></View>)}</View>
      <View className='action-row'>
        <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_LOCAL')}>保留本地</Button>
        <Button className='secondary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_SERVER')}>保留服务端</Button>
        <Button className='primary-action' disabled={Boolean(busyId)} onClick={() => void resolve(item, 'KEEP_BOTH')}>两份都保留</Button>
      </View>
    </View>)}
  </View>
}
