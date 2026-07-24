import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { AppDestination } from '../../../application/onboarding'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

export default function HomePage() {
  const application = getWeappApplication()
  const [destination, setDestination] = useState<AppDestination | 'LOADING'>('LOADING')
  const [message, setMessage] = useState('正在恢复本地会话…')

  useEffect(() => {
    void application.startup.start()
      .then((next) => {
        setDestination(next)
        setMessage(next === 'LOGIN'
          ? '使用微信登录后开始 3 分钟建档'
          : '档案已就绪，可以继续生成或查看计划')
      })
      .catch(() => {
        setDestination('LOGIN')
        setMessage('暂时无法连接本地服务，请检查网络后重试')
      })
  }, [])

  async function login(): Promise<void> {
    setMessage('正在安全登录…')
    try {
      const next = await application.startup.login()
      setDestination(next)
      setMessage(next === 'ONBOARDING' ? '即将进入成年与安全说明' : '登录成功')
    } catch {
      setMessage('微信登录失败，请稍后重试；临时登录码不会保存')
    }
  }

  return (
    <View className='home-page screen'>
      <View className='home-page__hero card'>
        <Text className='home-page__eyebrow'>P0 · 微信小程序 · KG</Text>
        <Text className='title'>稳健开始你的训练计划</Text>
        <Text className='subtitle'>仅面向已满 18 周岁的成年人。提供一般健身计划，不提供医疗诊断或康复处方。</Text>
        <View className='info-box'>
          <Text>{message}</Text>
        </View>
        {destination === 'LOGIN' && (
          <Button className='primary-action' onClick={() => void login()}>微信登录</Button>
        )}
        {destination === 'HOME' && (
          <View className='action-row'>
            <Button className='primary-action' onClick={() => void application.navigation.open('PLAN')}>进入我的计划</Button>
            <Button className='secondary-action' onClick={() => void application.navigation.open('ONBOARDING')}>重新设置档案</Button>
          </View>
        )}
      </View>
      <View className='card'>
        <Text className='section-title'>当前范围</Text>
        <Text className='subtitle'>已支持建档、计划确认、训练记录、休息计时和历史查看。重量、组数、次数和进阶结论始终由确定性规则计算。</Text>
      </View>
      {destination !== 'LOADING' && (
        <Button
          className='secondary-action'
          onClick={() => void application.navigation.open('MY')}
        >我的与隐私</Button>
      )}
    </View>
  )
}
