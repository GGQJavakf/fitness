import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const application = vi.hoisted(() => ({
  startup: {
    start: vi.fn(),
    login: vi.fn(),
  },
  hasActiveWorkout: vi.fn(),
  navigation: {
    open: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => application,
}))

const { default: HomePage } = await import('../src/presentation/pages/home')

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('home page runtime behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders workout recovery and routes it with the workout-session identifier', async () => {
    application.startup.start.mockResolvedValue('HOME')
    application.hasActiveWorkout.mockResolvedValue(true)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(HomePage))
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

    await act(async () => {
      renderer = TestRenderer.create(createElement(HomePage))
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

    await act(async () => {
      renderer = TestRenderer.create(createElement(HomePage))
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
  })
})
