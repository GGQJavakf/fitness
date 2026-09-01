import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { AppDestination } from '../../../../application/startup'
import { recordStartupFailure } from '../../../../platform/weapp/startupDiagnostics'
import { useWeappFirstPaint } from '../../../../platform/weapp/useWeappFirstPaint'
import { runSingleFlight } from './loginSingleFlight'
import { loadStartupApplication, type StartupApplication } from './startupApplicationLoader'

import './index.scss'

type StartupFailure = 'NONE' | 'CONNECTION' | 'DEVICE_CONFIGURATION'

const deviceConfigurationMessage = '当前真机包仍指向电脑本机地址，手机无法访问。请重新构建真机包后再打开；无需重复登录。'

function startupStatusLabel(
  failure: StartupFailure,
  destination: AppDestination | 'LOADING',
): string {
  if (failure === 'DEVICE_CONFIGURATION') return '真机包配置错误'
  if (failure === 'CONNECTION') return '会话恢复失败'
  if (destination === 'LOADING') return '正在连接'
  if (destination === 'LOGIN') return '待建立档案'
  return '训练档案已就绪'
}

export default function HomePage() {
  const [destination, setDestination] = useState<AppDestination | 'LOADING'>('LOADING')
  const [message, setMessage] = useState('正在恢复本地会话…')
  const [hasActiveWorkout, setHasActiveWorkout] = useState(false)
  const [isLoggingIn, setIsLoggingIn] = useState(false)
  const [startupFailure, setStartupFailure] = useState<StartupFailure>('NONE')
  const applicationRef = useRef<StartupApplication | null>(null)
  const loginInFlight = useRef(false)
  const startupInFlight = useRef(false)
  const startupRequestId = useRef(0)
  const mounted = useRef(true)

  async function restoreStartup(): Promise<void> {
    if (startupInFlight.current) return
    startupInFlight.current = true
    const requestId = ++startupRequestId.current
    setDestination('LOADING')
    setStartupFailure('NONE')
    setMessage('正在恢复本地会话…')
    try {
      let application = applicationRef.current
      if (!application) {
        try {
          application = await loadStartupApplication()
          applicationRef.current = application
        } catch {
          if (mounted.current && requestId === startupRequestId.current) {
            setDestination('LOGIN')
            setStartupFailure('CONNECTION')
            setMessage('启动模块暂时无法加载，请重新连接；无需重复登录。')
          }
          return
        }
      }
      if (application.startupConfigurationIssue === 'DEVICE_LOOPBACK_API') {
        if (mounted.current && requestId === startupRequestId.current) {
          setDestination('LOGIN')
          setStartupFailure('DEVICE_CONFIGURATION')
          setMessage(deviceConfigurationMessage)
        }
        return
      }
      let next: AppDestination
      try {
        next = await application.startup.start()
      } catch {
        recordStartupFailure('STARTUP_SESSION_RESTORE', 'SESSION_RESTORE')
        if (mounted.current && requestId === startupRequestId.current) {
          setDestination('LOGIN')
          setStartupFailure('CONNECTION')
          setMessage('暂时无法恢复会话，请检查网络后重新连接；无需重复登录。')
        }
        return
      }
      if (!mounted.current || requestId !== startupRequestId.current) return
      setDestination(next)
      if (next === 'HOME') {
        void application.hasActiveWorkout()
          .then((active) => {
            if (mounted.current && requestId === startupRequestId.current) setHasActiveWorkout(active)
          })
          .catch(() => {
            if (mounted.current && requestId === startupRequestId.current) setHasActiveWorkout(false)
          })
      }
      setMessage(next === 'LOGIN'
        ? '使用微信登录后开始 3 分钟建档'
        : '档案已就绪，可以继续生成或查看计划')
    } finally {
      startupInFlight.current = false
    }
  }

  useWeappFirstPaint(() => {
    void restoreStartup()
  })

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      startupRequestId.current += 1
    }
  }, [])

  async function login(): Promise<void> {
    const application = applicationRef.current
    if (!application) {
      await restoreStartup()
      return
    }
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

  const startupFailed = startupFailure !== 'NONE'
  const statusLabel = startupStatusLabel(startupFailure, destination)

  if (destination === 'LOADING') {
    return (
      <View className='home-page screen'>
        <View className='home-page__loading'>
          <Text className='home-page__eyebrow'>AI 科学训练系统</Text>
          <Text className='home-page__loading-title'>正在打开你的训练计划…</Text>
          <Text className='home-page__loading-note'>正在恢复会话与未完成训练，请稍候</Text>
        </View>
      </View>
    )
  }

  return (
    <View className='home-page screen'>
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

          <View className={`home-page__status ${startupFailed ? 'home-page__status--error' : ''}`}>
            <View className='home-page__status-indicator'>
              <View className='home-page__status-dot' />
            </View>
            <View className='home-page__status-copy'>
              <Text className='home-page__status-label'>{statusLabel}</Text>
              <Text className='home-page__status-message'>{message}</Text>
            </View>
          </View>

          {destination === 'LOGIN' && startupFailure === 'CONNECTION' && (
            <Button
              className='home-page__action home-page__action--primary'
              onClick={() => void restoreStartup()}
            >
              重新连接
            </Button>
          )}

          {destination === 'LOGIN' && !startupFailed && (
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
                  onClick={() => void applicationRef.current?.navigation.open('WORKOUT_SESSION')}
                >
                  继续本次训练
                </Button>
              )}
              <Button
                className={`home-page__action ${hasActiveWorkout ? 'home-page__action--light' : 'home-page__action--primary'}`}
                onClick={() => void applicationRef.current?.navigation.open('PLAN')}
              >
                进入我的计划
              </Button>
              <Button
                className='home-page__action home-page__action--ghost'
                onClick={() => void applicationRef.current?.navigation.open('ONBOARDING')}
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
            <Text className='home-page__rule-text'>重量、组数、次数与进阶结论由确定性规则计算；仅在明确启用并授权时使用 AI 个性化能力。</Text>
          </View>
        </View>

        <Button
          className='home-page__privacy-action'
          onClick={() => void applicationRef.current?.navigation.open('MY')}
        >
          <View className='home-page__privacy-copy'>
            <Text className='home-page__privacy-title'>我的与隐私</Text>
            <Text className='home-page__privacy-description'>查看个人资料与数据设置</Text>
          </View>
          <Text className='home-page__privacy-arrow'>›</Text>
        </Button>

        <Text className='home-page__footer'>科学训练 · 稳定进步 · 尊重身体反馈</Text>
      </View>
    </View>
  )
}
