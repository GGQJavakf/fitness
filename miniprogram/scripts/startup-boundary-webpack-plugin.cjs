'use strict'

const PLUGIN_NAME = 'FitnessStartupBoundaryWebpackPlugin'

const FEATURE_ROOT_PATTERN =
  /\/src\/platform\/weapp\/featureroots\/[^/]*compositionroot\.[cm]?[jt]sx?$/

// The main package is deliberately a recovery shell plus a subpackage loader.
// Treat every project source module as forbidden unless it is part of this
// small, reviewed kernel. This makes newly added business modules fail closed
// without relying on their filenames.
const STARTUP_ALLOWED_PROJECT_MODULES = Object.freeze([
  /\/src\/app(?:\.config)?\.[cm]?[jt]sx?$/,
  /\/src\/app\.(?:s?css|less|styl(?:us)?)$/,
  /\/src\/presentation\/pages\/home\/index(?:\.config)?\.[cm]?[jt]sx?$/,
  /\/src\/presentation\/pages\/home\/index\.(?:s?css|less|styl(?:us)?)$/,
  /\/src\/platform\/weapp\/(?:apprecovery|corejsweappglobal|sharedplatformkernel|startupdiagnostics|useweappfirstpaint|wechatuserscopeddatalifecycle)\.[cm]?[jt]sx?$/,
  /\/src\/application\/errors\.[cm]?[jt]sx?$/,
  /\/src\/infrastructure\/api\/sessionrefreshcoordinator\.[cm]?[jt]sx?$/,
])

// Only framework/runtime packages needed by the app shell may enter the main
// package. Feature SDKs must be loaded by their owning subpackage instead.
const STARTUP_ALLOWED_NODE_MODULE_PACKAGES = Object.freeze(new Set([
  '@babel/runtime',
  '@babel/runtime-corejs3',
  '@tarojs/api',
  '@tarojs/plugin-framework-react',
  '@tarojs/plugin-platform-weapp',
  '@tarojs/react',
  '@tarojs/runtime',
  '@tarojs/shared',
  '@tarojs/taro',
  '@tarojs/webpack5-runner',
  'core-js-pure',
  'react',
  'react-reconciler',
  'scheduler',
  'tslib',
]))

const STARTUP_FORBIDDEN_MODULES = Object.freeze([
  {
    label: 'feature composition root',
    pattern: /\/src\/platform\/weapp\/featureroots\//,
  },
  {
    label: 'shared feature core',
    pattern: /\/src\/platform\/weapp\/featurecore\.[cm]?[jt]sx?$/,
  },
  {
    label: 'FitnessApiClient',
    pattern: /\/src\/infrastructure\/api\/client\.[cm]?[jt]sx?$/,
  },
  {
    label: 'AI business module',
    pattern: /\/src\/(?:application\/(?:ai|cloudbaseai)|platform\/weapp\/cloudbaseaiadapter)\.[cm]?[jt]sx?$/,
  },
  {
    label: 'planning business module',
    pattern: /\/src\/application\/(?:onboarding|usecases|planeditor|trainingpreferencesafety)\.[cm]?[jt]sx?$/,
  },
  {
    label: 'workout business module',
    pattern: /\/src\/(?:application\/(?:workout[^/]*|automaticworkoutweight)|application\/(?:use-cases|ports)\/workout[^/]*|platform\/weapp\/wechatworkout[^/]*)\.[cm]?[jt]sx?$/,
  },
  {
    label: 'privacy business module',
    pattern: /\/src\/application\/(?:privacy|localprivacylifecycle)\.[cm]?[jt]sx?$/,
  },
])

const FEATURE_ROOT_OWNERS = Object.freeze([
  {
    label: 'startup',
    pattern: /\/src\/platform\/weapp\/featureroots\/startupcompositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/startup/',
  },
  {
    label: 'planning',
    pattern: /\/src\/platform\/weapp\/featureroots\/(?:planning|plan)compositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/planning/',
  },
  {
    label: 'workout',
    pattern: /\/src\/platform\/weapp\/featureroots\/workoutcompositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/workout/',
  },
  {
    label: 'progress',
    pattern: /\/src\/platform\/weapp\/featureroots\/(?:progress|history)compositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/progress/',
  },
  {
    label: 'account',
    pattern: /\/src\/platform\/weapp\/featureroots\/accountcompositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/account/',
  },
  {
    label: 'exercise guide',
    pattern: /\/src\/platform\/weapp\/featureroots\/exerciseguidecompositionroot\.[cm]?[jt]sx?$/,
    chunkPrefix: 'subpackages/exercise-guide/',
  },
])

// Every feature-exclusive project module has exactly one physical subpackage
// owner. Shared modules are separately allowlisted below; new project modules
// therefore cannot silently drift into an arbitrary subpackage chunk.
const FEATURE_SOURCE_OWNERS = Object.freeze([
  {
    label: 'startup',
    chunkPrefix: 'subpackages/startup/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/startupcompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/startup\//,
    ],
  },
  {
    label: 'planning',
    chunkPrefix: 'subpackages/planning/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/planningcompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/planning\//,
      /\/src\/presentation\/pages\/(?:onboarding|plan-candidates|plan-editor|plan-presets|plan)\//,
      /\/src\/presentation\/activeplanloadfailure\.[cm]?[jt]sx?$/,
      /\/src\/application\/(?:onboarding|planeditor|usecases)\.[cm]?[jt]sx?$/,
    ],
  },
  {
    label: 'workout',
    chunkPrefix: 'subpackages/workout/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/workoutcompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/platform\/weapp\/featureroots\/workoutgenerationoperations\.[cm]?[jt]sx?$/,
      /\/src\/platform\/weapp\/wechatworkoutstartintentstore\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/workout\//,
      /\/src\/presentation\/pages\/workout-(?:prepare|session|summary)\//,
      /\/src\/presentation\/workoutweightinput\.[cm]?[jt]sx?$/,
      /\/src\/application\/(?:automaticworkoutweight|selectnexttrainingday|workout[^/]*)\.[cm]?[jt]sx?$/,
      /\/src\/application\/(?:use-cases|ports)\/workout[^/]*\.[cm]?[jt]sx?$/,
      /\/src\/domain\/workout\//,
    ],
  },
  {
    label: 'progress',
    chunkPrefix: 'subpackages/progress/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/progresscompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/platform\/weapp\/featureroots\/progressgenerationoperations\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/progress\//,
      /\/src\/presentation\/pages\/(?:exercise-trend|history|sync-conflicts)\//,
      /\/src\/presentation\/components\/progression-card\//,
      /\/src\/application\/(?:history|progression)\.[cm]?[jt]sx?$/,
    ],
  },
  {
    label: 'account',
    chunkPrefix: 'subpackages/account/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/accountcompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/account\//,
      /\/src\/presentation\/pages\/(?:exercise-preferences|my)\//,
      /\/src\/application\/(?:localprivacylifecycle|privacy)\.[cm]?[jt]sx?$/,
    ],
  },
  {
    label: 'exercise-guide',
    chunkPrefix: 'subpackages/exercise-guide/',
    patterns: [
      /\/src\/platform\/weapp\/featureroots\/exerciseguidecompositionroot\.[cm]?[jt]sx?$/,
      /\/src\/subpackages\/exercise-guide\/pages\//,
      /\/src\/subpackages\/exercise-guide\/assets\//,
      /\/src\/subpackages\/exercise-guide\/exercise-guidance\.[cm]?[jt]sx?$/,
    ],
  },
])

const SHARED_FEATURE_PROJECT_MODULES = Object.freeze([
  /\/src\/application\/(?:ai|cloudbaseai|content|errors|models|navigation|ports|startup|trainingpreferencesafety)\.[cm]?[jt]sx?$/,
  /\/src\/application\/use-cases\/workoutflowservice\.[cm]?[jt]sx?$/,
  /\/src\/application\/ports\/workoutdraftstore\.[cm]?[jt]sx?$/,
  /\/src\/application\/(?:workoutflow|workoutflowdraftmapper)\.[cm]?[jt]sx?$/,
  /\/src\/domain\/(?:common|sync)\//,
  /\/src\/domain\/workout\/resttimer\.[cm]?[jt]sx?$/,
  /\/src\/infrastructure\/api\/(?:client|generated|schema\.generated|sessionrefreshcoordinator)\.[cm]?[jt]sx?$/,
  /\/src\/infrastructure\/telemetry\/events\.[cm]?[jt]sx?$/,
  /\/src\/platform\/weapp\/(?:adapters|apprecovery|cloudbaseaiadapter|corejsweappglobal|featurecore|lifecycle|retryablelazy|runtimeconfiguration|sharedplatformkernel|startupdiagnostics|useweappfirstpaint|wechatstorageadapter|wechattelemetryreporter|wechatuserscopeddatalifecycle)\.[cm]?[jt]sx?$/,
  /\/src\/platform\/weapp\/featureroots\/(?:airuntime|wechatworkoutstartupstateadapter)\.[cm]?[jt]sx?$/,
  /\/src\/presentation\/(?:copy)\.[cm]?[jt]sx?$/,
  /\/src\/presentation\/components\/main-navigation\//,
  /\/src\/subpackages\/exercise-guide\/(?:assets|components\/exercise-motion-guide)\//,
  /\/src\/subpackages\/exercise-guide\/exercise-guidance\.[cm]?[jt]sx?$/,
  /\/src\/subpackages\/shared\//,
])

const FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES = Object.freeze([
  'subpackages/shared/createFeaturePageLoader.tsx',
  'subpackages/shared/featurePageLoader.scss',
  'platform/weapp/coreJsWeappGlobal.cjs',
  'platform/weapp/appRecovery.ts',
  'platform/weapp/startupDiagnostics.ts',
  'platform/weapp/useWeappFirstPaint.ts',
])

const STARTUP_PAGE_INITIAL_PROJECT_FILES = Object.freeze([
  'platform/weapp/coreJsWeappGlobal.cjs',
  'platform/weapp/startupDiagnostics.ts',
  'platform/weapp/useWeappFirstPaint.ts',
  'subpackages/startup/pages/home/loginSingleFlight.ts',
  'subpackages/startup/pages/home/startupApplicationLoader.ts',
])

const WORKOUT_SESSION_SECONDARY_ASYNC_ASSETS = Object.freeze([
  Object.freeze({
    asset: 'subpackages/exercise-guide/async/detail.js',
    targetPattern: /\/src\/subpackages\/exercise-guide\/components\/exercise-motion-guide\/index\.[cm]?[jt]sx?$/,
  }),
])

const ASYNC_PAGE_BOUNDARIES = Object.freeze([
  ['startup home', 'subpackages/startup/pages/home/index', 'subpackages/startup/async/startup-application.js', /\/src\/platform\/weapp\/featureroots\/startupcompositionroot\.[cm]?[jt]sx?$/, STARTUP_PAGE_INITIAL_PROJECT_FILES, ['subpackages/startup/pages/home/index.scss']],
  ['planning onboarding', 'subpackages/planning/pages/onboarding/index', 'subpackages/planning/async/onboarding.js', /\/src\/presentation\/pages\/onboarding\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/onboarding/index.scss']],
  ['planning plan candidates', 'subpackages/planning/pages/plan-candidates/index', 'subpackages/planning/async/plan-candidates.js', /\/src\/presentation\/pages\/plan-candidates\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/plan-candidates/index.scss']],
  ['planning plan presets', 'subpackages/planning/pages/plan-presets/index', 'subpackages/planning/async/plan-presets.js', /\/src\/presentation\/pages\/plan-presets\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/plan-presets/index.scss']],
  ['planning plan', 'subpackages/planning/pages/plan/index', 'subpackages/planning/async/plan.js', /\/src\/presentation\/pages\/plan\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/plan/index.scss', 'presentation/components/main-navigation/index.scss']],
  ['planning plan editor', 'subpackages/planning/pages/plan-editor/index', 'subpackages/planning/async/plan-editor.js', /\/src\/presentation\/pages\/plan-editor\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/plan-editor/index.scss']],
  ['workout prepare', 'subpackages/workout/pages/workout-prepare/index', 'subpackages/workout/async/workout-prepare.js', /\/src\/presentation\/pages\/workout-prepare\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/workout-prepare/index.scss']],
  ['workout session', 'subpackages/workout/pages/workout-session/index', 'subpackages/workout/async/workout-session.js', /\/src\/presentation\/pages\/workout-session\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/workout-session/index.scss', 'subpackages/exercise-guide/components/exercise-motion-guide/index.scss'], WORKOUT_SESSION_SECONDARY_ASYNC_ASSETS],
  ['workout summary', 'subpackages/workout/pages/workout-summary/index', 'subpackages/workout/async/workout-summary.js', /\/src\/presentation\/pages\/workout-summary\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/workout-summary/index.scss']],
  ['progress sync conflicts', 'subpackages/progress/pages/sync-conflicts/index', 'subpackages/progress/async/sync-conflicts.js', /\/src\/presentation\/pages\/sync-conflicts\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/sync-conflicts/index.scss']],
  ['progress history', 'subpackages/progress/pages/history/index', 'subpackages/progress/async/history.js', /\/src\/presentation\/pages\/history\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/history/index.scss', 'presentation/components/main-navigation/index.scss', 'presentation/components/progression-card/index.scss']],
  ['progress exercise trend', 'subpackages/progress/pages/exercise-trend/index', 'subpackages/progress/async/exercise-trend.js', /\/src\/presentation\/pages\/exercise-trend\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/exercise-trend/index.scss']],
  ['account my', 'subpackages/account/pages/my/index', 'subpackages/account/async/my.js', /\/src\/presentation\/pages\/my\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/my/index.scss', 'presentation/components/main-navigation/index.scss']],
  ['account exercise preferences', 'subpackages/account/pages/exercise-preferences/index', 'subpackages/account/async/exercise-preferences.js', /\/src\/presentation\/pages\/exercise-preferences\/index\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['presentation/pages/exercise-preferences/index.scss']],
  ['exercise guide detail', 'subpackages/exercise-guide/pages/detail/index', 'subpackages/exercise-guide/async/detail.js', /\/src\/subpackages\/exercise-guide\/pages\/detail\/detailpage\.[cm]?[jt]sx?$/, FEATURE_PAGE_LOADER_INITIAL_PROJECT_FILES, ['subpackages/exercise-guide/pages/detail/index.scss', 'subpackages/exercise-guide/components/exercise-motion-guide/index.scss']],
].map(([label, entrypoint, asyncAsset, targetPattern, sharedInitialFiles, styleFiles, secondaryAsyncAssets = []]) => Object.freeze({
  label,
  entrypoint,
  asyncAsset,
  asyncPrefix: asyncAsset.slice(0, asyncAsset.lastIndexOf('/') + 1),
  targetPattern,
  secondaryAsyncAssets: Object.freeze([...secondaryAsyncAssets]),
  initialProjectPatterns: Object.freeze([
    `${entrypoint}.tsx`,
    `${entrypoint}.config.ts`,
    ...sharedInitialFiles,
    ...styleFiles,
  ].map(projectSourceExactPattern)),
})))

const FEATURE_SUBPACKAGE_PAGE_ENTRYPOINT =
  /^subpackages\/(?:startup|planning|workout|progress|account|exercise-guide)\/pages\/.+\/index$/

class StartupBoundaryWebpackPlugin {
  constructor(options = {}) {
    this.forbiddenModules = options.forbiddenModules ?? STARTUP_FORBIDDEN_MODULES
    this.featureRootOwners = options.featureRootOwners ?? FEATURE_ROOT_OWNERS
    this.allowedProjectModules =
      options.allowedProjectModules ?? STARTUP_ALLOWED_PROJECT_MODULES
    this.allowedNodeModulePackages =
      options.allowedNodeModulePackages ?? STARTUP_ALLOWED_NODE_MODULE_PACKAGES
    this.featureSourceOwners = options.featureSourceOwners ?? FEATURE_SOURCE_OWNERS
    this.sharedFeatureProjectModules =
      options.sharedFeatureProjectModules ?? SHARED_FEATURE_PROJECT_MODULES
    this.enforcePhysicalAsyncBoundaries =
      options.enforcePhysicalAsyncBoundaries ?? true
    this.asyncPageBoundaries = options.asyncPageBoundaries ?? ASYNC_PAGE_BOUNDARIES
  }

  apply(compiler) {
    compiler.hooks.thisCompilation.tap(PLUGIN_NAME, (compilation) => {
      const stage = compiler.webpack?.Compilation?.PROCESS_ASSETS_STAGE_REPORT ?? 5000
      compilation.hooks.processAssets.tap({ name: PLUGIN_NAME, stage }, () => {
        const violations = inspectCompilation(
          compilation,
          compilation.chunks,
          this.forbiddenModules,
          this.featureRootOwners,
          this.allowedProjectModules,
          this.allowedNodeModulePackages,
          this.featureSourceOwners,
          this.sharedFeatureProjectModules,
          this.enforcePhysicalAsyncBoundaries,
          this.asyncPageBoundaries,
        )
        if (!violations.length) return

        const error = new Error([
          `${PLUGIN_NAME} rejected the Webpack module graph:`,
          ...violations.map((violation) => `- ${violation}`),
        ].join('\n'))
        if (!Array.isArray(compilation.errors)) compilation.errors = []
        compilation.errors.push(error)
      })
    })
  }
}

function inspectCompilation(
  compilation,
  optimizedChunks = compilation.chunks,
  forbiddenModules = STARTUP_FORBIDDEN_MODULES,
  featureRootOwners = FEATURE_ROOT_OWNERS,
  allowedProjectModules = STARTUP_ALLOWED_PROJECT_MODULES,
  allowedNodeModulePackages = STARTUP_ALLOWED_NODE_MODULE_PACKAGES,
  featureSourceOwners = FEATURE_SOURCE_OWNERS,
  sharedFeatureProjectModules = SHARED_FEATURE_PROJECT_MODULES,
  enforcePhysicalAsyncBoundaries = true,
  asyncPageBoundaries = ASYNC_PAGE_BOUNDARIES,
) {
  const violations = []
  const startup = collectStartupChunks(compilation)

  if (startup.missingRoles.length) {
    const available = startup.availableEntrypoints.length
      ? startup.availableEntrypoints.join(', ')
      : '(none)'
    violations.push(
      `startup entrypoints could not be identified: ${startup.missingRoles.join(', ')}; available: ${available}`,
    )
  }

  const startupViolations = new Set()
  const projectRoot = compilationProjectRoot(compilation)
  for (const chunk of startup.chunks) {
    for (const module of modulesInChunk(compilation, chunk)) {
      for (const unprovenanced of collectUnapprovedProvenanceFreeModules(module)) {
        startupViolations.add(
          `${chunkLabel(chunk)} -> unapproved provenance-free module: ${describeModule(unprovenanced)}`,
        )
      }
      for (const resource of collectModuleResources(module)) {
        const dependencyPackage = nodeModulePackage(resource)
        if (dependencyPackage) {
          if (!allowedNodeModulePackages.has(dependencyPackage)) {
            startupViolations.add(
              `${chunkLabel(chunk)} -> unapproved startup dependency package ${dependencyPackage}: ${resource}`,
            )
          }
          continue
        }
        const rule = forbiddenModules.find((candidate) => candidate.pattern.test(resource))
        if (rule) {
          startupViolations.add(`${chunkLabel(chunk)} -> ${rule.label}: ${resource}`)
          continue
        }
        if (isProjectSourceResource(resource, projectRoot)) {
          if (!allowedProjectModules.some((candidate) => candidate.test(resource))) {
            startupViolations.add(
              `${chunkLabel(chunk)} -> unapproved startup project module: ${resource}`,
            )
          }
          continue
        }
        startupViolations.add(
          `${chunkLabel(chunk)} -> unapproved startup external resource: ${resource}`,
        )
      }
    }
  }
  if (startupViolations.size) {
    violations.push(
      `startup chunks contain forbidden modules:\n  ${[...startupViolations].sort().join('\n  ')}`,
    )
  }

  const ownershipViolations = new Set()
  for (const chunk of asIterable(optimizedChunks)) {
    const identities = chunkIdentities(chunk)
    const executableIdentities = executableChunkIdentities(chunk)
    const isSubpackageChunk = executableIdentities.some((identity) =>
      identity.startsWith('subpackages/') || identity.includes('/subpackages/'))
    for (const module of modulesInChunk(compilation, chunk)) {
      for (const resource of collectModuleResources(module)) {
        const rootOwner = FEATURE_ROOT_PATTERN.test(resource)
          ? featureRootOwners.find((candidate) => candidate.pattern.test(resource))
          : undefined
        if (FEATURE_ROOT_PATTERN.test(resource) && !rootOwner) {
          ownershipViolations.add(
            `${resource} -> unregistered feature root (no subpackage owner declared)`,
          )
          continue
        }
        const explicitlyShared = sharedFeatureProjectModules.some(
          (pattern) => pattern.test(resource),
        )
        const owner = explicitlyShared
          ? rootOwner
          : sourceOwnerForResource(resource, featureSourceOwners) ?? rootOwner
        if (!owner) {
          if (
            isSubpackageChunk
            && isProjectSourceResource(resource, projectRoot)
            && !explicitlyShared
          ) {
            ownershipViolations.add(
              `${resource} -> unclassified project source in ${identities.join(', ') || '(unidentified chunk)'}`,
            )
          }
          continue
        }
        if (executableIdentities.length === 0) {
          ownershipViolations.add(
            `${resource} -> (unidentified chunk) (expected ${owner.chunkPrefix})`,
          )
          continue
        }
        const outsideOwner = executableIdentities.filter(
          (identity) => !isWithinChunkPrefix(identity, owner.chunkPrefix),
        )
        if (outsideOwner.length === 0) continue
        ownershipViolations.add(
          `${resource} -> ${executableIdentities.join(', ')} (executable outside ${owner.chunkPrefix}: ${outsideOwner.join(', ')})`,
        )
      }
    }
  }
  if (ownershipViolations.size) {
    violations.push(
      `feature roots are assigned outside their subpackage (including feature-owned modules):\n  ${[...ownershipViolations].sort().join('\n  ')}`,
    )
  }

  if (enforcePhysicalAsyncBoundaries) {
    violations.push(...inspectPhysicalAsyncBoundaries(
      compilation,
      optimizedChunks,
      asyncPageBoundaries,
      allowedNodeModulePackages,
    ))
  }

  return violations
}

function sourceOwnerForResource(resource, owners = FEATURE_SOURCE_OWNERS) {
  return owners.find((owner) => owner.patterns.some((pattern) => pattern.test(resource)))
}

function projectSourceExactPattern(relativePath) {
  const normalized = String(relativePath).replace(/\\/g, '/').toLowerCase()
  const escaped = normalized.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`/src/${escaped}$`)
}

function inspectPhysicalAsyncBoundaries(
  compilation,
  optimizedChunks = compilation.chunks,
  boundaries = ASYNC_PAGE_BOUNDARIES,
  allowedNodeModulePackages = STARTUP_ALLOWED_NODE_MODULE_PACKAGES,
) {
  const violations = new Set()
  const projectRoot = compilationProjectRoot(compilation)
  const entrypoints = new Map(
    [...entrypointEntries(compilation.entrypoints)]
      .map(([name, entrypoint]) => [normalizeEntrypointName(name), entrypoint]),
  )
  const chunks = [...asIterable(optimizedChunks)]
  const registeredEntrypoints = new Set(boundaries.map((boundary) => boundary.entrypoint))

  for (const entrypointName of entrypoints.keys()) {
    if (
      FEATURE_SUBPACKAGE_PAGE_ENTRYPOINT.test(entrypointName)
      && !registeredEntrypoints.has(entrypointName)
    ) {
      violations.add(
        `unregistered physical async page entrypoint: ${entrypointName}`,
      )
    }
  }

  for (const boundary of boundaries) {
    const entrypoint = entrypoints.get(boundary.entrypoint)
    if (!entrypoint) {
      violations.add(`${boundary.label} -> missing page entrypoint ${boundary.entrypoint}`)
      continue
    }

    const initial = new Set(initialChunks(entrypoint))
    for (const chunk of initial) {
      for (const module of modulesInChunk(compilation, chunk)) {
        for (const unprovenanced of collectUnapprovedProvenanceFreeModules(module)) {
          violations.add(
            `${boundary.label} -> unapproved provenance-free initial module in ${chunkLabel(chunk)}: ${describeModule(unprovenanced)}`,
          )
        }
        for (const resource of collectModuleResources(module)) {
          const dependencyPackage = nodeModulePackage(resource)
          if (dependencyPackage) {
            if (!allowedNodeModulePackages.has(dependencyPackage)) {
              violations.add(
                `${boundary.label} -> unapproved initial dependency package ${dependencyPackage} in ${chunkLabel(chunk)}: ${resource}`,
              )
            }
            continue
          }
          if (
            isProjectSourceResource(resource, projectRoot)
            && !boundary.initialProjectPatterns?.some((pattern) => pattern.test(resource))
          ) {
            violations.add(
              `${boundary.label} -> unapproved initial project module in ${chunkLabel(chunk)}: ${resource}`,
            )
            continue
          }
          if (!isProjectSourceResource(resource, projectRoot)) {
            violations.add(
              `${boundary.label} -> unapproved initial external resource in ${chunkLabel(chunk)}: ${resource}`,
            )
          }
        }
      }
    }

    const asyncClosure = new Set(asyncChunks(entrypoint, initial))
    const targetChunks = chunks.filter((chunk) =>
      [...modulesInChunk(compilation, chunk)].some((module) =>
        [...collectModuleResources(module)].some((resource) =>
          boundary.targetPattern.test(resource))))
    if (!targetChunks.length) {
      violations.add(`${boundary.label} -> deferred target module was not emitted`)
    }

    for (const targetChunk of targetChunks) {
      if (initial.has(targetChunk) || chunkCanBeInitial(targetChunk)) {
        violations.add(
          `${boundary.label} -> deferred target is in an initial chunk: ${chunkLabel(targetChunk)}`,
        )
      }
      if (!asyncClosure.has(targetChunk)) {
        violations.add(
          `${boundary.label} -> deferred target is not reachable from its page async graph: ${chunkLabel(targetChunk)}`,
        )
      }
    }

    if (!assetExists(compilation, boundary.asyncAsset)) {
      violations.add(`${boundary.label} -> missing final async asset ${boundary.asyncAsset}`)
    }

    const declaredSecondaryAssets = new Set()
    for (const secondary of boundary.secondaryAsyncAssets ?? []) {
      declaredSecondaryAssets.add(secondary.asset)
      if (!assetExists(compilation, secondary.asset)) {
        violations.add(
          `${boundary.label} -> missing declared secondary async asset ${secondary.asset}`,
        )
      }
      const declaredChunks = chunks.filter((chunk) =>
        emittedExecutableFiles(chunk).includes(secondary.asset))
      if (!declaredChunks.length) {
        violations.add(
          `${boundary.label} -> declared secondary async chunk was not emitted: ${secondary.asset}`,
        )
      }
      for (const secondaryChunk of declaredChunks) {
        if (initial.has(secondaryChunk) || chunkCanBeInitial(secondaryChunk)) {
          violations.add(
            `${boundary.label} -> declared secondary async chunk is initial: ${secondary.asset}`,
          )
        }
        if (!asyncClosure.has(secondaryChunk)) {
          violations.add(
            `${boundary.label} -> declared secondary async chunk is not reachable after its page: ${secondary.asset}`,
          )
        }
        const containsTarget = [...modulesInChunk(compilation, secondaryChunk)].some(
          (module) => [...collectModuleResources(module)].some(
            (resource) => secondary.targetPattern.test(resource),
          ),
        )
        if (!containsTarget) {
          violations.add(
            `${boundary.label} -> declared secondary async chunk does not contain its target module: ${secondary.asset}`,
          )
        }
      }

      for (const candidate of chunks) {
        if (!asyncClosure.has(candidate)) continue
        const containsTarget = [...modulesInChunk(compilation, candidate)].some(
          (module) => [...collectModuleResources(module)].some(
            (resource) => secondary.targetPattern.test(resource),
          ),
        )
        if (
          containsTarget
          && !emittedExecutableFiles(candidate).includes(secondary.asset)
        ) {
          violations.add(
            `${boundary.label} -> secondary target leaked into an undeclared async chunk: ${chunkLabel(candidate)}`,
          )
        }
      }
    }

    for (const chunk of asyncClosure) {
      if (chunkCanBeInitial(chunk)) {
        violations.add(`${boundary.label} -> async closure contains initial chunk ${chunkLabel(chunk)}`)
      }
      const executableFiles = emittedExecutableFiles(chunk)
      if (!executableFiles.length) {
        violations.add(`${boundary.label} -> async chunk has no emitted JavaScript identity: ${chunkLabel(chunk)}`)
      }
      for (const file of executableFiles) {
        if (
          !isWithinChunkPrefix(file, boundary.asyncPrefix)
          && !declaredSecondaryAssets.has(file)
        ) {
          violations.add(
            `${boundary.label} -> async dependency escaped ${boundary.asyncPrefix}: ${file}`,
          )
        }
        if (!assetExists(compilation, file)) {
          violations.add(`${boundary.label} -> referenced async asset is missing: ${file}`)
        }
      }
      for (const styleFile of emittedStyleFiles(chunk)) {
        violations.add(`${boundary.label} -> async chunk emitted style asset ${styleFile}`)
      }
      for (const module of modulesInChunk(compilation, chunk)) {
        if (isCssModule(module)) {
          violations.add(
            `${boundary.label} -> async chunk contains CSS/mini-extract module: ${describeModuleWithResources(module)}`,
          )
        }
      }
    }
  }

  if (!violations.size) return []
  return [
    `physical async page boundaries are invalid:\n  ${[...violations].sort().join('\n  ')}`,
  ]
}

function asyncChunks(entrypoint, initial) {
  const entryChunk = typeof entrypoint?.getEntrypointChunk === 'function'
    ? entrypoint.getEntrypointChunk()
    : undefined
  if (typeof entryChunk?.getAllAsyncChunks === 'function') {
    return asIterable(entryChunk.getAllAsyncChunks())
  }
  if (typeof entrypoint?.getAllAsyncChunks === 'function') {
    return asIterable(entrypoint.getAllAsyncChunks())
  }
  const referenced = typeof entryChunk?.getAllReferencedChunks === 'function'
    ? asIterable(entryChunk.getAllReferencedChunks())
    : []
  return [...referenced].filter((chunk) => !initial.has(chunk))
}

function chunkCanBeInitial(chunk) {
  if (typeof chunk?.canBeInitial === 'function') return chunk.canBeInitial()
  return chunk?.initial === true
}

function emittedExecutableFiles(chunk) {
  const files = new Set()
  for (const file of [...asIterable(chunk?.files), ...asIterable(chunk?.auxiliaryFiles)]) {
    const normalized = normalizeWebpackPath(file)
    if (normalized && /\.(?:c|m)?js$/.test(normalized)) files.add(normalized)
  }
  return [...files]
}

function emittedStyleFiles(chunk) {
  const files = new Set()
  for (const file of [...asIterable(chunk?.files), ...asIterable(chunk?.auxiliaryFiles)]) {
    const normalized = normalizeWebpackPath(file)
    if (normalized && /\.(?:wxss|css|s?css|less|styl(?:us)?)$/.test(normalized)) {
      files.add(normalized)
    }
  }
  return [...files]
}

function assetExists(compilation, filename) {
  if (typeof compilation.getAsset === 'function') return Boolean(compilation.getAsset(filename))
  if (compilation.assets instanceof Map) return compilation.assets.has(filename)
  return Boolean(compilation.assets && Object.prototype.hasOwnProperty.call(compilation.assets, filename))
}

function isCssModule(module) {
  if (typeof module?.type === 'string' && /css|mini-extract/i.test(module.type)) return true
  return [...collectModuleResources(module)].some((resource) =>
    /\.(?:wxss|css|s?css|less|styl(?:us)?)$/.test(resource))
}

function describeModuleWithResources(module) {
  const resources = [...collectModuleResources(module)]
  return resources.length ? resources.join(', ') : describeModule(module)
}

function collectStartupChunks(compilation) {
  const chunks = new Set()
  const roles = new Set()
  const availableEntrypoints = []
  for (const [rawName, entrypoint] of entrypointEntries(compilation.entrypoints)) {
    const name = normalizeEntrypointName(rawName)
    availableEntrypoints.push(name || String(rawName))
    const role = startupEntrypointRole(name)
    if (!role) continue
    roles.add(role)
    for (const chunk of initialChunks(entrypoint)) chunks.add(chunk)
  }
  return {
    chunks,
    availableEntrypoints: availableEntrypoints.sort(),
    missingRoles: ['app', 'home'].filter((role) => !roles.has(role)),
  }
}

function entrypointEntries(entrypoints) {
  if (!entrypoints) return []
  if (typeof entrypoints.entries === 'function') return entrypoints.entries()
  return Object.entries(entrypoints)
}

function initialChunks(entrypoint) {
  if (!entrypoint) return []
  if (typeof entrypoint.getAllInitialChunks === 'function') {
    return asIterable(entrypoint.getAllInitialChunks())
  }
  if (typeof entrypoint.getEntrypointChunk === 'function') {
    const entryChunk = entrypoint.getEntrypointChunk()
    if (typeof entryChunk?.getAllInitialChunks === 'function') {
      return asIterable(entryChunk.getAllInitialChunks())
    }
  }
  return asIterable(entrypoint.chunks)
}

function modulesInChunk(compilation, chunk) {
  if (compilation.chunkGraph?.getChunkModulesIterable) {
    return asIterable(compilation.chunkGraph.getChunkModulesIterable(chunk))
  }
  if (chunk?.modulesIterable) return asIterable(chunk.modulesIterable)
  if (chunk?.modules) return asIterable(chunk.modules)
  if (typeof chunk?.getModules === 'function') return asIterable(chunk.getModules())
  return []
}

function collectModuleResources(module, resources = new Set(), seen = new Set()) {
  if (!module || typeof module !== 'object' || seen.has(module)) return resources
  seen.add(module)

  addNormalizedResource(resources, module.resource)
  if (typeof module.nameForCondition === 'function') {
    try {
      addNormalizedResource(resources, module.nameForCondition())
    } catch {
      // Some Webpack runtime modules deliberately do not expose a condition name.
    }
  }

  if (module.rootModule && module.rootModule !== module) {
    collectModuleResources(module.rootModule, resources, seen)
  }
  for (const nested of asIterable(module.modules)) {
    collectModuleResources(nested, resources, seen)
  }
  return resources
}

function collectUnapprovedProvenanceFreeModules(
  module,
  unapproved = new Set(),
  seen = new Set(),
) {
  if (!module || typeof module !== 'object' || seen.has(module)) return unapproved
  seen.add(module)

  if (!hasDirectModuleProvenance(module) && !isAllowedWebpackRuntimeModule(module)) {
    unapproved.add(module)
  }
  if (module.rootModule && module.rootModule !== module) {
    collectUnapprovedProvenanceFreeModules(module.rootModule, unapproved, seen)
  }
  for (const nested of asIterable(module.modules)) {
    collectUnapprovedProvenanceFreeModules(nested, unapproved, seen)
  }
  return unapproved
}

function hasDirectModuleProvenance(module) {
  if (normalizeWebpackPath(module.resource)) return true
  if (typeof module.nameForCondition !== 'function') return false
  try {
    return Boolean(normalizeWebpackPath(module.nameForCondition()))
  } catch {
    return false
  }
}

function isAllowedWebpackRuntimeModule(module) {
  const constructorName = module.constructor?.name
  return module.type === 'runtime'
    && typeof constructorName === 'string'
    && constructorName.endsWith('RuntimeModule')
}

function describeModule(module) {
  const constructorName = module.constructor?.name || '(unknown constructor)'
  const type = typeof module.type === 'string' && module.type ? module.type : 'unknown'
  let identifier = ''
  if (typeof module.identifier === 'function') {
    try {
      const value = module.identifier()
      if (typeof value === 'string' && value.trim()) identifier = value.trim()
    } catch {
      // The class/type still provides a stable diagnostic when identifier fails.
    }
  }
  const identifierSuffix = identifier
    ? `, identifier ${identifier.slice(0, 240)}`
    : ''
  return `${constructorName} (type ${type}${identifierSuffix})`
}

function addNormalizedResource(resources, value) {
  const normalized = normalizeWebpackPath(value)
  if (normalized) resources.add(normalized)
}

function normalizeWebpackPath(value) {
  if (typeof value !== 'string') return undefined
  let normalized = value.trim()
  if (!normalized) return undefined
  const loaderSeparator = normalized.lastIndexOf('!')
  if (loaderSeparator >= 0) normalized = normalized.slice(loaderSeparator + 1)
  const queryIndex = normalized.indexOf('?')
  if (queryIndex >= 0) normalized = normalized.slice(0, queryIndex)
  normalized = normalized
    .replace(/^file:\/\//i, '')
    .replace(/\\/g, '/')
    .replace(/\/+/g, '/')
    .replace(/^\/?([a-z]):\//i, '$1:/')
    .toLowerCase()
  return normalized || undefined
}

function normalizeEntrypointName(value) {
  const normalized = normalizeWebpackPath(String(value ?? '')) ?? ''
  return normalized.replace(/\.[cm]?[jt]sx?$/, '')
}

function compilationProjectRoot(compilation) {
  return normalizeWebpackPath(
    compilation.compiler?.context ?? compilation.options?.context,
  )
}

function isProjectSourceResource(resource, projectRoot) {
  if (!projectRoot || resource.includes('/node_modules/')) return false
  return resource.startsWith(`${projectRoot}/src/`)
}

function nodeModulePackage(resource) {
  const marker = '/node_modules/'
  const markerIndex = resource.lastIndexOf(marker)
  if (markerIndex < 0) return undefined
  const packagePath = resource.slice(markerIndex + marker.length)
  const segments = packagePath.split('/').filter(Boolean)
  if (!segments.length) return undefined
  if (segments[0].startsWith('@')) {
    return segments.length >= 2 ? `${segments[0]}/${segments[1]}` : undefined
  }
  return segments[0]
}

function startupEntrypointRole(name) {
  if (name === 'app' || name.endsWith('/app')) return 'app'
  if (
    name === 'presentation/pages/home/index'
    || name.endsWith('/presentation/pages/home/index')
  ) return 'home'
  return undefined
}

function chunkIdentities(chunk) {
  const identities = new Set()
  addChunkIdentity(identities, chunk?.name)
  if (typeof chunk?.id === 'string') addChunkIdentity(identities, chunk.id)
  for (const file of asIterable(chunk?.files)) addChunkIdentity(identities, file)
  for (const file of asIterable(chunk?.auxiliaryFiles)) addChunkIdentity(identities, file)
  return [...identities]
}

function executableChunkIdentities(chunk) {
  const identities = new Set()
  addExecutableChunkIdentity(identities, chunk?.name, true)
  if (typeof chunk?.id === 'string') addExecutableChunkIdentity(identities, chunk.id, true)
  for (const file of asIterable(chunk?.files)) {
    addExecutableChunkIdentity(identities, file, false)
  }
  for (const file of asIterable(chunk?.auxiliaryFiles)) {
    addExecutableChunkIdentity(identities, file, false)
  }
  return [...identities]
}

function addExecutableChunkIdentity(identities, value, allowEntrypointName) {
  const normalized = normalizeWebpackPath(value)
  if (!normalized) return
  if (/\.(?:c|m)?js(?:\.map)?$/.test(normalized)) {
    identities.add(normalized)
    return
  }
  if (!allowEntrypointName) return
  if (
    normalized.startsWith('subpackages/')
    || normalized.includes('/subpackages/')
    || startupEntrypointRole(normalizeEntrypointName(normalized))
  ) {
    identities.add(normalized)
  }
}

function addChunkIdentity(identities, value) {
  const normalized = normalizeWebpackPath(value)
  if (normalized) identities.add(normalized)
}

function isWithinChunkPrefix(identity, prefix) {
  const normalizedPrefix = normalizeWebpackPath(prefix)
  if (!normalizedPrefix) return false
  return identity.startsWith(normalizedPrefix)
    || identity.includes(`/${normalizedPrefix}`)
}

function chunkLabel(chunk) {
  const identities = chunkIdentities(chunk)
  return identities[0] ?? '(unnamed chunk)'
}

function asIterable(value) {
  if (!value || typeof value[Symbol.iterator] !== 'function') return []
  return value
}

module.exports = StartupBoundaryWebpackPlugin
module.exports.inspectCompilation = inspectCompilation
module.exports.normalizeWebpackPath = normalizeWebpackPath
module.exports.collectModuleResources = collectModuleResources
