import { Button, Input, Text, View } from '@tarojs/components'
import { useState } from 'react'

import { privacyActionErrorMessage } from '../../../application/privacy'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'

import './index.scss'

const application = getWeappApplication()

export default function MyPage() {
  const [confirmation, setConfirmation] = useState('')
  const [message, setMessage] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)

  async function exportData(): Promise<void> {
    setBusy(true)
    setMessage('')
    try {
      const result = await application.privacy.exportData()
      setMessage(`导出任务 ${result.id} 已就绪：${result.resourceSummary || '暂无记录'}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '数据导出失败，请稍后重试'))
    } finally {
      setBusy(false)
    }
  }

  async function requestDeletion(): Promise<void> {
    setBusy(true)
    setMessage('')
    try {
      const result = await application.privacy.requestDeletion(confirmation)
      setRequestId(result.id)
      setMessage(`删除申请${result.statusLabel}，范围：${result.scopeLabel}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '删除申请提交失败，请稍后重试'))
    } finally {
      setBusy(false)
    }
  }

  async function refreshStatus(): Promise<void> {
    if (!requestId) return
    try {
      const result = await application.privacy.getDeletionStatus(requestId)
      setMessage(`当前申请状态：${result.statusLabel}。${result.retentionNotice}`)
    } catch (error: unknown) {
      setMessage(privacyActionErrorMessage(error, '暂时无法读取申请状态'))
    }
  }

  return (
    <View className='screen screen--with-nav my-page'>
      <View className='card'>
        <Text className='title'>我的</Text>
        <Text className='subtitle'>P0 仅面向已满 18 周岁的成年人，单位固定为 KG。</Text>
      </View>

      <View className='card settings-list'>
        <View className='settings-row'><Text>训练档案</Text><Text className='subtitle'>可在建档流程更新</Text></View>
        <View className='settings-row'><Text>可用器械</Text><Text className='subtitle'>按场地配置</Text></View>
        <View className='settings-row'><Text>重量单位</Text><Text className='subtitle'>KG</Text></View>
        <View className='settings-row'><Text>休息设置</Text><Text className='subtitle'>随计划规则生成</Text></View>
      </View>

      <View className='card'>
        <Text className='section-title'>隐私与数据</Text>
        <Text className='subtitle'>导出和删除申请均会重新进行微信身份验证，并记录不含凭据的安全审计。</Text>
        <Button className='secondary-action' disabled={busy} onClick={() => void exportData()}>生成数据导出</Button>
      </View>

      <View className='card danger-zone'>
        <Text className='section-title'>申请删除账户数据</Text>
        <Text className='subtitle'>请输入 DELETE 二次确认。安全审计和依法必须保留的数据与普通业务数据分离，不会被静默丢弃。</Text>
        <Input
          className='confirmation-input'
          value={confirmation}
          maxlength={6}
          placeholder='输入 DELETE'
          onInput={(event) => setConfirmation(event.detail.value)}
        />
        <Button className='danger-action' disabled={busy || confirmation !== 'DELETE'} onClick={() => void requestDeletion()}>提交删除申请</Button>
        {requestId && (
          <Button className='secondary-action' onClick={() => void refreshStatus()}>刷新申请状态</Button>
        )}
      </View>

      {message && <View className='info-box'><Text>{message}</Text></View>}
      <MainNavigation current='MY' onNavigate={(destination) => void application.navigation.replace(destination)} />
    </View>
  )
}
