import { createRequire } from 'node:module'
import { readFile, readdir } from 'node:fs/promises'
import { dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const require = createRequire(import.meta.url)
const { parseSync } = require('@babel/core')
const {
  NATIVE_FIRST_PAINT_DIAGNOSTIC,
  NATIVE_FIRST_PAINT_MARKER,
  registeredPageWxmlAssets,
} = require('./weapp-native-first-paint-assets.cjs')

export const PHYSICAL_ASYNC_BOUNDARIES = Object.freeze([
  { owner: 'startup', page: 'home', chunk: 'startup-application' },
  { owner: 'planning', page: 'onboarding', chunk: 'onboarding' },
  { owner: 'planning', page: 'plan-candidates', chunk: 'plan-candidates' },
  { owner: 'planning', page: 'plan-presets', chunk: 'plan-presets' },
  { owner: 'planning', page: 'plan', chunk: 'plan' },
  { owner: 'planning', page: 'plan-editor', chunk: 'plan-editor' },
  { owner: 'workout', page: 'workout-prepare', chunk: 'workout-prepare' },
  {
    owner: 'workout',
    page: 'workout-session',
    chunk: 'workout-session',
    secondaryChunks: Object.freeze([
      Object.freeze({ owner: 'exercise-guide', chunk: 'detail' }),
    ]),
  },
  { owner: 'workout', page: 'workout-summary', chunk: 'workout-summary' },
  { owner: 'progress', page: 'sync-conflicts', chunk: 'sync-conflicts' },
  { owner: 'progress', page: 'history', chunk: 'history' },
  { owner: 'progress', page: 'exercise-trend', chunk: 'exercise-trend' },
  { owner: 'account', page: 'my', chunk: 'my' },
  { owner: 'account', page: 'exercise-preferences', chunk: 'exercise-preferences' },
  { owner: 'exercise-guide', page: 'detail', chunk: 'detail' },
])

export const PHYSICAL_ASYNC_SUBPACKAGES = Object.freeze([
  'startup',
  'planning',
  'workout',
  'progress',
  'account',
  'exercise-guide',
])

const RECOVERY_MAIN_PAGE = 'presentation/pages/home/index'
const ASYNC_CHUNK_ENVELOPE_MARKER = '__fitnessWebpackChunkEnvelope'

const RUNTIME_REQUIREMENTS = Object.freeze([
  ['require.async loader', /\brequire\.async\s*\(/],
  ['cross-context chunk envelope validator', /Invalid WeChat async chunk registration envelope/],
  ['cross-context chunk envelope marker', /__fitnessWebpackChunkEnvelope/],
  ['webpackJsonp callback queue', /\bwebpackJsonp\b/],
  ['onChunksLoaded function', /\.O\s*=\s*function\b/],
  ['onChunksLoaded chunk predicate', /\.O\.j\s*=\s*function\b/],
  ['JSONP chunk completion scan', /\.some\(\s*function\b/],
  ['JSONP return through onChunksLoaded', /\breturn\s+[$A-Z_a-z][$\w]*\.O\s*\(/],
  ['WeChat runtime global', /\.g\s*=\s*wx\b/],
])

const FORBIDDEN_RUNTIME_PATTERNS = Object.freeze([
  ['Function constructor', /\b(?:new\s+)?Function\s*\(/],
  ['eval', /(?:^|[^$\w])eval\s*\(/],
  ['document', /\bdocument\b/],
  ['fetch', /(?:^|[^$\w])fetch\s*\(/],
  ['XMLHttpRequest', /\bXMLHttpRequest\b/],
  ['wx.request', /\bwx\.request\b/],
])

const FORBIDDEN_GENERATED_JAVASCRIPT_PATTERNS = Object.freeze([
  ['Function constructor', /\b(?:new\s+)?Function\s*\(/],
  ['eval', /(?:^|[^$\w])eval\s*\(/],
  ['string timer', /\bset(?:Interval|Timeout)\s*\(\s*['"`]/],
  ['browser URL constructor', /\bnew\s+URL\s*\(/],
  ['runtime Unicode property escape', /\\\\p\{/],
])

const MAX_GENERATED_REGEXP_PATTERN_LENGTH = 4096

const JAVASCRIPT_EXTENSION = /\.(?:cjs|js|mjs)$/i
const ASYNC_STYLE_EXTENSION = /\.(?:css|less|sass|scss|styl|stylus|wxss)$/i
const STATIC_RESOURCE_SOURCE_EXTENSION = /\.(?:cjs|css|js|mjs|wxss)$/i
const STATIC_RESOURCE_EXTENSION = /\.(?:aac|apng|avif|bmp|eot|flac|gif|ico|jpe?g|m4a|mov|mp3|mp4|oga|ogg|ogv|opus|otf|png|svg|ttf|wav|webm|webp|woff2?)(?:[?#].*)?$/i
const QUOTED_RESOURCE = /(["'`])([^"'`\r\n]+)\1/g
const CSS_RESOURCE = /\burl\(\s*(["']?)([^"')\r\n]+)\1\s*\)/gi
const STATIC_REQUIRE = /\brequire\s*\(\s*(['"])([^'"\r\n]+)\1\s*\)/g
const REQUIRE_ASYNC_CALL = /\brequire\.async\s*\(\s*([^)]+?)\s*\)/g
const ENSURE_CHUNK = /\b(?:__webpack_require__|[$A-Z_a-z][$\w]*)\.e\(\s*(['"]?)([$\w-]+)\1\s*\)\.then\s*\(/g
const SYNC_REQUIRE_IN_PROMISE_CALLBACK = /Promise\.resolve\(\s*\)\.then\(\s*function(?:\s+[$A-Z_a-z][$\w]*)?\s*\([^)]*\)\s*\{[\s\S]{0,2048}?(?:\brequire\s*\(|\b__webpack_require__\s*\(|\breturn\s+[$A-Z_a-z][$\w]*\s*\(\s*\d+\s*\))/

function unixPath(path) {
  return path.replaceAll('\\', '/')
}

function isOutside(directory, target) {
  const path = relative(directory, target)
  return path === '..' || path.startsWith(`..${sep}`) || isAbsolute(path)
}

function expectedPaths(boundary) {
  const root = `subpackages/${boundary.owner}`
  return {
    root,
    pageEntry: `pages/${boundary.page}/index`,
    shell: `${root}/pages/${boundary.page}/index.js`,
    template: `${root}/pages/${boundary.page}/index.wxml`,
    style: `${root}/pages/${boundary.page}/index.wxss`,
    asyncModule: `${root}/async/${boundary.chunk}`,
    asyncFile: `${root}/async/${boundary.chunk}.js`,
  }
}

function expectedSecondaryPath(secondary) {
  return `subpackages/${secondary.owner}/async/${secondary.chunk}.js`
}

function expectedNativeAsyncChunkFiles() {
  const files = new Set()
  for (const boundary of PHYSICAL_ASYNC_BOUNDARIES) {
    files.add(expectedPaths(boundary).asyncFile)
    for (const secondary of boundary.secondaryChunks ?? []) {
      files.add(expectedSecondaryPath(secondary))
    }
  }
  return files
}

async function inventory(directory) {
  const files = new Map()

  async function visit(currentDirectory) {
    const entries = await readdir(currentDirectory, { withFileTypes: true })
    await Promise.all(entries.map(async (entry) => {
      const absolutePath = resolve(currentDirectory, entry.name)
      if (entry.isDirectory()) {
        await visit(absolutePath)
      } else if (entry.isFile()) {
        files.set(unixPath(relative(directory, absolutePath)), absolutePath)
      }
    }))
  }

  await visit(directory)
  return files
}

async function readRequiredFile(files, relativePath, errors, label) {
  const absolutePath = files.get(relativePath)
  if (!absolutePath) {
    errors.push(`${label} is missing: ${relativePath}`)
    return undefined
  }
  try {
    return await readFile(absolutePath, 'utf8')
  } catch (error) {
    errors.push(`${label} cannot be read: ${relativePath} (${error.message})`)
    return undefined
  }
}

function parseAppConfig(source, errors) {
  if (source === undefined) return undefined
  try {
    const config = JSON.parse(source)
    if (!config || typeof config !== 'object' || Array.isArray(config)) {
      errors.push('app.json must contain a JSON object')
      return undefined
    }
    return config
  } catch (error) {
    errors.push(`app.json is not valid JSON (${error.message})`)
    return undefined
  }
}

function validateSubpackages(appConfig, errors) {
  if (!appConfig) return new Map()
  if (appConfig.subPackages !== undefined && appConfig.subpackages !== undefined) {
    errors.push('app.json must not define both subPackages and subpackages')
  }
  const subpackages = appConfig.subPackages ?? appConfig.subpackages
  if (!Array.isArray(subpackages)) {
    errors.push('app.json must define a subPackages array')
    return new Map()
  }

  const byRoot = new Map()
  for (const subpackage of subpackages) {
    if (!subpackage || typeof subpackage !== 'object' || Array.isArray(subpackage)) {
      errors.push('app.json contains an invalid subpackage entry')
      continue
    }
    const root = subpackage.root
    if (typeof root !== 'string' || !root) {
      errors.push('app.json contains a subpackage without a root')
      continue
    }
    if (byRoot.has(root)) {
      errors.push(`app.json contains duplicate subpackage root: ${root}`)
      continue
    }
    byRoot.set(root, subpackage)
  }

  const expectedRoots = new Set(
    PHYSICAL_ASYNC_SUBPACKAGES.map((owner) => `subpackages/${owner}`),
  )
  for (const root of byRoot.keys()) {
    if (!expectedRoots.has(root)) {
      errors.push(`subpackage root has no physical async topology: ${root}`)
    }
  }

  for (const owner of PHYSICAL_ASYNC_SUBPACKAGES) {
    const root = `subpackages/${owner}`
    const subpackage = byRoot.get(root)
    if (!subpackage) {
      errors.push(`required non-independent subpackage is missing: ${root}`)
      continue
    }
    if (subpackage.name !== owner) {
      errors.push(`subpackage name must match its native loader owner: ${root} -> ${String(subpackage.name)}`)
    }
    if ('independent' in subpackage && subpackage.independent !== false) {
      errors.push(`subpackage must be non-independent: ${root}`)
    }
    if (!Array.isArray(subpackage.pages)) {
      errors.push(`subpackage pages must be an array: ${root}`)
    }
  }
  return byRoot
}

function validateInitialPages(appConfig, subpackages, errors) {
  if (!appConfig) return
  const initialPaths = []
  if (Array.isArray(appConfig.pages)) {
    initialPaths.push(...appConfig.pages.filter((page) => typeof page === 'string'))
  }
  for (const [root, subpackage] of subpackages) {
    if (!Array.isArray(subpackage.pages)) continue
    for (const page of subpackage.pages) {
      if (typeof page === 'string') initialPaths.push(`${root}/${page}`)
    }
  }

  for (const path of initialPaths) {
    if (/(?:^|\/)async(?:\/|$)/.test(path)) {
      errors.push(`async chunk must not be declared as an initial page: ${path}`)
    }
  }
}

function validateRegisteredPageTopology(appConfig, subpackages, errors) {
  if (!appConfig) return
  if (!Array.isArray(appConfig.pages)) {
    errors.push('app.json pages must be an array')
  } else {
    const seen = new Set()
    for (const page of appConfig.pages) {
      if (typeof page !== 'string') {
        errors.push('app.json contains a non-string main page')
        continue
      }
      if (seen.has(page)) errors.push(`app.json contains duplicate main page: ${page}`)
      seen.add(page)
      if (page !== RECOVERY_MAIN_PAGE) {
        errors.push(`main page has no startup boundary: ${page}`)
      }
    }
    if (!seen.has(RECOVERY_MAIN_PAGE)) {
      errors.push(`required main recovery page is missing: ${RECOVERY_MAIN_PAGE}`)
    }
  }

  for (const owner of PHYSICAL_ASYNC_SUBPACKAGES) {
    const root = `subpackages/${owner}`
    const subpackage = subpackages.get(root)
    if (!subpackage || !Array.isArray(subpackage.pages)) continue
    const expected = new Set(
      PHYSICAL_ASYNC_BOUNDARIES
        .filter((boundary) => boundary.owner === owner)
        .map((boundary) => `pages/${boundary.page}/index`),
    )
    const seen = new Set()
    for (const page of subpackage.pages) {
      if (typeof page !== 'string') {
        errors.push(`subpackage contains a non-string page: ${root}`)
        continue
      }
      if (seen.has(page)) errors.push(`subpackage contains duplicate page: ${root}/${page}`)
      seen.add(page)
      if (!expected.has(page)) {
        errors.push(`subpackage page has no physical async boundary: ${root}/${page}`)
      }
    }
  }
}

function validateRuntime(runtime, errors) {
  if (runtime === undefined) return
  for (const [label, pattern] of RUNTIME_REQUIREMENTS) {
    if (!pattern.test(runtime)) errors.push(`runtime is missing ${label}`)
  }
  for (const [label, pattern] of FORBIDDEN_RUNTIME_PATTERNS) {
    if (pattern.test(runtime)) errors.push(`runtime contains forbidden ${label}`)
  }

  const expected = expectedNativeAsyncChunkFiles()
  const actual = new Set()
  for (const match of runtime.matchAll(REQUIRE_ASYNC_CALL)) {
    const argument = match[1].trim()
    const literal = /^(['"])([^'"\\\r\n]+)\1$/.exec(argument)
    if (!literal) {
      errors.push(`runtime require.async must use one static string literal, found: ${argument}`)
      continue
    }
    const path = literal[2]
    if (!path.startsWith('./')) {
      errors.push(`runtime require.async path must be relative to runtime.js: ${path}`)
      continue
    }
    actual.add(path.slice(2))
  }
  for (const path of expected) {
    if (!actual.has(path)) {
      errors.push(`runtime is missing static require.async mapping: ${path}`)
    }
  }
  for (const path of actual) {
    if (!expected.has(path)) {
      errors.push(`runtime contains unexpected static require.async mapping: ${path}`)
    }
  }
}

function ensureChunkIds(shell) {
  const ids = []
  for (const match of shell.matchAll(ENSURE_CHUNK)) ids.push(match[2])
  return [...new Set(ids)]
}

function validateNativeAsyncChunkEnvelope(source, path, errors) {
  if (!source.includes(ASYNC_CHUNK_ENVELOPE_MARKER)) {
    errors.push(`physical async chunk is missing the cross-context registration envelope: ${path}`)
  }
  if (!/\bmodule\.exports\s*=/.test(source)) {
    errors.push(`physical async chunk does not export its registration envelope: ${path}`)
  }
  if (/\bwebpackJsonp\b[\s\S]{0,128}?\.push\s*\(/.test(source)) {
    errors.push(`physical async chunk still depends on a cross-context webpackJsonp side effect: ${path}`)
  }
}

function runtimeMapsChunk(runtime, chunkId, asyncModule) {
  const id = chunkId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const module = asyncModule.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(
    `(?:\\{|,)\\s*(?:["']${id}["']|${id})\\s*:\\s*["']${module}["']`,
  ).test(runtime)
}

async function validateBoundaries(files, appConfig, subpackages, runtime, errors) {
  for (const boundary of PHYSICAL_ASYNC_BOUNDARIES) {
    const paths = expectedPaths(boundary)
    const subpackage = subpackages.get(paths.root)
    if (subpackage && Array.isArray(subpackage.pages) && !subpackage.pages.includes(paths.pageEntry)) {
      errors.push(`mapped page is missing from app.json: ${paths.root}/${paths.pageEntry}`)
    }

    const shell = await readRequiredFile(files, paths.shell, errors, 'page shell')
    if (shell !== undefined) {
      if (shell.length === 0) errors.push(`page shell is empty: ${paths.shell}`)
      const chunkIds = ensureChunkIds(shell)
      if (chunkIds.length === 0) {
        errors.push(`page shell does not call Webpack ensureChunk: ${paths.shell}`)
      }
      if (chunkIds.length > 1) {
        errors.push(`page shell calls more than one physical async chunk: ${paths.shell}`)
      }
      if (SYNC_REQUIRE_IN_PROMISE_CALLBACK.test(shell)) {
        errors.push(`page shell contains dynamic-import-node synchronous require fallback: ${paths.shell}`)
      }
      if (
        runtime !== undefined
        && chunkIds.length > 0
        && !chunkIds.some((chunkId) => runtimeMapsChunk(runtime, chunkId, paths.asyncModule))
      ) {
        errors.push(`page shell ensureChunk is not mapped to ${paths.asyncModule}: ${paths.shell}`)
      }
    }

    const asyncSource = await readRequiredFile(files, paths.asyncFile, errors, 'physical async chunk')
    if (asyncSource !== undefined && asyncSource.length === 0) {
      errors.push(`physical async chunk is empty: ${paths.asyncFile}`)
    }
    if (asyncSource !== undefined) {
      validateNativeAsyncChunkEnvelope(asyncSource, paths.asyncFile, errors)
      const expectedSecondaryChunks = boundary.secondaryChunks ?? []
      const secondaryChunkIds = ensureChunkIds(asyncSource)
      if (secondaryChunkIds.length !== expectedSecondaryChunks.length) {
        errors.push(
          `physical async chunk has ${secondaryChunkIds.length} secondary ensureChunk call(s), `
          + `expected ${expectedSecondaryChunks.length}: ${paths.asyncFile}`,
        )
      }
      for (const secondary of expectedSecondaryChunks) {
        const secondaryPath = expectedSecondaryPath(secondary)
        const secondarySource = await readRequiredFile(
          files,
          secondaryPath,
          errors,
          'declared secondary async chunk',
        )
        if (secondarySource !== undefined && secondarySource.length === 0) {
          errors.push(`declared secondary async chunk is empty: ${secondaryPath}`)
        }
        if (secondarySource !== undefined) {
          validateNativeAsyncChunkEnvelope(secondarySource, secondaryPath, errors)
        }
        if (
          runtime !== undefined
          && !secondaryChunkIds.some((chunkId) => (
            runtimeMapsChunk(runtime, chunkId, secondaryPath.slice(0, -3))
          ))
        ) {
          errors.push(
            `physical async chunk secondary ensureChunk is not mapped to ${secondaryPath.slice(0, -3)}: `
            + paths.asyncFile,
          )
        }
      }
    }
    if (!files.has(paths.style)) errors.push(`page wxss is missing: ${paths.style}`)
  }
}

async function validateNativeFirstPaint(files, appConfig, errors) {
  if (!appConfig) return
  let templates
  try {
    templates = registeredPageWxmlAssets(appConfig)
  } catch (error) {
    errors.push(`native first-paint topology is invalid (${error.message})`)
    return
  }
  const appStyle = await readRequiredFile(files, 'app.wxss', errors, 'global app style')
  if (appStyle !== undefined && !appStyle.includes(NATIVE_FIRST_PAINT_MARKER)) {
    errors.push('global app style is missing native first-paint protection')
  }
  for (const template of templates) {
    const source = await readRequiredFile(files, template, errors, 'registered page template')
    if (source === undefined) continue
    if (!source.includes(NATIVE_FIRST_PAINT_MARKER)) {
      errors.push(`registered page is missing native first-paint protection: ${template}`)
    }
    if (!source.includes(NATIVE_FIRST_PAINT_DIAGNOSTIC)) {
      errors.push(`registered page is missing native first-paint diagnostic: ${template}`)
    }
  }
}

function validateAsyncStyles(files, errors) {
  for (const path of files.keys()) {
    if (/(?:^|\/)async\//.test(path) && ASYNC_STYLE_EXTENSION.test(path)) {
      errors.push(`async directory must not contain styles: ${path}`)
    }
  }
}

function staticRequireCandidates(basePath) {
  if (extname(basePath)) return [basePath]
  return [
    basePath,
    `${basePath}.js`,
    `${basePath}.cjs`,
    `${basePath}.mjs`,
    `${basePath}.json`,
    resolve(basePath, 'index.js'),
    resolve(basePath, 'index.cjs'),
    resolve(basePath, 'index.mjs'),
    resolve(basePath, 'index.json'),
  ]
}

async function validateStaticRequires(distDirectory, files, errors) {
  const absoluteFiles = new Set([...files.values()].map((path) => resolve(path)))
  const javascriptFiles = [...files.entries()]
    .filter(([path]) => JAVASCRIPT_EXTENSION.test(path))

  for (const [relativePath, absolutePath] of javascriptFiles) {
    let source
    try {
      source = await readFile(absolutePath, 'utf8')
    } catch (error) {
      errors.push(`JavaScript artifact cannot be read: ${relativePath} (${error.message})`)
      continue
    }
    for (const match of source.matchAll(STATIC_REQUIRE)) {
      const request = match[2]
      if (!request.startsWith('./') && !request.startsWith('../')) continue
      const basePath = resolve(dirname(absolutePath), request)
      if (isOutside(distDirectory, basePath)) {
        errors.push(`relative static require escapes dist: ${relativePath} -> ${request}`)
        continue
      }
      const candidates = staticRequireCandidates(basePath)
        .filter((candidate) => !isOutside(distDirectory, candidate))
      if (!candidates.some((candidate) => absoluteFiles.has(resolve(candidate)))) {
        errors.push(`relative static require target is missing: ${relativePath} -> ${request}`)
      }
    }
  }
}

async function validateGeneratedJavaScript(files, errors) {
  const javascriptFiles = [...files.entries()]
    .filter(([path]) => JAVASCRIPT_EXTENSION.test(path))

  for (const [relativePath, absolutePath] of javascriptFiles) {
    let source
    try {
      source = await readFile(absolutePath, 'utf8')
    } catch (error) {
      errors.push(`JavaScript artifact cannot be read: ${relativePath} (${error.message})`)
      continue
    }
    if (relativePath !== 'runtime.js') {
      for (const [label, pattern] of FORBIDDEN_GENERATED_JAVASCRIPT_PATTERNS) {
        if (pattern.test(source)) {
          errors.push(`JavaScript artifact contains forbidden ${label}: ${relativePath}`)
        }
      }
    }
    validateGeneratedJavaScriptAst(source, absolutePath, relativePath, errors)
  }
}

function validateGeneratedJavaScriptAst(source, absolutePath, relativePath, errors) {
  let ast
  try {
    ast = parseSync(source, {
      babelrc: false,
      configFile: false,
      filename: absolutePath,
      parserOpts: { plugins: ['jsx'] },
      sourceType: 'unambiguous',
    })
  } catch (error) {
    errors.push(`JavaScript artifact cannot be parsed: ${relativePath} (${error.message})`)
    return
  }

  let containsGlobalThis = false
  let largestRegExpPatternLength = 0
  const unsupportedRegExpFeatures = new Set()
  const unsupportedSyntax = new Set()
  const pending = [ast]
  while (pending.length > 0) {
    const node = pending.pop()
    if (!node || typeof node !== 'object') continue
    if (node.type === 'Identifier' && node.name === 'globalThis') {
      containsGlobalThis = true
    }
    if (node.type === 'RegExpLiteral') {
      largestRegExpPatternLength = Math.max(
        largestRegExpPatternLength,
        node.pattern.length,
      )
      for (const flag of node.flags) {
        if (!'gim'.includes(flag)) unsupportedRegExpFeatures.add(`flag ${flag}`)
      }
      if (/\(\?<=[\s\S]|\(\?<![\s\S]/.test(node.pattern)) {
        unsupportedRegExpFeatures.add('lookbehind')
      }
      if (/\(\?<[$A-Z_a-z][$\w]*>/.test(node.pattern) || /\\k<[$A-Z_a-z][$\w]*>/.test(node.pattern)) {
        unsupportedRegExpFeatures.add('named capture group')
      }
    }
    const syntaxLabel = unsupportedAndroidSyntaxLabel(node)
    if (syntaxLabel) unsupportedSyntax.add(syntaxLabel)
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc' || key === 'extra') continue
      if (Array.isArray(value)) pending.push(...value)
      else if (value && typeof value === 'object') pending.push(value)
    }
  }

  if (containsGlobalThis) {
    errors.push(`JavaScript artifact contains forbidden globalThis: ${relativePath}`)
  }
  if (largestRegExpPatternLength > MAX_GENERATED_REGEXP_PATTERN_LENGTH) {
    errors.push(
      `JavaScript artifact contains oversized RegExp literal (`
      + `${largestRegExpPatternLength} > ${MAX_GENERATED_REGEXP_PATTERN_LENGTH}): ${relativePath}`,
    )
  }
  for (const feature of [...unsupportedRegExpFeatures].sort()) {
    errors.push(
      `JavaScript artifact contains unsupported Android RegExp ${feature}: ${relativePath}`,
    )
  }
  for (const syntax of [...unsupportedSyntax].sort()) {
    errors.push(
      `JavaScript artifact contains unsupported Android syntax ${syntax}: ${relativePath}`,
    )
  }
}

function unsupportedAndroidSyntaxLabel(node) {
  if (node.type === 'ArrowFunctionExpression') return 'arrow function'
  if (node.type === 'OptionalMemberExpression' || node.type === 'OptionalCallExpression') {
    return 'optional chaining'
  }
  if (node.type === 'ClassDeclaration' || node.type === 'ClassExpression') return 'class'
  if (node.type === 'TemplateLiteral' || node.type === 'TaggedTemplateExpression') {
    return 'template literal'
  }
  if (node.type === 'ForOfStatement') return 'for-of loop'
  if (node.type === 'SpreadElement') return 'spread element'
  if (node.type === 'RestElement') return 'rest element'
  if (node.type === 'AssignmentPattern') return 'default parameter or destructuring value'
  if (node.type === 'ObjectPattern' || node.type === 'ArrayPattern') return 'destructuring'
  if (node.type === 'BigIntLiteral' || node.type === 'DecimalLiteral') return 'extended numeric literal'
  if (node.type === 'PrivateName' || node.type === 'StaticBlock') return 'private or static class element'
  if (node.type === 'ImportExpression' || node.type === 'MetaProperty') return 'dynamic import or meta property'
  if (
    (node.type === 'FunctionDeclaration'
      || node.type === 'FunctionExpression'
      || node.type === 'ObjectMethod')
    && (node.async || node.generator)
  ) {
    return 'async or generator function'
  }
  if (node.type === 'ObjectMethod' && node.kind === 'method') return 'object method shorthand'
  if (node.type === 'ObjectProperty' && (node.computed || node.shorthand)) {
    return 'computed or shorthand object property'
  }
  if (node.type === 'LogicalExpression' && node.operator === '??') {
    return 'nullish coalescing'
  }
  if (node.type === 'BinaryExpression' && node.operator === '**') {
    return 'exponentiation operator'
  }
  if (
    node.type === 'AssignmentExpression'
    && ['&&=', '||=', '??=', '**='].includes(node.operator)
  ) {
    return 'modern assignment operator'
  }
  return undefined
}

function artifactOwner(relativePath) {
  return relativePath.match(/^subpackages\/([^/]+)\//)?.[1]
}

function staticResourceRequests(source) {
  const requests = new Set()
  for (const match of source.matchAll(QUOTED_RESOURCE)) {
    if (STATIC_RESOURCE_EXTENSION.test(match[2])) requests.add(match[2])
  }
  for (const match of source.matchAll(CSS_RESOURCE)) {
    if (STATIC_RESOURCE_EXTENSION.test(match[2])) requests.add(match[2])
  }
  return requests
}

function resolveStaticResourceRequest(distDirectory, sourcePath, request) {
  const normalized = request.trim().replaceAll('\\', '/').replace(/[?#].*$/, '')
  if (!normalized || normalized.startsWith('#') || normalized.startsWith('//')) return undefined
  if (/^[a-z][a-z0-9+.-]*:/i.test(normalized)) return undefined
  const sourceDirectory = dirname(resolve(distDirectory, ...sourcePath.split('/')))
  const absoluteTarget = normalized.startsWith('/')
    ? resolve(distDirectory, normalized.slice(1))
    : normalized.startsWith('subpackages/')
      ? resolve(distDirectory, normalized)
      : resolve(sourceDirectory, normalized)
  if (isOutside(distDirectory, absoluteTarget)) return { escaped: true, request }
  return {
    escaped: false,
    request,
    relativePath: unixPath(relative(distDirectory, absoluteTarget)),
  }
}

async function validateStaticResourceOwnership(distDirectory, files, errors) {
  const sources = [...files.entries()]
    .filter(([path]) => STATIC_RESOURCE_SOURCE_EXTENSION.test(path))
  for (const [sourcePath, absolutePath] of sources) {
    let source
    try {
      source = await readFile(absolutePath, 'utf8')
    } catch (error) {
      errors.push(`static resource source cannot be read: ${sourcePath} (${error.message})`)
      continue
    }
    for (const request of staticResourceRequests(source)) {
      const target = resolveStaticResourceRequest(distDirectory, sourcePath, request)
      if (!target) continue
      if (target.escaped) {
        errors.push(`local static resource escapes dist: ${sourcePath} -> ${request}`)
        continue
      }
      if (!files.has(target.relativePath)) {
        errors.push(`local static resource target is missing: ${sourcePath} -> ${request}`)
        continue
      }
      const sourceOwner = artifactOwner(sourcePath)
      const targetOwner = artifactOwner(target.relativePath)
      if (targetOwner !== undefined && targetOwner !== sourceOwner) {
        errors.push(
          `local static resource crosses subpackage owner: ${sourcePath} -> ${target.relativePath}`,
        )
      }
    }
  }
}

export async function validatePhysicalAsyncBuild(distDirectory) {
  const directory = resolve(distDirectory)
  const errors = []
  let files
  try {
    files = await inventory(directory)
  } catch (error) {
    return [`dist directory cannot be scanned: ${directory} (${error.message})`]
  }

  const appSource = await readRequiredFile(files, 'app.json', errors, 'app config')
  const runtime = await readRequiredFile(files, 'runtime.js', errors, 'Webpack runtime')
  const appConfig = parseAppConfig(appSource, errors)
  const subpackages = validateSubpackages(appConfig, errors)

  validateRegisteredPageTopology(appConfig, subpackages, errors)
  validateInitialPages(appConfig, subpackages, errors)
  await validateNativeFirstPaint(files, appConfig, errors)
  validateRuntime(runtime, errors)
  await validateBoundaries(files, appConfig, subpackages, runtime, errors)
  validateAsyncStyles(files, errors)
  await validateGeneratedJavaScript(files, errors)
  await validateStaticRequires(directory, files, errors)
  await validateStaticResourceOwnership(directory, files, errors)

  return errors
}

async function run() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url))
  const distDirectory = process.argv[2]
    ? resolve(process.argv[2])
    : resolve(scriptDirectory, '..', 'dist')
  const errors = await validatePhysicalAsyncBuild(distDirectory)
  if (errors.length > 0) {
    console.error(`[BLOCKED] Physical async artifact validation failed (${errors.length}):`)
    for (const error of errors) console.error(`- ${error}`)
    process.exitCode = 2
    return
  }
  console.log(
    `[PASS] Physical async artifact validation: ${PHYSICAL_ASYNC_BOUNDARIES.length} page boundaries, `
    + `${PHYSICAL_ASYNC_SUBPACKAGES.length} non-independent subpackages, native require.async runtime, `
    + 'declared secondary chunks and static resource ownership.',
  )
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  await run()
}
