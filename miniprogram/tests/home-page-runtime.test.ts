import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const application = vi.hoisted(() => ({
  startupConfigurationIssue: undefined as 'DEVICE_LOOPBACK_API' | undefined,
  startup: {
    start: vi.fn(),
    login: vi.fn(),
  },
  hasActiveWorkout: vi.fn(),
  navigation: {
    open: vi.fn(),
  },
}))

const startupApplicationLoader = vi.hoisted(() => ({
  loadStartupApplication: vi.fn(),
}))

const diagnostics = vi.hoisted(() => ({
  recordStartupFailure: vi.fn(),
}))

const pageLifecycle = vi.hoisted(() => ({
  readyCallbacks: [] as Array<() => void>,
}))

const taroRuntime = vi.hoisted(() => ({
  redirectTo: vi.fn(),
  nextTick: vi.fn((callback: () => void) => callback()),
  useReady: vi.fn((callback: () => void) => {
    pageLifecycle.readyCallbacks.push(callback)
  }),
}))

const wechatRuntime = vi.hoisted(() => ({
  loadSubpackage: vi.fn(),
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('@tarojs/taro', () => ({
  default: taroRuntime,
}))

vi.mock('../src/subpackages/startup/pages/home/startupApplicationLoader', () => ({
  loadStartupApplication: startupApplicationLoader.loadStartupApplication,
}))

vi.mock('../src/platform/weapp/startupDiagnostics', () => ({
  recordStartupFailure: diagnostics.recordStartupFailure,
}))

const { default: BootstrapPage } = await import('../src/presentation/pages/home')
const { default: HomePage } = await import('../src/subpackages/startup/pages/home')

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function firePageReady(): void {
  for (const callback of pageLifecycle.readyCallbacks.splice(0)) callback()
}

describe('home page runtime behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pageLifecycle.readyCallbacks.length = 0
    application.startupConfigurationIssue = undefined
    startupApplicationLoader.loadStartupApplication.mockReset().mockResolvedValue(application)
    wechatRuntime.loadSubpackage.mockReset().mockImplementation((options: { success: () => void }) => {
      options.success()
    })
    taroRuntime.redirectTo.mockReset().mockResolvedValue(undefined)
    vi.stubGlobal('wx', wechatRuntime)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps a visible retryable bootstrap when the startup subpackage cannot load', async () => {
    wechatRuntime.loadSubpackage.mockImplementation((options: { fail: (error: Error) => void }) => {
      options.fail(new Error('startup root failed to load'))
    })
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(BootstrapPage))
    })
    if (!renderer) throw new Error('bootstrap page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('AI 科学训练系统')
    expect(wechatRuntime.loadSubpackage).not.toHaveBeenCalled()
    expect(taroRuntime.redirectTo).not.toHaveBeenCalled()
    expect(startupApplicationLoader.loadStartupApplication).not.toHaveBeenCalled()

    await act(async () => {
      firePageReady()
      await flushPage()
    })

    const failed = JSON.stringify(renderer.toJSON())
    expect(failed).toContain('启动模块加载失败，请检查网络后重试')
    expect(failed).toContain('重新加载')
    expect(startupApplicationLoader.loadStartupApplication).not.toHaveBeenCalled()
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'BOOTSTRAP_SUBPACKAGE_LOAD',
      'SUBPACKAGE_LOAD',
    )
  })

  it('records a sanitized bootstrap redirect failure after the first paint', async () => {
    taroRuntime.redirectTo.mockRejectedValueOnce(new Error('redirect token=must-not-leak'))
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(BootstrapPage))
    })
    if (!renderer) throw new Error('bootstrap page did not render')

    expect(wechatRuntime.loadSubpackage).not.toHaveBeenCalled()
    expect(taroRuntime.redirectTo).not.toHaveBeenCalled()
    expect(diagnostics.recordStartupFailure).not.toHaveBeenCalled()

    await act(async () => {
      firePageReady()
      await flushPage()
    })

    expect(JSON.stringify(renderer.toJSON())).toContain('启动模块加载失败')
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'BOOTSTRAP_REDIRECT',
      'NAVIGATION',
    )
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledTimes(1)
  })

  it('keeps the first paint visible without duplicating the loader-owned failure diagnostic', async () => {
    let rejectRootLoad: ((error: Error) => void) | undefined
    startupApplicationLoader.loadStartupApplication.mockReturnValue(new Promise((_resolve, reject) => {
      rejectRootLoad = reject
    }))
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(HomePage))
    })
    if (!renderer) throw new Error('home page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('正在打开你的训练计划')
    expect(startupApplicationLoader.loadStartupApplication).not.toHaveBeenCalled()
    expect(application.startup.start).not.toHaveBeenCalled()
    expect(taroRuntime.redirectTo).not.toHaveBeenCalled()

    await act(async () => {
      firePageReady()
      rejectRootLoad?.(new Error('startup composition root getter failed'))
      await flushPage()
    })

    const failed = JSON.stringify(renderer.toJSON())
    expect(failed).toContain('会话恢复失败')
    expect(failed).toContain('重新连接')
    expect(diagnostics.recordStartupFailure).not.toHaveBeenCalled()
  })

  it('shows a build configuration error immediately instead of spinning on a physical-device loopback build', async () => {
    application.startupConfigurationIssue = 'DEVICE_LOOPBACK_API'
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(HomePage))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('home page did not render')

    const failed = JSON.stringify(renderer.toJSON())
    expect(failed).toContain('真机包配置错误')
    expect(failed).toContain('重新构建真机包')
    expect(failed).not.toContain('正在打开你的训练计划')
    expect(application.startup.start).not.toHaveBeenCalled()
  })

  it('renders workout recovery and routes it with the workout-session identifier', async () => {
    application.startup.start.mockResolvedValue('HOME')
    application.hasActiveWorkout.mockResolvedValue(true)
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(HomePage))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('home page did not render')

    await act(async () => {
      await flushPage()
    })
    button(renderer, '继续本次训练').props.onClick()

    expect(application.navigation.open).toHaveBeenCalledWith('WORKOUT_SESSION')
  })

  it('keeps a visible disabled login action while single-flight login is pending', async () => {
    application.startup.start.mockResolvedValue('LOGIN')
    let finishLogin: ((destination: string) => void) | undefined
    application.startup.login.mockReturnValue(new Promise((resolve) => {
      finishLogin = resolve
    }))
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(HomePage))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('home page did not render')

    act(() => {
      button(renderer!, '微信登录并建立档案').props.onClick()
      button(renderer!, '微信登录并建立档案').props.onClick()
    })

    const pending = button(renderer, '正在安全登录')
    expect(pending.props.loading).toBe(true)
    expect(pending.props.disabled).toBe(true)
    expect(application.startup.login).toHaveBeenCalledOnce()

    await act(async () => {
      finishLogin?.('ONBOARDING')
      await flushPage()
    })
  })

  it('does not misrepresent a startup network failure as a logged-out state', async () => {
    application.startup.start
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce('LOGIN')
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(HomePage))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('home page did not render')

    const failed = JSON.stringify(renderer.toJSON())
    expect(failed).toContain('会话恢复失败')
    expect(failed).toContain('无需重复登录')
    expect(failed).not.toContain('微信登录并建立档案')

    await act(async () => {
      button(renderer!, '重新连接').props.onClick()
      await flushPage()
    })

    expect(application.startup.start).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('微信登录并建立档案')
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'STARTUP_SESSION_RESTORE',
      'SESSION_RESTORE',
    )
  })
})
