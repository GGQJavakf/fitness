import { Button, Text, View } from '@tarojs/components'
import {
  Component,
  type ComponentType,
  type ReactNode,
  useEffect,
  useState,
} from 'react'

import { recoverWeappHome } from '../../platform/weapp/appRecovery'
import {
  recordStartupFailure,
  STARTUP_BUILD_LABEL,
} from '../../platform/weapp/startupDiagnostics'
import { useWeappFirstPaint } from '../../platform/weapp/useWeappFirstPaint'

import './featurePageLoader.scss'

interface PageModule {
  default: ComponentType
}

export type FeatureLoadFailureCode =
  | 'WL-E01'
  | 'WL-E02'
  | 'WL-E03'
  | 'WL-E04'
  | 'WL-E99'
  | 'WL-R01'
  | `WL-M${number}`

class InvalidFeaturePageModuleError extends Error {
  constructor() {
    super('Loaded feature page module has no component export')
    this.name = 'InvalidFeaturePageModuleError'
  }
}

function FeatureLoaderState(props: {
  failed: boolean
  failureCode?: FeatureLoadFailureCode
  featureName: string
  onRetry: () => void
}) {
  return (
    <View className='feature-loader'>
      <Text className='feature-loader__eyebrow'>AI 科学训练系统</Text>
      <Text className='feature-loader__title'>
        {props.failed ? '功能加载失败' : `正在加载${props.featureName}…`}
      </Text>
      <Text className='feature-loader__message'>
        {props.failed ? '当前功能暂时不可用，可以重试或返回安全首页。' : '页面已经打开，正在准备功能模块。'}
      </Text>
      {props.failed && props.failureCode && (
        <Text className='feature-loader__diagnostic'>
          {`诊断码：${props.failureCode} · ${STARTUP_BUILD_LABEL}`}
        </Text>
      )}
      {props.failed && (
        <View className='feature-loader__actions'>
          <Button className='feature-loader__primary' onClick={props.onRetry}>
            重新加载
          </Button>
          <Button
            className='feature-loader__secondary'
            onClick={() => void recoverWeappHome()}
          >
            返回安全首页
          </Button>
        </View>
      )}
    </View>
  )
}

class FeatureRenderBoundary extends Component<{
  children: ReactNode
  fallback: ReactNode
}, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true }
  }

  componentDidCatch(): void {
    recordStartupFailure('FEATURE_RENDER', 'RENDER')
  }

  render(): ReactNode {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}

export function createFeaturePageLoader(
  featureName: string,
  loadPage: () => Promise<PageModule>,
): ComponentType {
  return function FeaturePageLoader() {
    const [Page, setPage] = useState<ComponentType | null>(null)
    const [failed, setFailed] = useState(false)
    const [failureCode, setFailureCode] = useState<FeatureLoadFailureCode | null>(null)
    const [attempt, setAttempt] = useState(0)
    const [firstPaintComplete, setFirstPaintComplete] = useState(false)

    useWeappFirstPaint(() => setFirstPaintComplete(true))

    useEffect(() => {
      if (!firstPaintComplete) return
      let active = true
      setFailed(false)
      setFailureCode(null)
      void Promise.resolve()
        .then(loadPage)
        .then((module) => {
          if (!module || typeof module.default !== 'function') {
            throw new InvalidFeaturePageModuleError()
          }
          if (active) setPage(() => module.default)
        })
        .catch((error: unknown) => {
          if (active) {
            recordStartupFailure('FEATURE_MODULE_LOAD', 'MODULE_LOAD')
            setFailureCode(classifyFeatureLoadFailure(error))
            setFailed(true)
          }
        })
      return () => {
        active = false
      }
    }, [attempt, firstPaintComplete])

    const retry = () => {
      setPage(null)
      setAttempt((value) => value + 1)
    }
    const failure = (
      <FeatureLoaderState
        failed
        failureCode='WL-R01'
        featureName={featureName}
        onRetry={retry}
      />
    )

    if (Page) {
      return (
        <FeatureRenderBoundary key={attempt} fallback={failure}>
          <Page />
        </FeatureRenderBoundary>
      )
    }

    return (
      <FeatureLoaderState
        failed={failed}
        failureCode={failureCode ?? undefined}
        featureName={featureName}
        onRetry={retry}
      />
    )
  }
}

export function classifyFeatureLoadFailure(error: unknown): FeatureLoadFailureCode {
  const candidates: unknown[] = [error]
  const seen = new Set<unknown>()
  for (let index = 0; index < candidates.length && index < 8; index += 1) {
    const candidate = candidates[index]
    if (seen.has(candidate)) continue
    seen.add(candidate)
    if (!candidate || typeof candidate !== 'object') continue
    const record = candidate as Record<string, unknown>
    const name = typeof record.name === 'string' ? record.name : ''
    const message = typeof record.message === 'string' ? record.message : ''
    const type = typeof record.type === 'string' ? record.type : ''
    const moduleId = typeof record.moduleId === 'string' ? record.moduleId : ''
    if (name === 'InvalidFeaturePageModuleError') return 'WL-E04'
    if (type === 'fitness-module-evaluation' && /^\d{1,8}$/.test(moduleId)) {
      return `WL-M${moduleId}` as FeatureLoadFailureCode
    }
    if (type === 'fitness-envelope-invalid') return 'WL-E03'
    if (type === 'fitness-native-require') return 'WL-E02'
    if (name === 'ChunkLoadError' && type === 'missing') return 'WL-E01'
    if (message.includes('Invalid WeChat async chunk registration envelope')) return 'WL-E03'
    if (name === 'ChunkLoadError') return 'WL-E02'
    candidates.push(record.cause, record.error)
  }
  return 'WL-E99'
}
