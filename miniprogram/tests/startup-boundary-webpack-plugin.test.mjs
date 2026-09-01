import { createRequire } from 'node:module'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

const require = createRequire(import.meta.url)
const StartupBoundaryWebpackPlugin = require('../scripts/startup-boundary-webpack-plugin.cjs')
const {
  installCoreJsWeappGlobalAdapter,
  installStartupBoundaryPlugin,
} = require('../scripts/taro-build-plugin.cjs')
const webpack = require('webpack')

function createHook() {
  let handler
  return {
    tap(_name, callback) {
      handler = callback
    },
    call(...arguments_) {
      if (!handler) throw new Error('hook was not registered')
      return handler(...arguments_)
    },
  }
}

function createModule(resource, nestedModules) {
  return {
    ...(resource === undefined ? {} : { resource }),
    ...(nestedModules === undefined ? {} : { modules: nestedModules }),
  }
}

function createChunk(name, modules, files = [], options = {}) {
  return {
    name,
    modules,
    files: new Set(files),
    initial: options.initial ?? false,
    canBeInitial() {
      return this.initial
    },
  }
}

function createEntrypoint(chunks, asyncChunks = []) {
  return {
    chunks,
    getAllInitialChunks() {
      return chunks
    },
    getEntrypointChunk() {
      return {
        getAllInitialChunks() {
          return chunks
        },
        getAllAsyncChunks() {
          return asyncChunks
        },
        getAllReferencedChunks() {
          return [...chunks, ...asyncChunks]
        },
      }
    },
  }
}

function validateCompilation({
  chunks,
  appChunks,
  homeChunks,
  additionalEntrypoints = [],
  assets,
  pluginOptions = {},
}) {
  const thisCompilation = createHook()
  const processAssets = createHook()
  const compilation = {
    chunks: new Set(chunks),
    options: { context: 'E:/project' },
    entrypoints: new Map([
      ['app', createEntrypoint(appChunks)],
      ['presentation/pages/home/index', createEntrypoint(homeChunks)],
      ...additionalEntrypoints,
    ]),
    chunkGraph: {
      getChunkModulesIterable(chunk) {
        return chunk.modules
      },
    },
    hooks: { processAssets },
    assets,
    errors: [],
  }
  const compiler = {
    context: 'E:/project',
    hooks: { thisCompilation },
    webpack: {
      Compilation: { PROCESS_ASSETS_STAGE_REPORT: 5000 },
    },
  }
  compilation.compiler = compiler

  new StartupBoundaryWebpackPlugin({
    enforcePhysicalAsyncBoundaries: false,
    ...pluginOptions,
  }).apply(compiler)
  thisCompilation.call(compilation)
  processAssets.call({})
  return compilation.errors
}

async function compileWebpack(config) {
  const compiler = webpack(config)
  try {
    return await new Promise((resolve, reject) => {
      compiler.run((error, stats) => {
        if (error) reject(error)
        else if (!stats) reject(new Error('Webpack did not return stats'))
        else resolve(stats)
      })
    })
  } finally {
    await new Promise((resolve, reject) => {
      compiler.close((error) => error ? reject(error) : resolve())
    })
  }
}

describe('StartupBoundaryWebpackPlugin', () => {
  it('rejects forbidden feature modules in every app and home referenced chunk', () => {
    const app = createChunk('app', [
      createModule('E:\\day\\wechat\\Fitness\\code\\miniprogram\\src\\application\\startup.ts'),
    ])
    const common = createChunk('common', [
      createModule('E:\\day\\wechat\\Fitness\\code\\miniprogram\\src\\platform\\weapp\\featureRoots\\planningCompositionRoot.ts?abc'),
      createModule('E:\\day\\wechat\\Fitness\\code\\miniprogram\\src\\infrastructure\\api\\client.ts'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, common, home],
      appChunks: [app, common],
      homeChunks: [home, common],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('startup chunks contain forbidden modules')
    expect(errors[0].message).toContain('/src/platform/weapp/featureroots/planningcompositionroot.ts')
    expect(errors[0].message).toContain('/src/infrastructure/api/client.ts')
  })

  it('rejects a feature root assigned to another subpackage chunk', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const wrongFeatureChunk = createChunk(
      'subpackages/workout/pages/prepare/index',
      [createModule('E:/project/src/platform/weapp/featureRoots/planningCompositionRoot.ts')],
    )

    const errors = validateCompilation({
      chunks: [app, home, wrongFeatureChunk],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('feature roots are assigned outside their subpackage')
    expect(errors[0].message).toContain('executable outside subpackages/planning/')
  })

  it('rejects mixed executable identities even when one file has the expected owner', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const mixed = createChunk(
      undefined,
      [createModule('E:/project/src/platform/weapp/featureRoots/planningCompositionRoot.ts')],
      ['app.js', 'subpackages/planning/common.js'],
    )

    const errors = validateCompilation({
      chunks: [app, home, mixed],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('executable outside subpackages/planning/: app.js')
  })

  it('keeps the startup composition root inside the startup subpackage', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const wrongStartupChunk = createChunk(
      'subpackages/planning/pages/onboarding/index',
      [createModule('E:/project/src/platform/weapp/featureRoots/startupCompositionRoot.ts')],
    )

    const errors = validateCompilation({
      chunks: [app, home, wrongStartupChunk],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('feature roots are assigned outside their subpackage')
    expect(errors[0].message).toContain('executable outside subpackages/startup/')
  })

  it('accepts narrow startup modules and feature roots owned by their subpackages', () => {
    const app = createChunk('app', [
      createModule('E:\\project\\src\\app.tsx'),
      createModule('E:\\project\\src\\platform\\weapp\\sharedPlatformKernel.ts'),
      createModule('E:\\project\\src\\infrastructure\\api\\sessionRefreshCoordinator.ts'),
    ])
    const home = createChunk('presentation/pages/home/index', [
      createModule('E:/project/src/presentation/pages/home/index.tsx'),
    ])
    const planning = createChunk(
      'subpackages/planning/pages/onboarding/index',
      [createModule('E:\\project\\src\\platform\\weapp\\featureRoots\\planningCompositionRoot.ts')],
    )
    const startup = createChunk(
      'subpackages/startup/pages/home/index',
      [createModule('E:/project/src/platform/weapp/featureRoots/startupCompositionRoot.ts')],
    )
    const workout = createChunk(
      undefined,
      [createModule('E:/project/src/platform/weapp/featureRoots/workoutCompositionRoot.ts')],
      ['subpackages/workout/common.js'],
    )

    const errors = validateCompilation({
      chunks: [app, home, startup, planning, workout],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toEqual([])
  })

  it('fails closed when a feature root is assigned to a chunk with no identity', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const unidentified = createChunk(
      undefined,
      [createModule('E:/project/src/platform/weapp/featureRoots/workoutCompositionRoot.ts')],
    )

    const errors = validateCompilation({
      chunks: [app, home, unidentified],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('(unidentified chunk)')
    expect(errors[0].message).toContain('expected subpackages/workout/')
  })

  it('rejects an unregistered composition root', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const nutrition = createChunk(
      'subpackages/planning/pages/nutrition/index',
      [createModule('E:/project/src/platform/weapp/featureRoots/nutritionCompositionRoot.ts')],
    )

    const errors = validateCompilation({
      chunks: [app, home, nutrition],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unregistered feature root')
    expect(errors[0].message).toContain('nutritioncompositionroot.ts')
  })

  it('rejects newly named business modules unless the startup allowlist is updated', () => {
    const app = createChunk('app', [
      createModule('E:/project/src/app.tsx'),
      createModule('E:/project/src/application/planCandidateService.ts'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved startup project module')
    expect(errors[0].message).toContain('/src/application/plancandidateservice.ts')
  })

  it('rejects a new third-party package from startup chunks by default', () => {
    const app = createChunk('app', [
      createModule('E:/project/src/app.tsx'),
      createModule('E:/project/node_modules/feature-sdk/dist/index.js'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved startup dependency package feature-sdk')
  })

  it('allows only the pure compatibility runtime and its WeChat global adapter', () => {
    const app = createChunk('app', [
      createModule('E:/project/src/app.tsx'),
      createModule('E:/project/src/platform/weapp/coreJsWeappGlobal.cjs'),
      createModule('E:/project/node_modules/@babel/runtime-corejs3/core-js-stable/instance/pad-start.js'),
      createModule('E:/project/node_modules/core-js-pure/internals/string-pad.js'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toEqual([])
  })

  it('applies the dependency allowlist to nameForCondition provenance too', () => {
    const dependency = {
      nameForCondition() {
        return 'E:/project/node_modules/analytics-sdk/dist/index.js'
      },
    }
    const app = createChunk('app', [dependency])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved startup dependency package analytics-sdk')
  })

  it('rejects external, generated, workspace, and vendored resources by default', () => {
    const app = createChunk('app', [
      createModule('E:/project/src/app.tsx'),
      createModule('E:/project/generated/bootstrap.js'),
      createModule('E:/workspace/feature-sdk/dist/index.js'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain(
      'unapproved startup external resource: e:/project/generated/bootstrap.js',
    )
    expect(errors[0].message).toContain(
      'unapproved startup external resource: e:/workspace/feature-sdk/dist/index.js',
    )
  })

  it('accepts package-level framework dependencies with Windows paths', () => {
    const app = createChunk('app', [
      createModule('E:\\project\\src\\app.tsx'),
      createModule('E:\\project\\node_modules\\react\\index.js'),
      createModule('E:\\project\\node_modules\\@tarojs\\runtime\\dist\\index.js?x'),
    ])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toEqual([])
  })

  it('accepts explicit Webpack runtime modules without resource provenance', () => {
    class JsonpChunkLoadingRuntimeModule {}
    const runtimeByConstructor = new JsonpChunkLoadingRuntimeModule()
    runtimeByConstructor.type = 'runtime'
    const app = createChunk('app', [runtimeByConstructor])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toEqual([])
  })

  it('does not accept runtime provenance from type or constructor name alone', () => {
    class BusinessRuntimeModule {}
    const constructorOnly = new BusinessRuntimeModule()
    const typeOnly = { type: 'runtime' }
    const app = createChunk('app', [constructorOnly, typeOnly])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('BusinessRuntimeModule (type unknown)')
    expect(errors[0].message).toContain('Object (type runtime)')
  })

  it('fails closed for an ordinary module with no resource provenance', () => {
    const app = createChunk('app', [createModule(undefined)])
    const home = createChunk('presentation/pages/home/index', [])

    const errors = validateCompilation({
      chunks: [app, home],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved provenance-free module')
    expect(errors[0].message).toContain('Object (type unknown)')
  })

  it('installs the boundary plugin through the project-local Taro webpack chain', () => {
    const calls = []
    const chain = {
      plugin(name) {
        calls.push(['plugin', name])
        return {
          use(plugin) {
            calls.push(['use', plugin])
          },
        }
      },
    }

    installStartupBoundaryPlugin(chain)

    expect(calls).toEqual([
      ['plugin', 'fitness-startup-boundary'],
      ['use', StartupBoundaryWebpackPlugin],
    ])
  })

  it('replaces only the core-js-pure global detector with the WeChat standard-library adapter', () => {
    const calls = []
    const chain = {
      plugin(name) {
        calls.push(['plugin', name])
        return {
          use(plugin, arguments_) {
            calls.push(['use', plugin, arguments_])
          },
        }
      },
    }

    installCoreJsWeappGlobalAdapter(chain, webpack)

    expect(calls[0]).toEqual(['plugin', 'fitness-core-js-weapp-global'])
    expect(calls[1][1]).toBe(webpack.NormalModuleReplacementPlugin)
    const [, replace] = calls[1][2]
    const coreJsRequest = {
      context: 'E:\\project\\node_modules\\core-js-pure\\internals',
      request: './global-this',
    }
    replace(coreJsRequest)
    expect(coreJsRequest.request.replace(/\\/g, '/')).toMatch(
      /\/src\/platform\/weapp\/coreJsWeappGlobal\.cjs$/,
    )

    const coreJsModuleRequest = {
      context: 'E:\\project\\node_modules\\core-js-pure\\modules',
      request: '../internals/global-this',
    }
    replace(coreJsModuleRequest)
    expect(coreJsModuleRequest.request.replace(/\\/g, '/')).toMatch(
      /\/src\/platform\/weapp\/coreJsWeappGlobal\.cjs$/,
    )

    const unrelatedRequest = {
      context: 'E:\\project\\node_modules\\feature-sdk\\internals',
      request: './global-this',
    }
    replace(unrelatedRequest)
    expect(unrelatedRequest.request).toBe('./global-this')

    const publicGlobalThisRequest = {
      context: 'E:\\project\\node_modules\\core-js-pure\\features',
      request: '../full/global-this',
    }
    replace(publicGlobalThisRequest)
    expect(publicGlobalThisRequest.request).toBe('../full/global-this')
  })

  it('rejects a planning page module assigned to a workout chunk', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const wrongOwner = createChunk(
      'subpackages/workout/async/workout-session',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/workout/async/workout-session.js'],
    )

    const errors = validateCompilation({
      chunks: [app, home, wrongOwner],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('/src/presentation/pages/plan/index.tsx')
    expect(errors[0].message).toContain('executable outside subpackages/planning/')
  })

  it('assigns workout generation operations exclusively to the workout owner', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const resource = createModule(
      'E:/project/src/platform/weapp/featureRoots/workoutGenerationOperations.ts',
    )
    const workout = createChunk(
      'subpackages/workout/async/workout-session',
      [resource],
      ['subpackages/workout/async/workout-session.js'],
    )
    const planning = createChunk(
      'subpackages/planning/async/plan',
      [resource],
      ['subpackages/planning/async/plan.js'],
    )

    expect(validateCompilation({
      chunks: [app, home, workout],
      appChunks: [app],
      homeChunks: [home],
    })).toEqual([])

    const wrongOwnerErrors = validateCompilation({
      chunks: [app, home, planning],
      appChunks: [app],
      homeChunks: [home],
    })
    expect(wrongOwnerErrors).toHaveLength(1)
    expect(wrongOwnerErrors[0].message).toContain('workoutgenerationoperations.ts')
    expect(wrongOwnerErrors[0].message).toContain('executable outside subpackages/workout/')
  })

  it('rejects an unclassified project module from a subpackage chunk', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const feature = createChunk(
      'subpackages/planning/async/new-feature',
      [createModule('E:/project/src/application/newFeatureService.ts')],
      ['subpackages/planning/async/new-feature.js'],
    )

    const errors = validateCompilation({
      chunks: [app, home, feature],
      appChunks: [app],
      homeChunks: [home],
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unclassified project source')
    expect(errors[0].message).toContain('newfeatureservice.ts')
  })

  it('accepts a physical async page whose target and final asset stay in its owner', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx')],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/planning/async/plan',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/planning/async/plan.js'],
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[boundary.entrypoint, createEntrypoint([shell], [feature])]],
      assets: {
        'subpackages/planning/async/plan.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toEqual([])
  })

  it('accepts only the declared noninitial exercise-guide secondary chunk for workout session', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/workout/pages/workout-session/index',
      [createModule('E:/project/src/subpackages/workout/pages/workout-session/index.tsx')],
      ['subpackages/workout/pages/workout-session/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/workout/async/workout-session',
      [createModule('E:/project/src/presentation/pages/workout-session/index.tsx')],
      ['subpackages/workout/async/workout-session.js'],
    )
    const secondary = createChunk(
      'subpackages/exercise-guide/async/detail',
      [createModule(
        'E:/project/src/subpackages/exercise-guide/components/exercise-motion-guide/index.tsx',
      )],
      ['subpackages/exercise-guide/async/detail.js'],
    )
    const boundary = {
      label: 'workout session fixture',
      entrypoint: 'subpackages/workout/pages/workout-session/index',
      asyncAsset: 'subpackages/workout/async/workout-session.js',
      asyncPrefix: 'subpackages/workout/async/',
      targetPattern: /\/src\/presentation\/pages\/workout-session\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/workout\/pages\/workout-session\/index\.tsx$/,
      ],
      secondaryAsyncAssets: [{
        asset: 'subpackages/exercise-guide/async/detail.js',
        targetPattern: /\/src\/subpackages\/exercise-guide\/components\/exercise-motion-guide\/index\.[cm]?[jt]sx?$/,
      }],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature, secondary],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[
        boundary.entrypoint,
        createEntrypoint([shell], [feature, secondary]),
      ]],
      assets: {
        'subpackages/workout/async/workout-session.js': {},
        'subpackages/exercise-guide/async/detail.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toEqual([])
  })

  it('rejects an undeclared cross-owner secondary chunk', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/workout/pages/workout-session/index',
      [createModule('E:/project/src/subpackages/workout/pages/workout-session/index.tsx')],
      ['subpackages/workout/pages/workout-session/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/workout/async/workout-session',
      [createModule('E:/project/src/presentation/pages/workout-session/index.tsx')],
      ['subpackages/workout/async/workout-session.js'],
    )
    const undeclared = createChunk(
      'subpackages/exercise-guide/async/undeclared-guide',
      [createModule(
        'E:/project/src/subpackages/exercise-guide/components/exercise-motion-guide/index.tsx',
      )],
      ['subpackages/exercise-guide/async/undeclared-guide.js'],
    )
    const boundary = {
      label: 'workout session fixture',
      entrypoint: 'subpackages/workout/pages/workout-session/index',
      asyncAsset: 'subpackages/workout/async/workout-session.js',
      asyncPrefix: 'subpackages/workout/async/',
      targetPattern: /\/src\/presentation\/pages\/workout-session\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/workout\/pages\/workout-session\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature, undeclared],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[
        boundary.entrypoint,
        createEntrypoint([shell], [feature, undeclared]),
      ]],
      assets: {
        'subpackages/workout/async/workout-session.js': {},
        'subpackages/exercise-guide/async/undeclared-guide.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain(
      'async dependency escaped subpackages/workout/async/: '
      + 'subpackages/exercise-guide/async/undeclared-guide.js',
    )
  })

  it('rejects an initial business target, missing async asset, and async CSS', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const target = createModule('E:/project/src/presentation/pages/plan/index.tsx')
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [
        createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx'),
        target,
      ],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const invalidAsync = createChunk(
      'subpackages/planning/async/plan',
      [
        target,
        { type: 'css/mini-extract', resource: 'E:/project/src/presentation/pages/plan/index.scss' },
      ],
      [
        'subpackages/planning/async/plan.js',
        'subpackages/planning/async/plan.wxss',
      ],
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, invalidAsync],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[boundary.entrypoint, createEntrypoint([shell], [invalidAsync])]],
      assets: {},
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved initial project module')
    expect(errors[0].message).toContain('missing final async asset')
    expect(errors[0].message).toContain('async chunk emitted style asset')
    expect(errors[0].message).toContain('CSS/mini-extract module')
  })

  it('rejects a new owner-correct business module from a page initial closure', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [
        createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx'),
        createModule('E:/project/src/subpackages/planning/business/NewPlanner.ts'),
      ],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/planning/async/plan',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/planning/async/plan.js'],
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[boundary.entrypoint, createEntrypoint([shell], [feature])]],
      assets: {
        'subpackages/planning/async/plan.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved initial project module')
    expect(errors[0].message).toContain('/src/subpackages/planning/business/newplanner.ts')
  })

  it('rejects a new third-party dependency from a page initial closure', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [
        createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx'),
        createModule('E:/project/node_modules/react/index.js'),
        createModule('E:/project/node_modules/feature-sdk/index.js'),
      ],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/planning/async/plan',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/planning/async/plan.js'],
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[boundary.entrypoint, createEntrypoint([shell], [feature])]],
      assets: {
        'subpackages/planning/async/plan.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain(
      'unapproved initial dependency package feature-sdk',
    )
    expect(errors[0].message).not.toContain(
      'unapproved initial dependency package react',
    )
  })

  it('rejects an ordinary provenance-free module from a page initial closure', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [
        createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx'),
        createModule(undefined),
      ],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/planning/async/plan',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/planning/async/plan.js'],
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [[boundary.entrypoint, createEntrypoint([shell], [feature])]],
      assets: {
        'subpackages/planning/async/plan.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('unapproved provenance-free initial module')
    expect(errors[0].message).toContain('Object (type unknown)')
  })

  it('rejects a new subpackage page entrypoint without a registered async boundary', () => {
    const app = createChunk('app', [])
    const home = createChunk('presentation/pages/home/index', [])
    const shell = createChunk(
      'subpackages/planning/pages/plan/index',
      [createModule('E:/project/src/subpackages/planning/pages/plan/index.tsx')],
      ['subpackages/planning/pages/plan/index.js'],
      { initial: true },
    )
    const feature = createChunk(
      'subpackages/planning/async/plan',
      [createModule('E:/project/src/presentation/pages/plan/index.tsx')],
      ['subpackages/planning/async/plan.js'],
    )
    const extraShell = createChunk(
      'subpackages/planning/pages/new-planner/index',
      [createModule('E:/project/src/subpackages/planning/pages/new-planner/index.tsx')],
      ['subpackages/planning/pages/new-planner/index.js'],
      { initial: true },
    )
    const boundary = {
      label: 'planning plan fixture',
      entrypoint: 'subpackages/planning/pages/plan/index',
      asyncAsset: 'subpackages/planning/async/plan.js',
      asyncPrefix: 'subpackages/planning/async/',
      targetPattern: /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/,
      initialProjectPatterns: [
        /\/src\/subpackages\/planning\/pages\/plan\/index\.tsx$/,
      ],
    }

    const errors = validateCompilation({
      chunks: [app, home, shell, feature, extraShell],
      appChunks: [app],
      homeChunks: [home],
      additionalEntrypoints: [
        [boundary.entrypoint, createEntrypoint([shell], [feature])],
        [
          'subpackages/planning/pages/new-planner/index',
          createEntrypoint([extraShell]),
        ],
      ],
      assets: {
        'subpackages/planning/async/plan.js': {},
      },
      pluginOptions: {
        enforcePhysicalAsyncBoundaries: true,
        asyncPageBoundaries: [boundary],
      },
    })

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain(
      'unregistered physical async page entrypoint: subpackages/planning/pages/new-planner/index',
    )
  })

  it('observes mixed emitted JS files at the real Webpack processAssets lifecycle', async () => {
    const fixtureRoot = await mkdtemp(join(tmpdir(), 'fitness-startup-boundary-'))
    const sourceRoot = join(fixtureRoot, 'src')
    const planningRoot = join(
      sourceRoot,
      'platform',
      'weapp',
      'featureRoots',
      'planningCompositionRoot.js',
    )
    const planningEntry = join(sourceRoot, 'subpackages', 'planning', 'index.js')
    const outputPath = join(fixtureRoot, 'dist')

    try {
      await Promise.all([
        mkdir(join(sourceRoot, 'presentation', 'pages', 'home'), { recursive: true }),
        mkdir(join(sourceRoot, 'platform', 'weapp', 'featureRoots'), { recursive: true }),
        mkdir(join(sourceRoot, 'subpackages', 'planning'), { recursive: true }),
      ])
      await Promise.all([
        writeFile(join(sourceRoot, 'app.js'), 'module.exports = {}\n'),
        writeFile(
          join(sourceRoot, 'presentation', 'pages', 'home', 'index.js'),
          'module.exports = {}\n',
        ),
        writeFile(planningRoot, 'module.exports = {}\n'),
        writeFile(
          planningEntry,
          "require('../../platform/weapp/featureRoots/planningCompositionRoot.js')\n",
        ),
      ])

      class AddMixedExecutableFilePlugin {
        apply(compiler) {
          compiler.hooks.thisCompilation.tap('AddMixedExecutableFilePlugin', (compilation) => {
            compilation.hooks.processAssets.tap(
              {
                name: 'AddMixedExecutableFilePlugin',
                stage: compiler.webpack.Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL,
              },
              () => {
                const planningChunk = [...compilation.chunks].find(
                  (chunk) => chunk.name === 'subpackages/planning/index',
                )
                if (!planningChunk) throw new Error('planning fixture chunk was not created')
                compilation.emitAsset(
                  'app-copy.js',
                  new compiler.webpack.sources.RawSource(''),
                )
                planningChunk.files.add('app-copy.js')
              },
            )
          })
        }
      }

      const stats = await compileWebpack({
        context: fixtureRoot,
        mode: 'production',
        devtool: false,
        entry: {
          app: join(sourceRoot, 'app.js'),
          'presentation/pages/home/index': join(
            sourceRoot,
            'presentation',
            'pages',
            'home',
            'index.js',
          ),
          'subpackages/planning/index': planningEntry,
        },
        output: {
          path: outputPath,
          filename: '[name].js',
        },
        optimization: {
          concatenateModules: false,
          minimize: false,
          splitChunks: false,
        },
        plugins: [
          new AddMixedExecutableFilePlugin(),
          new StartupBoundaryWebpackPlugin({
            enforcePhysicalAsyncBoundaries: false,
          }),
        ],
      })
      const messages = stats.toJson({ all: false, errors: true }).errors
        .map((error) => error.message)
        .join('\n')

      expect(stats.hasErrors()).toBe(true)
      expect(messages).toContain(
        'executable outside subpackages/planning/: app-copy.js',
      )
    } finally {
      await rm(fixtureRoot, { recursive: true, force: true })
    }
  })
})
