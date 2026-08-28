import { Component, createElement, type ReactNode } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const reLaunch = vi.hoisted(() => vi.fn())

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('@tarojs/taro', () => ({
  default: {
    reLaunch,
  },
}))

const { default: App } = await import('../src/app')

let shouldFail = true

class FlakyPage extends Component {
  render(): ReactNode {
    if (shouldFail) throw new Error('render failed')
    return createElement('view', null, 'page recovered')
  }
}

function recoveryButton(renderer: ReactTestRenderer) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === '返回首页',
  )
}

async function flushBoundary(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('application error boundary', () => {
  let consoleError: ReturnType<typeof vi.spyOn> | undefined

  beforeEach(() => {
    shouldFail = true
    vi.clearAllMocks()
    reLaunch.mockResolvedValue({})
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    consoleError?.mockRestore()
  })

  it('replaces a render-time white screen with a recoverable page', async () => {
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(
        createElement(App, null, createElement(FlakyPage)),
      )
    })
    if (!renderer) throw new Error('application did not render')

    const failed = JSON.stringify(renderer.toJSON())
    expect(failed).toContain('页面加载失败')
    expect(failed).toContain('不会清除本地训练记录')

    shouldFail = false
    await act(async () => {
      recoveryButton(renderer!).props.onClick()
      await flushBoundary()
    })

    expect(reLaunch).toHaveBeenCalledWith({
      url: '/presentation/pages/home/index',
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('page recovered')
  })
})
