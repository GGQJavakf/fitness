import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import {
  privacyActionErrorMessage,
  privacyCategoryLabel,
  type PrivacyExportResource,
} from '../../../application/privacy'
import { LocalUserDataCleanupError } from '../../../application/localPrivacyLifecycle'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'

import './index.scss'

const application = getWeappApplication()

export default function MyPage() {
  const [confirmation, setConfirmation] = useState('')
  const [message, setMessage] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)
  const [showDeletion, setShowDeletion] = useState(false)
  const [exportedResources, setExportedResources] = useState<readonly PrivacyExportResource[]>([])
  const [exportExpiresAt, setExportExpiresAt] = useState('')
  const actionInFlight = useRef(false)

  useEffect(() => {
    if (!exportExpiresAt) return undefined
    const delay = Math.max(0, Date.parse(exportExpiresAt) - Date.now())
    const timer = setTimeout(() => {
      setExportedResources([])
      setExportExpiresAt('')
    }, delay)
    return () => clearTimeout(timer)
  }, [exportExpiresAt])

  async function exportData(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    try {
      const result = await application.privacy.exportData()
      setExportedResources(result.resources)
      setExportExpiresAt(result.expiresAt)
      setMessage(`数据副本已准备好：${result.resourceSummary || '暂无训练记录'}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '数据导出失败，请稍后重试'))
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function requestDeletion(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    try {
      const result = await application.privacy.requestDeletion(confirmation)
      setRequestId(result.id)
      setMessage(`删除申请${result.statusLabel}，范围：${result.scopeLabel}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '删除申请提交失败，请稍后重试'))
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function refreshStatus(): Promise<void> {
    if (!requestId || actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    try {
      const result = await application.privacy.getDeletionStatus(requestId)
      setMessage(`当前申请状态：${result.statusLabel}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '暂时无法读取申请状态'))
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function logout(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    setExportedResources([])
    setExportExpiresAt('')
    try {
      await application.account.logout()
    } catch (error: unknown) {
      setMessage(error instanceof LocalUserDataCleanupError
        ? '本机数据未能完全清理，请重试退出登录后再使用其他账号'
        : '退出登录未完成，请稍后重试')
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function switchAccount(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    setExportedResources([])
    setExportExpiresAt('')
    try {
      await application.account.switchAccount()
    } catch (error: unknown) {
      setMessage(error instanceof LocalUserDataCleanupError
        ? '本机数据未能完全清理，已停止切换账号，请重试'
        : '旧账号本机数据已清理，但新账号登录失败，请重试')
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  return (
    <View className='screen screen--with-nav my-page'>
      <View className='page-hero profile-hero'>
        <Text className='page-hero__eyebrow'>YOUR TRAINING PROFILE</Text>
        <Text className='page-hero__title'>我的训练档案</Text>
        <Text className='page-hero__description'>目标、时间和训练条件共同决定你的科学计划。</Text>
        <View className='profile-hero__status'>
          <View className='profile-hero__status-dot' />
          <Text>档案已建立</Text>
        </View>
      </View>

      <View className='surface-card profile-overview'>
        <View className='profile-overview__item'>
          <Text className='profile-overview__label'>适用人群</Text>
          <Text className='profile-overview__value'>成年人</Text>
        </View>
        <View className='profile-overview__item'>
          <Text className='profile-overview__label'>重量单位</Text>
          <Text className='profile-overview__value data-number'>KG</Text>
        </View>
        <View className='profile-overview__item'>
          <Text className='profile-overview__label'>计划方式</Text>
          <Text className='profile-overview__value'>科学推荐</Text>
        </View>
      </View>

      <View className='section-heading'>
        <Text className='section-heading__title'>训练偏好</Text>
        <Text className='section-heading__meta'>影响后续推荐</Text>
      </View>
      <View className='surface-card settings-list'>
        <View className='settings-row'>
          <View className='settings-row__mark data-number'>01</View>
          <View className='settings-row__copy'><Text className='settings-row__title'>训练目标与经验</Text><Text className='settings-row__description'>用于选择适合的训练节奏</Text></View>
        </View>
        <View className='settings-row'>
          <View className='settings-row__mark data-number'>02</View>
          <View className='settings-row__copy'><Text className='settings-row__title'>场地与器械</Text><Text className='settings-row__description'>只推荐当前条件可完成的动作</Text></View>
        </View>
        <View className='settings-row'>
          <View className='settings-row__mark data-number'>03</View>
          <View className='settings-row__copy'><Text className='settings-row__title'>频率与训练时长</Text><Text className='settings-row__description'>控制每周安排和单次训练量</Text></View>
        </View>
        <Button className='secondary-action settings-edit' onClick={() => void application.navigation.open('ONBOARDING')}>重新设置训练档案</Button>
        <Button className='secondary-action settings-edit' onClick={() => void application.navigation.open('EXERCISE_PREFERENCES')}>设置不推荐动作</Button>
      </View>

      <View className='section-heading'>
        <Text className='section-heading__title'>隐私与数据</Text>
        <Text className='section-heading__meta'>由你掌控</Text>
      </View>
      <View className='surface-card privacy-card'>
        <View className='privacy-card__heading'>
          <View className='privacy-card__mark'>
            <View className='privacy-card__mark-core' />
          </View>
          <View>
            <Text className='privacy-card__title'>获取我的数据副本</Text>
            <Text className='privacy-card__description'>导出训练档案、计划和训练记录。</Text>
          </View>
        </View>
        <Button className='secondary-action' loading={busy} disabled={busy} onClick={() => void exportData()}>生成数据副本</Button>
        {exportedResources.length > 0 && (
          <View className='privacy-export'>
            {exportedResources.map((resource) => (
              <View className='privacy-export__group' key={resource.category}>
                <Text className='privacy-export__category'>{privacyCategoryLabel(resource.category)} · {resource.recordCount} 项</Text>
                {resource.records.map((record) => (
                  <View className='privacy-export__record' key={record.id}>
                    <Text>{record.summary}</Text>
                  </View>
                ))}
              </View>
            ))}
            <Text className='privacy-export__expiry'>数据仅在本页临时展示，到期或离开页面后请重新验证身份生成。</Text>
          </View>
        )}
      </View>

      <View className='profile-boundary'>
        <Text className='profile-boundary__title'>健康与服务边界</Text>
        <Text className='profile-boundary__description'>提供一般健身训练建议，不替代医疗诊断、治疗或康复服务。</Text>
      </View>

      <View className='surface-card account-actions'>
        <Text className='section-title'>账号</Text>
        <Text className='subtitle'>退出或切换账号时，会先清理本机中的会话、未完成训练与同步队列。</Text>
        <View className='account-actions__buttons'>
          <Button className='secondary-action' disabled={busy} onClick={() => void logout()}>退出登录</Button>
          <Button className='secondary-action' disabled={busy} onClick={() => void switchAccount()}>切换账号</Button>
        </View>
      </View>

      <Button className='deletion-disclosure' onClick={() => setShowDeletion((value) => !value)}>
        <Text>{showDeletion ? '收起账户删除选项' : '账户与数据删除'}</Text>
        <Text className='deletion-disclosure__indicator'>{showDeletion ? '−' : '+'}</Text>
      </Button>

      {showDeletion && (
        <View className='surface-card danger-zone'>
          <Text className='section-title'>申请删除账户数据</Text>
          <Text className='subtitle'>这是不可逆操作。请输入 DELETE 确认，申请提交后可在这里查看处理状态。</Text>
          <Input
            className='confirmation-input'
            value={confirmation}
            maxlength={6}
            placeholder='输入 DELETE'
            onInput={(event) => setConfirmation(event.detail.value)}
          />
          <Button className='danger-action' disabled={busy || confirmation !== 'DELETE'} onClick={() => void requestDeletion()}>提交删除申请</Button>
          {requestId && <Button className='secondary-action' onClick={() => void refreshStatus()}>查看申请状态</Button>}
        </View>
      )}

      {message && <View className='profile-message'><Text>{message}</Text></View>}
      <MainNavigation current='MY' onNavigate={(destination) => void application.navigation.replace(destination)} />
    </View>
  )
}
