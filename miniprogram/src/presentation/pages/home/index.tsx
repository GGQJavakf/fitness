import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { openWeappStartupHome } from '../../../platform/weapp/appRecovery'
import { useWeappFirstPaint } from '../../../platform/weapp/useWeappFirstPaint'

import './index.scss'

export default function BootstrapPage() {
  const [failed, setFailed] = useState(false)
  const mountedRef = useRef(true)
  const loadingRef = useRef(false)

  async function openStartup(): Promise<void> {
    if (loadingRef.current) return
    loadingRef.current = true
    if (mountedRef.current) setFailed(false)
    try {
      await openWeappStartupHome()
    } catch {
      if (mountedRef.current) setFailed(true)
    } finally {
      loadingRef.current = false
    }
  }

  useWeappFirstPaint(() => {
    void openStartup()
  })

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  return (
    <View className='bootstrap-page'>
      <View className='bootstrap-page__mark'>AI</View>
      <Text className='bootstrap-page__title'>AI 科学训练系统</Text>
      <Text className='bootstrap-page__message'>
        {failed ? '启动模块加载失败，请检查网络后重试' : '正在准备训练空间…'}
      </Text>
      {failed && (
        <Button className='bootstrap-page__retry' onClick={() => void openStartup()}>
          重新加载
        </Button>
      )}
    </View>
  )
}
