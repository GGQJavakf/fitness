import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createElement, type ComponentType } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import ts from 'typescript'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const pageLifecycle = vi.hoisted(() => ({
  readyCallbacks: [] as Array<() => void>,
}))

const diagnostics = vi.hoisted(() => ({
  recordStartupFailure: vi.fn(),
}))

const taro = vi.hoisted(() => ({
  reLaunch: vi.fn(),
  nextTick: vi.fn((callback: () => void) => callback()),
  useReady: vi.fn((callback: () => void) => {
    pageLifecycle.readyCallbacks.push(callback)
  }),
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

vi.mock('../src/platform/weapp/startupDiagnostics', () => ({
  recordStartupFailure: diagnostics.recordStartupFailure,
  STARTUP_BUILD_LABEL: 'R5',
}))

const { classifyFeatureLoadFailure, createFeaturePageLoader } = await import(
  '../src/subpackages/shared/createFeaturePageLoader'
)

const projectRoot = resolve(import.meta.dirname, '..')

interface PageModule {
  default: ComponentType
}

function deferred<T>() {
  let resolvePromise!: (value: T) => void
  let rejectPromise!: (error: Error) => void
  const promise = new Promise<T>((resolveValue, rejectValue) => {
    resolvePromise = resolveValue
    rejectPromise = rejectValue
  })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}

function renderedText(renderer: ReactTestRenderer): string {
  return JSON.stringify(renderer.toJSON())
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolveValue) => setTimeout(resolveValue, 0))
}

function firePageReady(): void {
  for (const callback of pageLifecycle.readyCallbacks.splice(0)) callback()
}

function ReadyPage() {
  readyPageRender()
  return createElement('view', { className: 'ready-page' }, '功能已就绪')
}

const readyPageRender = vi.fn()

function propertyName(property: ts.ObjectLiteralElementLike): string | undefined {
  const name = property.name
  return name && (ts.isIdentifier(name) || ts.isStringLiteral(name)) ? name.text : undefined
}

function objectProperty(object: ts.ObjectLiteralExpression, name: string): ts.PropertyAssignment | undefined {
  return object.properties.find((property): property is ts.PropertyAssignment => (
    ts.isPropertyAssignment(property) && propertyName(property) === name
  ))
}

function registeredSubpackages(): ReadonlyMap<string, readonly string[]> {
  const configPath = resolve(projectRoot, 'src/app.config.ts')
  const source = ts.createSourceFile(
    configPath,
    readFileSync(configPath, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  let config: ts.ObjectLiteralExpression | undefined
  function visit(node: ts.Node): void {
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && node.expression.text === 'defineAppConfig'
      && node.arguments[0]
      && ts.isObjectLiteralExpression(node.arguments[0])
    ) {
      config = node.arguments[0]
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  if (!config) throw new Error('defineAppConfig object was not found')

  const property = objectProperty(config, 'subPackages')
  if (!property || !ts.isArrayLiteralExpression(property.initializer)) {
    throw new Error('subPackages array was not found')
  }

  const result = new Map<string, readonly string[]>()
  for (const element of property.initializer.elements) {
    if (!ts.isObjectLiteralExpression(element)) continue
    const root = objectProperty(element, 'root')?.initializer
    const pages = objectProperty(element, 'pages')?.initializer
    if (!root || !ts.isStringLiteral(root) || !pages || !ts.isArrayLiteralExpression(pages)) continue
    result.set(
      root.text,
      pages.elements.filter(ts.isStringLiteral).map((page) => page.text),
    )
  }
  return result
}

describe('feature page loader runtime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pageLifecycle.readyCallbacks.length = 0
    taro.reLaunch.mockResolvedValue(undefined)
  })

  it('paints a visible page before the dynamic import completes', async () => {
    const pending = deferred<PageModule>()
    const loadPage = vi.fn(() => pending.promise)
    const Loader = createFeaturePageLoader('训练计划', loadPage)
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(Loader))
    })
    if (!renderer) throw new Error('feature loader did not render')

    const firstPaint = renderedText(renderer)
    expect(firstPaint).toContain('AI 科学训练系统')
    expect(firstPaint).toContain('正在加载训练计划')
    expect(firstPaint).toContain('页面已经打开')
    expect(loadPage).not.toHaveBeenCalled()
    expect(readyPageRender).not.toHaveBeenCalled()
    expect(taro.reLaunch).not.toHaveBeenCalled()
    expect(diagnostics.recordStartupFailure).not.toHaveBeenCalled()

    await act(async () => {
      await flushPage()
    })
    expect(loadPage).not.toHaveBeenCalled()
    expect(readyPageRender).not.toHaveBeenCalled()

    await act(async () => {
      firePageReady()
      await flushPage()
    })
    expect(loadPage).toHaveBeenCalledOnce()
    expect(renderedText(renderer)).toContain('正在加载训练计划')
    expect(readyPageRender).not.toHaveBeenCalled()

    await act(async () => {
      pending.resolve({ default: ReadyPage })
      await flushPage()
    })
    expect(renderedText(renderer)).toContain('功能已就绪')
    expect(readyPageRender).toHaveBeenCalledOnce()
  })

  it('shows an actionable failure surface when the dynamic import rejects', async () => {
    const Loader = createFeaturePageLoader(
      '训练进展',
      vi.fn().mockRejectedValue(new Error('chunk unavailable')),
    )
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(Loader))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('feature loader did not render')

    const failed = renderedText(renderer)
    expect(failed).toContain('功能加载失败')
    expect(failed).toContain('当前功能暂时不可用')
    expect(failed).toContain('重新加载')
    expect(failed).toContain('返回安全首页')
    expect(failed).toContain('诊断码：WL-E99 · R5')
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'FEATURE_MODULE_LOAD',
      'MODULE_LOAD',
    )
  })

  it('retries a failed import and renders the recovered feature page', async () => {
    const loadPage = vi.fn()
      .mockRejectedValueOnce(new Error('first chunk request failed'))
      .mockResolvedValueOnce({ default: ReadyPage })
    const Loader = createFeaturePageLoader('账户功能', loadPage)
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(Loader))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('feature loader did not render')
    expect(renderedText(renderer)).toContain('功能加载失败')

    await act(async () => {
      button(renderer!, '重新加载').props.onClick()
      await flushPage()
    })

    expect(loadPage).toHaveBeenCalledTimes(2)
    expect(renderedText(renderer)).toContain('功能已就绪')
  })

  it('shows a recoverable surface when the loaded feature throws during render', async () => {
    let shouldFail = true
    const renderFeature = vi.fn(() => {
      if (shouldFail) throw new Error('render stack with token=must-not-leak')
      return createElement('view', null, '渲染已恢复')
    })
    const loadPage = vi.fn().mockResolvedValue({ default: renderFeature })
    const Loader = createFeaturePageLoader('训练记录', loadPage)
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    let renderer: ReactTestRenderer | undefined

    try {
      act(() => {
        renderer = TestRenderer.create(createElement(Loader))
      })
      if (!renderer) throw new Error('feature loader did not render')
      expect(loadPage).not.toHaveBeenCalled()
      expect(renderFeature).not.toHaveBeenCalled()

      await act(async () => {
        firePageReady()
        await flushPage()
      })

      expect(renderedText(renderer)).toContain('功能加载失败')
      expect(renderedText(renderer)).toContain('重新加载')
      expect(renderedText(renderer)).toContain('诊断码：WL-R01 · R5')
      expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
        'FEATURE_RENDER',
        'RENDER',
      )

      shouldFail = false
      await act(async () => {
        button(renderer!, '重新加载').props.onClick()
        await flushPage()
      })

      expect(loadPage).toHaveBeenCalledTimes(2)
      expect(renderedText(renderer)).toContain('渲染已恢复')
    } finally {
      consoleError.mockRestore()
    }
  })

  it('returns to the paint-safe main-package route after an import failure', async () => {
    const Loader = createFeaturePageLoader(
      '动作说明',
      vi.fn().mockRejectedValue(new Error('chunk unavailable')),
    )
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(Loader))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('feature loader did not render')

    act(() => button(renderer!, '返回安全首页').props.onClick())

    expect(taro.reLaunch).toHaveBeenCalledWith({
      url: '/presentation/pages/home/index',
    })
  })

  it('rejects a loaded module without a component export using a stable safe code', async () => {
    const Loader = createFeaturePageLoader(
      '训练计划',
      vi.fn().mockResolvedValue({ default: undefined } as unknown as PageModule),
    )
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(Loader))
    })
    await act(async () => {
      firePageReady()
      await flushPage()
    })
    if (!renderer) throw new Error('feature loader did not render')

    expect(renderedText(renderer)).toContain('诊断码：WL-E04 · R5')
  })

  it('classifies native chunk failures without exposing raw error details', () => {
    expect(classifyFeatureLoadFailure({
      name: 'ChunkLoadError',
      type: 'missing',
      message: 'private route must not be displayed',
    })).toBe('WL-E01')
    expect(classifyFeatureLoadFailure({
      name: 'ChunkLoadError',
      type: 'fitness-native-require',
    })).toBe('WL-E02')
    expect(classifyFeatureLoadFailure({
      name: 'ChunkLoadError',
      type: 'fitness-envelope-invalid',
    })).toBe('WL-E03')
    expect(classifyFeatureLoadFailure({
      name: 'FitnessAsyncModuleEvaluationError',
      type: 'fitness-module-evaluation',
      moduleId: '58923',
      cause: new Error('private token must not be displayed'),
    })).toBe('WL-M58923')
    expect(classifyFeatureLoadFailure({
      type: 'fitness-module-evaluation',
      moduleId: '../../unsafe',
    })).toBe('WL-E99')
    expect(classifyFeatureLoadFailure(new Error('private token must not be displayed'))).toBe('WL-E99')
  })
})

describe('registered feature page loader boundary', () => {
  it('wraps every registered feature entry and keeps startup on its dedicated visible loader', () => {
    const subpackages = registeredSubpackages()
    expect([...subpackages.keys()].sort()).toEqual([
      'subpackages/account',
      'subpackages/exercise-guide',
      'subpackages/planning',
      'subpackages/progress',
      'subpackages/startup',
      'subpackages/workout',
    ])

    for (const root of [
      'subpackages/planning',
      'subpackages/workout',
      'subpackages/progress',
      'subpackages/account',
      'subpackages/exercise-guide',
    ]) {
      const pages = subpackages.get(root)
      expect(pages, root).toBeDefined()
      for (const page of pages ?? []) {
        const entryPath = resolve(projectRoot, 'src', root, `${page}.tsx`)
        expect(existsSync(entryPath), entryPath).toBe(true)
        const entry = readFileSync(entryPath, 'utf8')
        expect(entry, entryPath).toContain('createFeaturePageLoader')
        expect(entry, entryPath).toMatch(/\(\)\s*=>\s*import\(/)
      }
    }

    expect(subpackages.get('subpackages/startup')).toEqual(['pages/home/index'])
    const startupHome = readFileSync(
      resolve(projectRoot, 'src/subpackages/startup/pages/home/index.tsx'),
      'utf8',
    )
    const startupLoader = readFileSync(
      resolve(projectRoot, 'src/subpackages/startup/pages/home/startupApplicationLoader.ts'),
      'utf8',
    )
    expect(startupHome).toContain('loadStartupApplication')
    expect(startupHome).toContain('正在打开你的训练计划')
    expect(startupHome).not.toContain('featureRoots/startupCompositionRoot')
    expect(startupLoader).toMatch(/await\s+import\([\s\S]*startupCompositionRoot/)
    expect(startupLoader).toContain('module.getStartupApplication()')
  })
})
