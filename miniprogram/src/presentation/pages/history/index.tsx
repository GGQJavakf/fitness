import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { toWorkoutHistoryCard, type WorkoutHistoryCard } from '../../../application/history'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()

export default function HistoryPage() {
  const [items, setItems] = useState<WorkoutHistoryCard[]>([])
  const [cursor, setCursor] = useState<string | undefined>()
  const [message, setMessage] = useState('正在读取训练历史…')
  const [loading, setLoading] = useState(false)

  async function load(nextCursor?: string): Promise<void> {
    if (loading) return
    setLoading(true)
    try {
      const page = await application.listWorkoutHistory(nextCursor)
      const cards = page.items.map(toWorkoutHistoryCard)
      setItems((current) => nextCursor ? [...current, ...cards] : cards)
      setCursor(page.nextCursor)
      setMessage(cards.length || nextCursor ? '训练事实来自服务端记录。' : '还没有已完成或提前结束的训练。')
    } catch {
      setMessage('历史暂时无法加载，请检查网络后重试；本地训练草稿不会被删除。')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  return <View className='screen'>
    <View className='card'><Text className='title'>训练历史</Text><Text className='subtitle'>{message}</Text></View>
    {items.map((item) => <View className='card history-card' key={item.id}>
      <View className='history-row'><Text className='section-title'>{item.title}</Text>
        <Text className={item.incomplete ? 'history-status history-status--incomplete' : 'history-status'}>{item.statusLabel}</Text></View>
      <Text>{item.factsLabel}</Text><Text className='subtitle'>{item.timeLabel}</Text>
    </View>)}
    {cursor && <Button className='secondary-action' disabled={loading} onClick={() => void load(cursor)}>{loading ? '加载中…' : '加载更多'}</Button>}
    {!cursor && message.includes('无法加载') && <Button className='secondary-action' disabled={loading} onClick={() => void load()}>重试</Button>}
  </View>
}
