import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { LocalUserDataCleanupError } from '../src/application/localPrivacyLifecycle'

const application = vi.hoisted(() => ({
  account: {
    logout: vi.fn(),
    switchAccount: vi.fn(),
  },
  privacy: {
    exportData: vi.fn(),
    requestDeletion: vi.fn(),
    getDeletionStatus: vi.fn(),
  },
  navigation: {
    open: vi.fn(),
    replace: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Input: 'input',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/accountCompositionRoot', () => ({
  getAccountApplication: () => application,
}))

vi.mock('../src/presentation/components/main-navigation', () => ({
  default: 'main-navigation',
}))

const { default: MyPage } = await import('../src/presentation/pages/my')

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('My page account lifecycle actions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.privacy.exportData.mockResolvedValue({
      resources: [],
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      resourceSummary: '',
      retentionNotice: '不含安全审计记录',
    })
    application.account.logout.mockResolvedValue({ remoteLogoutSucceeded: true })
    application.account.switchAccount.mockResolvedValue({
      remoteLogoutSucceeded: true,
      destination: 'PLAN',
    })
  })

  it('connects explicit logout to the privacy-safe account lifecycle', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(MyPage))
    })
    if (!renderer) throw new Error('My page did not render')

    await act(async () => {
      button(renderer!, '退出登录').props.onClick()
      await flushPage()
    })

    expect(application.account.logout).toHaveBeenCalledOnce()
  })

  it('connects account switch and reports a local cleanup failure without starting another action', async () => {
    application.account.switchAccount.mockRejectedValueOnce(new LocalUserDataCleanupError())
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(MyPage))
    })
    if (!renderer) throw new Error('My page did not render')

    await act(async () => {
      button(renderer!, '切换账号').props.onClick()
      await flushPage()
    })

    expect(application.account.switchAccount).toHaveBeenCalledOnce()
    expect(renderer.root.findAllByType('text').map((node) => node.props.children).join(''))
      .toContain('本机数据未能完全清理')
  })

  it('opens the post-onboarding exclusion editor from the live preference action', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(MyPage))
    })
    if (!renderer) throw new Error('My page did not render')

    await act(async () => {
      button(renderer!, '设置不推荐动作').props.onClick()
      await flushPage()
    })

    expect(application.navigation.open).toHaveBeenCalledWith('EXERCISE_PREFERENCES')
  })

  it('renders each exported record summary instead of only showing aggregate counts', async () => {
    application.privacy.exportData.mockResolvedValue({
      resources: [{
        category: 'WORKOUTS',
        recordCount: 2,
        records: [
          { id: 'session-1', summary: '2026-08-10 上肢训练，完成 15 组' },
          { id: 'session-2', summary: '2026-08-12 下肢训练，完成 12 组' },
        ],
      }],
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      resourceSummary: '训练记录 2 项',
      retentionNotice: '不含安全审计记录',
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(MyPage))
    })
    if (!renderer) throw new Error('My page did not render')

    await act(async () => {
      button(renderer!, '生成数据副本').props.onClick()
      await flushPage()
    })

    expect(application.privacy.exportData).toHaveBeenCalledOnce()
    const rendered = JSON.stringify(renderer.toJSON())
    const category = renderer.root.find(
      (node) => node.props.className === 'privacy-export__category',
    )
    expect(category.props.children.join('')).toBe('训练记录 · 2 项')
    expect(rendered).toContain('2026-08-10 上肢训练，完成 15 组')
    expect(rendered).toContain('2026-08-12 下肢训练，完成 12 组')
    act(() => renderer?.unmount())
  })

  it('allows only one privacy-sensitive account action at a time', async () => {
    const logout = deferred<{ remoteLogoutSucceeded: boolean }>()
    application.account.logout.mockReturnValueOnce(logout.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(MyPage))
    })
    if (!renderer) throw new Error('My page did not render')

    const logoutButton = button(renderer, '退出登录')
    const switchButton = button(renderer, '切换账号')
    act(() => {
      logoutButton.props.onClick()
      logoutButton.props.onClick()
      switchButton.props.onClick()
    })

    expect(application.account.logout).toHaveBeenCalledOnce()
    expect(application.account.switchAccount).not.toHaveBeenCalled()
    await act(async () => {
      logout.resolve({ remoteLogoutSucceeded: true })
      await flushPage()
    })
  })
})
