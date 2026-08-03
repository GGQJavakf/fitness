import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { AppDestination } from '../../../application/onboarding'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'
import { runSingleFlight } from './loginSingleFlight'

import './index.scss'

export default function HomePage() {
  const application = getWeappApplication()
  const [destination, setDestination] = useState<AppDestination | 'LOADING'>('LOADING')
  const [message, setMessage] = useState('正在恢复本地会话…')
  const [hasActiveWorkout, setHasActiveWorkout] = useState(false)
  const [isLoggingIn, setIsLoggingIn] = useState(false)
  const loginInFlight = useRef(false)

  useEffect(() => {
    void application.startup.start()
      .then((next) => {
        setDestination(next)
        if (next === 'HOME') void application.hasActiveWorkout().then(setHasActiveWorkout).catch(() => setHasActiveWorkout(false))
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
    await runSingleFlight(loginInFlight, setIsLoggingIn, async () => {
      setMessage('正在安全登录…')
      try {
        const next = await application.startup.login()
        setDestination(next)
        setMessage(next === 'ONBOARDING' ? '即将进入成年与安全说明' : '登录成功')
      } catch {
        setMessage('微信登录失败，请稍后重试；临时登录码不会保存')
      }
    })
  }

  const statusLabel = destination === 'LOADING'
    ? '正在连接'
    : destination === 'LOGIN'
      ? '待建立档案'
      : '训练档案已就绪'

  return (
    <View className={`home-page screen ${destination === 'HOME' ? 'screen--with-nav' : ''}`}>
      <View className='home-page__content'>
        <View className='home-page__hero'>
          <View className='home-page__orb home-page__orb--large' />
          <View className='home-page__orb home-page__orb--small' />

          <View className='home-page__brand'>
            <View className='home-page__brand-mark'>
              <View className='home-page__brand-line home-page__brand-line--short' />
              <View className='home-page__brand-line' />
              <View className='home-page__brand-line home-page__brand-line--medium' />
            </View>
            <Text className='home-page__eyebrow'>AI 科学训练系统</Text>
          </View>

          <View className='home-page__hero-copy'>
            <Text className='home-page__title'>先了解你，{'\n'}再开始训练</Text>
            <Text className='home-page__subtitle'>结合目标、时间和训练条件，为你建立可持续进阶的个性化计划。</Text>
          </View>

          <View className='home-page__status'>
            <View className='home-page__status-indicator'>
              <View className='home-page__status-dot' />
            </View>
            <View className='home-page__status-copy'>
              <Text className='home-page__status-label'>{statusLabel}</Text>
              <Text className='home-page__status-message'>{message}</Text>
            </View>
          </View>

          {destination === 'LOGIN' && (
            <Button
              className='home-page__action home-page__action--primary'
              loading={isLoggingIn}
              disabled={isLoggingIn}
              onClick={() => void login()}
            >
              {isLoggingIn ? '正在安全登录' : '微信登录并建立档案'}
            </Button>
          )}

          {destination === 'HOME' && (
            <View className='home-page__actions'>
              {hasActiveWorkout && (
                <Button
                  className='home-page__action home-page__action--primary'
                  onClick={() => void application.navigation.open('WORKOUT_SESSION')}
                >
                  继续本次训练
                </Button>
              )}
              <Button
                className={`home-page__action ${hasActiveWorkout ? 'home-page__action--light' : 'home-page__action--primary'}`}
                onClick={() => void application.navigation.open('PLAN')}
              >
                进入我的计划
              </Button>
              <Button
                className='home-page__action home-page__action--ghost'
                onClick={() => void application.navigation.open('ONBOARDING')}
              >
                重新设置档案
              </Button>
            </View>
          )}
        </View>

        <View className='home-page__capabilities'>
          <View className='home-page__capability'>
            <Text className='home-page__capability-index'>01</Text>
            <Text className='home-page__capability-title'>科学计划</Text>
            <Text className='home-page__capability-description'>适配目标与场地</Text>
          </View>
          <View className='home-page__capability-divider' />
          <View className='home-page__capability'>
            <Text className='home-page__capability-index'>02</Text>
            <Text className='home-page__capability-title'>规则计算</Text>
            <Text className='home-page__capability-description'>关键数字更可靠</Text>
          </View>
          <View className='home-page__capability-divider' />
          <View className='home-page__capability'>
            <Text className='home-page__capability-index'>03</Text>
            <Text className='home-page__capability-title'>长期进化</Text>
            <Text className='home-page__capability-description'>随训练持续调整</Text>
          </View>
        </View>

        <View className='home-page__trust-card'>
          <View className='home-page__trust-heading'>
            <View className='home-page__trust-mark'>
              <View className='home-page__trust-mark-core' />
            </View>
            <View className='home-page__trust-title-group'>
              <Text className='home-page__trust-kicker'>TRAINING STANDARD</Text>
              <Text className='home-page__trust-title'>专业，也有清晰边界</Text>
            </View>
          </View>
          <Text className='home-page__trust-description'>面向已满 18 周岁的成年人，提供一般健身计划，不提供医疗诊断或康复处方。</Text>
          <View className='home-page__rule-note'>
            <View className='home-page__rule-line' />
            <Text className='home-page__rule-text'>重量、组数、次数与进阶结论由确定性规则计算，AI 负责个性化推荐与解释。</Text>
          </View>
        </View>

        {destination !== 'LOADING' && (
          <Button
            className='home-page__privacy-action'
            onClick={() => void application.navigation.open('MY')}
          >
            <View className='home-page__privacy-copy'>
              <Text className='home-page__privacy-title'>我的与隐私</Text>
              <Text className='home-page__privacy-description'>查看个人资料与数据设置</Text>
            </View>
            <Text className='home-page__privacy-arrow'>›</Text>
          </Button>
        )}

        <Text className='home-page__footer'>科学训练 · 稳定进步 · 尊重身体反馈</Text>
      </View>
      {destination === 'HOME' && (
        <MainNavigation
          current='HOME'
          onNavigate={(nextDestination) => void application.navigation.replace(nextDestination)}
        />
      )}
    </View>
  )
}
