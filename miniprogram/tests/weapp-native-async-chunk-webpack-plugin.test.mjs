import { createRequire } from 'node:module'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import vm from 'node:vm'

import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

const require = createRequire(import.meta.url)
const webpack = require('webpack')
const WeappNativeAsyncChunkWebpackPlugin = require(
  '../scripts/weapp-native-async-chunk-webpack-plugin.cjs',
)

const {
  ASYNC_CHUNK_ENVELOPE_MARKER,
  isSafeNativeAsyncChunkUrl,
  normalizeNativeAsyncChunkUrl,
} = WeappNativeAsyncChunkWebpackPlugin

const ASYNC_CHUNK_NAME = 'subpackages/planning/async/feature'
const ASYNC_CHUNK_FILE = `${ASYNC_CHUNK_NAME}.js`

let fixture

beforeAll(async () => {
  fixture = await compileFixture()
})

afterAll(async () => {
  if (fixture?.root) {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

describe('WeappNativeAsyncChunkWebpackPlugin', () => {
  it('keeps a physical Webpack 5 async chunk and transports registration through module exports', () => {
    const asyncChunk = [...fixture.stats.compilation.chunks]
      .find((chunk) => chunk.name === ASYNC_CHUNK_NAME)

    expect(asyncChunk, 'the named dynamic import must remain a Webpack chunk').toBeDefined()
    expect(asyncChunk.canBeInitial()).toBe(false)
    expect([...asyncChunk.files]).toContain(ASYNC_CHUNK_FILE)
    expect(fixture.stats.compilation.getAsset(ASYNC_CHUNK_FILE)).toBeDefined()

    expect(fixture.entrySource).toMatch(/__webpack_require__\.e\(/)
    const nativeRequireArguments = [...fixture.runtimeSource.matchAll(/\brequire\.async\(([^)]+)\)/g)]
      .map((match) => match[1].trim())
    expect(nativeRequireArguments).toEqual([JSON.stringify(`./${ASYNC_CHUNK_FILE}`)])
    expect(fixture.runtimeSource).not.toContain('require.async(chunkPath)')
    expect(fixture.runtimeSource).toContain('inProgressWeappScripts = Object.create(null)')
    expect(fixture.runtimeSource).toContain('webpackJsonpCallback')
    expect(fixture.runtimeSource).toContain('__webpack_require__.O')
    expect(fixture.runtimeSource).toContain(ASYNC_CHUNK_ENVELOPE_MARKER)
    expect(fixture.runtimeSource).toContain('installWeappAsyncChunk')
    expect(fixture.runtimeSource).toContain('fitness-envelope-invalid')
    expect(fixture.runtimeSource).toContain('fitness-native-require')
    expect(fixture.asyncChunkSource).toContain(`module.exports={"${ASYNC_CHUNK_ENVELOPE_MARKER}":1`)
    expect(fixture.asyncChunkSource).not.toContain('webpackJsonp')
    expect(fixture.asyncChunkSource).not.toContain('.push(')
    expect(fixture.runtimeSource).not.toMatch(
      /\bnew\s+Function\b|\bdocument\b|\beval\s*\(|\bfetch\s*\(|\bXMLHttpRequest\b|\bwx\.request\b/,
    )
  })

  it('loads an envelope from an isolated subpackage context once for concurrent imports', async () => {
    const runtime = createFixtureRuntime(fixture)

    expect(runtime.context.wx.__featureEvaluations__).toBeUndefined()
    const first = runtime.importFeature()
    const concurrent = runtime.importFeature()

    expect(runtime.requests).toHaveLength(1)
    expect(runtime.requireAsync).toHaveBeenCalledTimes(1)
    expect(runtime.requests[0].path).toBe(`./${ASYNC_CHUNK_FILE}`)

    runtime.requests[0].succeed()
    await expect(first).resolves.toMatchObject({ value: 'native-async-loaded' })
    await expect(concurrent).resolves.toMatchObject({ value: 'native-async-loaded' })
    expect(runtime.context.wx.__featureEvaluations__).toBe(1)

    const duplicateEnvelope = runtime.evaluateAsyncChunk()
    expect(duplicateEnvelope[ASYNC_CHUNK_ENVELOPE_MARKER]).toBe(1)
    await expect(runtime.importFeature()).resolves.toMatchObject({
      value: 'native-async-loaded',
    })
    expect(runtime.requireAsync).toHaveBeenCalledTimes(1)
    expect(runtime.context.wx.__featureEvaluations__).toBe(1)
  })

  it('rejects a native load failure and performs a fresh require.async attempt on retry', async () => {
    const runtime = createFixtureRuntime(fixture)
    const first = runtime.importFeature()

    runtime.requests[0].fail(new Error('native download failed'))
    await expect(first).rejects.toMatchObject({
      name: 'ChunkLoadError',
      type: 'fitness-native-require',
      request: `./${ASYNC_CHUNK_FILE}`,
    })

    const retry = runtime.importFeature()
    expect(runtime.requests).toHaveLength(2)
    expect(runtime.requireAsync).toHaveBeenCalledTimes(2)
    runtime.requests[1].succeed()

    await expect(retry).resolves.toMatchObject({ value: 'native-async-loaded' })
    expect(runtime.context.wx.__featureEvaluations__).toBe(1)
  })

  it('classifies an invalid cross-context envelope and allows a clean retry', async () => {
    const runtime = createFixtureRuntime(fixture)
    const first = runtime.importFeature()

    runtime.requests[0].succeed({})
    await expect(first).rejects.toMatchObject({
      name: 'ChunkLoadError',
      type: 'fitness-envelope-invalid',
      request: `./${ASYNC_CHUNK_FILE}`,
    })

    const retry = runtime.importFeature()
    expect(runtime.requests).toHaveLength(2)
    runtime.requests[1].succeed()
    await expect(retry).resolves.toMatchObject({ value: 'native-async-loaded' })
  })

  it('tags the innermost failing async module without exposing its raw error', async () => {
    const runtime = createFixtureRuntime(fixture)
    runtime.context.wx.__forceFeatureFailure__ = true
    const loading = runtime.importFeature()

    runtime.requests[0].succeed()

    await expect(loading).rejects.toMatchObject({
      name: 'FitnessAsyncModuleEvaluationError',
      type: 'fitness-module-evaluation',
      moduleId: expect.stringMatching(/^\d+$/),
    })
  })

  it('does not let a stale or duplicate settlement from a failed attempt complete its retry', async () => {
    const runtime = createFixtureRuntime(fixture, { manualThenable: true })
    const first = runtime.importFeature()

    runtime.requests[0].reject(new Error('first attempt failed'))
    await expect(first).rejects.toMatchObject({ name: 'ChunkLoadError' })

    let retryState = 'pending'
    const retry = runtime.importFeature()
    void retry.then(
      () => { retryState = 'resolved' },
      () => { retryState = 'rejected' },
    )
    expect(runtime.requests).toHaveLength(2)

    runtime.requests[0].resolve({ evaluateChunk: true })
    runtime.requests[0].reject(new Error('duplicate stale failure'))
    await flushMicrotasks()
    expect(retryState).toBe('pending')
    expect(runtime.context.wx.__featureEvaluations__).toBeUndefined()

    runtime.requests[1].resolve({ evaluateChunk: true })
    await expect(retry).resolves.toMatchObject({ value: 'native-async-loaded' })
    expect(retryState).toBe('resolved')
    expect(runtime.context.wx.__featureEvaluations__).toBe(1)
  })
})

describe('native async chunk URL validation', () => {
  it.each([
    ['./subpackages/planning/async/feature.js', ASYNC_CHUNK_FILE],
    ['/subpackages/planning/async/feature.js', ASYNC_CHUNK_FILE],
    ['subpackages/planning/async/feature.js', ASYNC_CHUNK_FILE],
    ['subpackages\\planning\\async\\feature.js', ASYNC_CHUNK_FILE],
  ])('accepts and canonicalizes %s', (input, expected) => {
    expect(normalizeNativeAsyncChunkUrl(input)).toBe(expected)
    expect(isSafeNativeAsyncChunkUrl(input)).toBe(true)
  })

  it.each([
    'https://example.test/subpackages/planning/async/feature.js',
    'file:///subpackages/planning/async/feature.js',
    'C:\\subpackages\\planning\\async\\feature.js',
    '\\\\server\\share\\subpackages\\planning\\async\\feature.js',
    './subpackages/planning/async/../feature.js',
    '/runtime.js',
    'runtime.js',
    '/subpackages/planning/feature.js',
    '/subpackages/nutrition/async/feature.js',
    '/subpackages/planning-other/async/feature.js',
  ])('rejects unsafe or non-owned path %s', (input) => {
    expect(normalizeNativeAsyncChunkUrl(input)).toBeUndefined()
    expect(isSafeNativeAsyncChunkUrl(input)).toBe(false)
  })
})

async function compileFixture() {
  const root = await mkdtemp(join(tmpdir(), 'fitness-weapp-native-async-'))
  const sourceRoot = join(root, 'src')
  const outputRoot = join(root, 'dist')
  await mkdir(sourceRoot, { recursive: true })
  await Promise.all([
    writeFile(
      join(sourceRoot, 'index.js'),
      [
        'globalThis.__importFeature__ = function() {',
        '  return import(',
        `    /* webpackChunkName: ${JSON.stringify(ASYNC_CHUNK_NAME)} */`,
        "    './feature.js'",
        '  );',
        '};',
        '',
      ].join('\n'),
    ),
    writeFile(
      join(sourceRoot, 'feature.js'),
      [
        "if (wx.__forceFeatureFailure__) throw new TypeError('private runtime detail');",
        'wx.__featureEvaluations__ = (wx.__featureEvaluations__ || 0) + 1;',
        "export const value = 'native-async-loaded';",
        '',
      ].join('\n'),
    ),
  ])

  const compiler = webpack({
    context: root,
    mode: 'production',
    target: ['web', 'es5'],
    devtool: false,
    entry: { main: join(sourceRoot, 'index.js') },
    output: {
      path: outputRoot,
      filename: '[name].js',
      chunkFilename: '[name].js',
      chunkLoading: 'jsonp',
      chunkLoadingGlobal: 'webpackJsonp',
      globalObject: 'wx',
      publicPath: '/',
    },
    optimization: {
      minimize: false,
      runtimeChunk: { name: 'runtime' },
      splitChunks: false,
    },
    plugins: [new WeappNativeAsyncChunkWebpackPlugin()],
  })

  try {
    const stats = await runCompiler(compiler)
    const errors = stats.toJson({ all: false, errors: true }).errors
    if (stats.hasErrors()) {
      throw new Error(errors.map((error) => error.message).join('\n'))
    }
    return {
      root,
      outputRoot,
      stats,
      runtimeSource: await readFile(join(outputRoot, 'runtime.js'), 'utf8'),
      entrySource: await readFile(join(outputRoot, 'main.js'), 'utf8'),
      asyncChunkSource: await readFile(join(outputRoot, ASYNC_CHUNK_FILE), 'utf8'),
    }
  } catch (error) {
    await rm(root, { recursive: true, force: true })
    throw error
  } finally {
    await closeCompiler(compiler)
  }
}

function createFixtureRuntime(compiled, options = {}) {
  const requests = []
  let evaluateAsyncChunk
  const wx = {}
  const requireAsync = vi.fn((path) => {
    if (options.manualThenable) {
      const request = { path }
      const thenable = {
        then(resolve, reject) {
          request.resolve = ({ evaluateChunk = false } = {}) => {
            resolve(evaluateChunk ? evaluateAsyncChunk() : undefined)
          }
          request.reject = reject
        },
      }
      requests.push(request)
      return thenable
    }

    let resolvePromise
    let rejectPromise
    const promise = new Promise((resolve, reject) => {
      resolvePromise = resolve
      rejectPromise = reject
    })
    requests.push({
      path,
      succeed(envelope = evaluateAsyncChunk()) {
        resolvePromise(envelope)
      },
      fail(error) {
        rejectPromise(error)
      },
    })
    return promise
  })
  const nativeRequire = () => {
    throw new Error('the fixture must not use synchronous native require')
  }
  nativeRequire.async = requireAsync

  const context = vm.createContext({
    clearTimeout,
    console,
    Promise,
    require: nativeRequire,
    setTimeout,
    wx,
  })
  runInContext(compiled.runtimeSource, context, 'runtime.js')
  runInContext(compiled.entrySource, context, 'main.js')
  evaluateAsyncChunk = () => {
    const asyncModule = { exports: {} }
    const asyncContext = vm.createContext({
      console,
      module: asyncModule,
      exports: asyncModule.exports,
      wx,
    })
    runInContext(compiled.asyncChunkSource, asyncContext, ASYNC_CHUNK_FILE)
    return asyncModule.exports
  }

  return {
    context,
    evaluateAsyncChunk,
    importFeature: () => context.__importFeature__(),
    requests,
    requireAsync,
  }
}

function runInContext(source, context, filename) {
  return new vm.Script(source, { filename }).runInContext(context)
}

function runCompiler(compiler) {
  return new Promise((resolve, reject) => {
    compiler.run((error, stats) => {
      if (error) reject(error)
      else if (!stats) reject(new Error('Webpack did not return stats'))
      else resolve(stats)
    })
  })
}

function closeCompiler(compiler) {
  return new Promise((resolve, reject) => {
    compiler.close((error) => error ? reject(error) : resolve())
  })
}

async function flushMicrotasks() {
  await Promise.resolve()
  await Promise.resolve()
}
