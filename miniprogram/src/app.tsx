import { Button, Text, View } from '@tarojs/components'
import {
  Component,
  type PropsWithChildren,
  type ReactNode,
} from 'react'

import { recoverWeappHome } from './platform/weapp/appRecovery'
import { initializeSharedPlatformKernel } from './platform/weapp/sharedPlatformKernel'
import { recordStartupFailure } from './platform/weapp/startupDiagnostics'

import './app.scss'

// Keep the user-scope and refresh coordinators in the main package so ordinary
// subpackages share one lightweight platform kernel without pulling in business code.
initializeSharedPlatformKernel()

interface AppErrorBoundaryState {
  failed: boolean
  recovering: boolean
}

class AppErrorBoundary extends Component<PropsWithChildren, AppErrorBoundaryState> {
  readonly state: AppErrorBoundaryState = {
    failed: false,
    recovering: false,
  }

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return {
      failed: true,
      recovering: false,
    }
  }

  componentDidCatch(): void {
    recordStartupFailure('APP_RENDER', 'RENDER')
  }

  private returnHome = (): void => {
    if (this.state.recovering) return
    this.setState({ recovering: true })

    let recovery: Promise<unknown>
    try {
      recovery = recoverWeappHome()
    } catch {
      this.setState({ failed: true, recovering: false })
      return
    }

    void recovery.then(
      () => this.setState({ failed: false, recovering: false }),
      () => this.setState({ failed: true, recovering: false }),
    )
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children

    return (
      <View className='screen'>
        <View className='surface-card'>
          <Text className='eyebrow'>FITNESS RECOVERY</Text>
          <Text className='title'>页面加载失败</Text>
          <Text className='subtitle'>
            应用已停止当前页面，避免继续显示空白。返回首页不会清除本地训练记录。
          </Text>
          <Button
            className='primary-action'
            loading={this.state.recovering}
            disabled={this.state.recovering}
            onClick={this.returnHome}
          >
            {this.state.recovering ? '正在返回首页' : '返回首页'}
          </Button>
        </View>
      </View>
    )
  }
}

export default function App({ children }: PropsWithChildren) {
  return <AppErrorBoundary>{children}</AppErrorBoundary>
}
