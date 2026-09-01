import { mkdir, mkdtemp, readFile, rm, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import {
  PHYSICAL_ASYNC_BOUNDARIES,
  PHYSICAL_ASYNC_SUBPACKAGES,
  validatePhysicalAsyncBuild,
} from '../scripts/check-physical-async-boundaries.mjs'

const fixtureDirectories = []

afterEach(async () => {
  await Promise.all(fixtureDirectories.splice(0).map((directory) => (
    rm(directory, { recursive: true, force: true })
  )))
})

describe('physical async build artifact validator', () => {
  it('accepts the fixed 15-page native async artifact topology', async () => {
    const fixture = await createValidFixture()

    await expect(validatePhysicalAsyncBuild(fixture)).resolves.toEqual([])
  })

  it('rejects independent, missing, and initial async page declarations', async () => {
    const fixture = await createValidFixture()
    const appPath = join(fixture, 'app.json')
    const app = JSON.parse(await readFile(appPath, 'utf8'))
    app.subPackages[0].independent = true
    app.subPackages[0].name = 'wrong-startup-owner'
    app.subPackages[1].pages = app.subPackages[1].pages
      .filter((page) => page !== 'pages/onboarding/index')
    app.subPackages[1].pages.push('pages/new-unmapped-feature/index')
    app.subPackages[2].pages.push('async/workout-prepare')
    app.subPackages = app.subPackages
      .filter((subpackage) => subpackage.root !== 'subpackages/exercise-guide')
    app.subPackages.push({ root: 'subpackages/unmapped-owner', pages: [] })
    app.pages.push('presentation/pages/unmapped-main/index')
    await writeJson(appPath, app)

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain('subpackage must be non-independent: subpackages/startup')
    expect(errors).toContain(
      'subpackage name must match its native loader owner: '
      + 'subpackages/startup -> wrong-startup-owner',
    )
    expect(errors).toContain(
      'mapped page is missing from app.json: subpackages/planning/pages/onboarding/index',
    )
    expect(errors).toContain(
      'async chunk must not be declared as an initial page: subpackages/workout/async/workout-prepare',
    )
    expect(errors).toContain(
      'required non-independent subpackage is missing: subpackages/exercise-guide',
    )
    expect(errors).toContain(
      'subpackage page has no physical async boundary: '
      + 'subpackages/planning/pages/new-unmapped-feature/index',
    )
    expect(errors).toContain(
      'main page has no startup boundary: presentation/pages/unmapped-main/index',
    )
    expect(errors).toContain(
      'subpackage root has no physical async topology: subpackages/unmapped-owner',
    )
  })

  it('rejects a shell chunk id mapped to the wrong owner async file', async () => {
    const fixture = await createValidFixture()
    const runtimePath = join(fixture, 'runtime.js')
    const runtime = await readFile(runtimePath, 'utf8')
    await writeFile(
      runtimePath,
      runtime.replace(
        '"1":"subpackages/startup/async/startup-application"',
        '"1":"subpackages/planning/async/startup-application"',
      ),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'page shell ensureChunk is not mapped to subpackages/startup/async/startup-application: '
      + 'subpackages/startup/pages/home/index.js',
    )
  })

  it('rejects a missing physical chunk, missing wxss, and synchronous dynamic-import fallback', async () => {
    const fixture = await createValidFixture()
    const boundary = PHYSICAL_ASYNC_BOUNDARIES[0]
    const paths = fixturePaths(fixture, boundary)
    await Promise.all([
      unlink(paths.asyncFile),
      unlink(paths.style),
      writeFile(
        paths.shell,
        'Promise.resolve().then(function () { return require("./synchronous-feature") })\n',
      ),
    ])

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(`page shell does not call Webpack ensureChunk: ${paths.shellRelative}`)
    expect(errors).toContain(
      `page shell contains dynamic-import-node synchronous require fallback: ${paths.shellRelative}`,
    )
    expect(errors).toContain(`physical async chunk is missing: ${paths.asyncRelative}`)
    expect(errors).toContain(`page wxss is missing: ${paths.styleRelative}`)
  })

  it('rejects missing native envelope/JSONP runtime markers and unsafe fallbacks', async () => {
    const fixture = await createValidFixture()
    await writeFile(
      join(fixture, 'runtime.js'),
      [
        'new Function("return this")',
        'eval("0")',
        'document.createElement("script")',
        'fetch("/chunk.js")',
        'new XMLHttpRequest()',
        'wx.request({})',
        '',
      ].join('\n'),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toEqual(expect.arrayContaining([
      'runtime is missing require.async loader',
      'runtime is missing cross-context chunk envelope validator',
      'runtime is missing cross-context chunk envelope marker',
      'runtime is missing webpackJsonp callback queue',
      'runtime is missing onChunksLoaded function',
      'runtime is missing onChunksLoaded chunk predicate',
      'runtime is missing JSONP chunk completion scan',
      'runtime is missing JSONP return through onChunksLoaded',
      'runtime is missing WeChat runtime global',
      'runtime contains forbidden Function constructor',
      'runtime contains forbidden eval',
      'runtime contains forbidden document',
      'runtime contains forbidden fetch',
      'runtime contains forbidden XMLHttpRequest',
      'runtime contains forbidden wx.request',
    ]))
  })

  it('rejects generated JavaScript APIs that are unsafe in the WeChat runtime', async () => {
    const fixture = await createValidFixture()
    await writeFile(
      join(fixture, 'app.js'),
      [
        'const globalObject = Function("return this")()',
        'const wechatRuntime = globalThis.wx',
        'const parsed = new URL("https://example.test")',
        'const propertyPattern = "[\\\\p{Z}]"',
        `const oversizedPattern = /${'a'.repeat(4097)}/`,
        'const unicodePattern = /a/u',
        'const lookbehindPattern = /(?<=a)b/',
        'const modernSyntax = () => source?.value',
        'setTimeout("bootstrap()", 0)',
        'require("./runtime")',
        '',
      ].join('\n'),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'JavaScript artifact contains forbidden Function constructor: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains forbidden string timer: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains forbidden browser URL constructor: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains forbidden runtime Unicode property escape: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains forbidden globalThis: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains oversized RegExp literal (4097 > 4096): app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains unsupported Android RegExp flag u: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains unsupported Android RegExp lookbehind: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains unsupported Android syntax arrow function: app.js',
    )
    expect(errors).toContain(
      'JavaScript artifact contains unsupported Android syntax optional chaining: app.js',
    )
  })

  it('rejects a variable require.async argument even when the chunk map is valid', async () => {
    const fixture = await createValidFixture()
    const runtimePath = join(fixture, 'runtime.js')
    const runtime = await readFile(runtimePath, 'utf8')
    await writeFile(
      runtimePath,
      runtime.replace(
        'require.async("./subpackages/startup/async/startup-application.js")',
        'require.async(path)',
      ),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'runtime require.async must use one static string literal, found: path',
    )
    expect(errors).toContain(
      'runtime is missing static require.async mapping: '
      + 'subpackages/startup/async/startup-application.js',
    )
  })

  it('rejects an async chunk that still registers through a cross-context global side effect', async () => {
    const fixture = await createValidFixture()
    const paths = fixturePaths(fixture, PHYSICAL_ASYNC_BOUNDARIES[0])
    await writeFile(
      paths.asyncFile,
      '(wx["webpackJsonp"] = wx["webpackJsonp"] || []).push([[1], {}]);\n',
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toEqual(expect.arrayContaining([
      `physical async chunk is missing the cross-context registration envelope: ${paths.asyncRelative}`,
      `physical async chunk does not export its registration envelope: ${paths.asyncRelative}`,
      `physical async chunk still depends on a cross-context webpackJsonp side effect: ${paths.asyncRelative}`,
    ]))
  })

  it('rejects a page or global style without the native pre-React first-paint surface', async () => {
    const fixture = await createValidFixture()
    await Promise.all([
      writeFile(
        join(fixture, 'presentation', 'pages', 'home', 'index.wxml'),
        '<template is="taro_tmpl" data="{{root:root}}" />\n',
      ),
      writeFile(join(fixture, 'app.wxss'), '.app {}\n'),
    ])

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain('global app style is missing native first-paint protection')
    expect(errors).toContain(
      'registered page is missing native first-paint protection: '
      + 'presentation/pages/home/index.wxml',
    )
    expect(errors).toContain(
      'registered page is missing native first-paint diagnostic: '
      + 'presentation/pages/home/index.wxml',
    )
  })

  it('rejects styles emitted below an async directory', async () => {
    const fixture = await createValidFixture()
    const style = join(fixture, 'subpackages', 'planning', 'async', 'plan.wxss')
    await writeFileWithDirectories(style, '.page {}\n')

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'async directory must not contain styles: subpackages/planning/async/plan.wxss',
    )
  })

  it('rejects a missing or wrongly mapped declared secondary async chunk', async () => {
    const fixture = await createValidFixture()
    const secondary = secondaryFixtureRecords()[0]
    await unlink(join(fixture, ...secondary.path.split('/')))
    const runtimePath = join(fixture, 'runtime.js')
    const runtime = await readFile(runtimePath, 'utf8')
    await writeFile(
      runtimePath,
      runtime.replace(
        `"${secondary.id}":"subpackages/exercise-guide/async/detail"`,
        `"${secondary.id}":"subpackages/workout/async/detail"`,
      ),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'declared secondary async chunk is missing: '
      + 'subpackages/exercise-guide/async/detail.js',
    )
    expect(errors).toContain(
      'physical async chunk secondary ensureChunk is not mapped to '
      + 'subpackages/exercise-guide/async/detail: '
      + 'subpackages/workout/async/workout-session.js',
    )
  })

  it('rejects cross-owner and missing local static resources emitted by JS or CSS', async () => {
    const fixture = await createValidFixture()
    const workoutChunk = join(
      fixture,
      'subpackages',
      'workout',
      'async',
      'workout-session.js',
    )
    const workoutSource = await readFile(workoutChunk, 'utf8')
    await writeFile(
      workoutChunk,
      `${workoutSource}\nvar crossOwner = "subpackages/exercise-guide/assets/guide.jpg"\n`,
    )
    await writeFileWithDirectories(
      join(fixture, 'subpackages', 'exercise-guide', 'assets', 'guide.jpg'),
      'fixture',
    )
    await writeFile(
      join(fixture, 'subpackages', 'exercise-guide', 'pages', 'detail', 'index.wxss'),
      [
        '.guide { background-image: url("../../assets/missing.png"); }',
        '@font-face { src: url("../../assets/missing.eot"); }',
        '.poster { background-image: url("../../assets/missing.avif"); }',
        '',
      ].join('\n'),
    )

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain(
      'local static resource crosses subpackage owner: '
      + 'subpackages/workout/async/workout-session.js -> '
      + 'subpackages/exercise-guide/assets/guide.jpg',
    )
    expect(errors).toContain(
      'local static resource target is missing: '
      + 'subpackages/exercise-guide/pages/detail/index.wxss -> ../../assets/missing.png',
    )
    expect(errors).toContain(
      'local static resource target is missing: '
      + 'subpackages/exercise-guide/pages/detail/index.wxss -> ../../assets/missing.eot',
    )
    expect(errors).toContain(
      'local static resource target is missing: '
      + 'subpackages/exercise-guide/pages/detail/index.wxss -> ../../assets/missing.avif',
    )
  })

  it('rejects missing and dist-escaping relative static require targets in every JS artifact', async () => {
    const fixture = await createValidFixture()
    await writeFile(
      join(fixture, 'app.js'),
      'require("./runtime")\nrequire("./missing-runtime-helper")\nrequire("../outside-dist")\n',
    )
    const nested = join(fixture, 'subpackages', 'planning', 'async', 'nested-check.js')
    await writeFile(nested, 'require("./missing-neighbor")\n')

    const errors = await validatePhysicalAsyncBuild(fixture)

    expect(errors).toContain('relative static require target is missing: app.js -> ./missing-runtime-helper')
    expect(errors).toContain('relative static require escapes dist: app.js -> ../outside-dist')
    expect(errors).toContain(
      'relative static require target is missing: subpackages/planning/async/nested-check.js -> ./missing-neighbor',
    )
  })
})

async function createValidFixture() {
  const fixture = await mkdtemp(join(tmpdir(), 'fitness-physical-async-gate-'))
  fixtureDirectories.push(fixture)

  const pagesByOwner = new Map(PHYSICAL_ASYNC_SUBPACKAGES.map((owner) => [owner, []]))
  for (const boundary of PHYSICAL_ASYNC_BOUNDARIES) {
    pagesByOwner.get(boundary.owner).push(`pages/${boundary.page}/index`)
  }
  const appConfig = {
    pages: ['presentation/pages/home/index'],
    subPackages: PHYSICAL_ASYNC_SUBPACKAGES.map((owner) => ({
      root: `subpackages/${owner}`,
      name: owner,
      pages: pagesByOwner.get(owner),
    })),
  }
  await Promise.all([
    writeJson(join(fixture, 'app.json'), appConfig),
    writeFile(join(fixture, 'app.js'), 'require("./runtime")\n'),
    writeFile(join(fixture, 'app.wxss'), '/* fitness-native-first-paint-v1 */\n'),
    writeFile(join(fixture, 'runtime.js'), validRuntimeSource()),
    writeFileWithDirectories(
      join(fixture, 'presentation', 'pages', 'home', 'index.wxml'),
      '<view class="fitness-native-first-paint-v1">WB-P00 · R5</view>\n',
    ),
  ])

  const secondaryRecords = secondaryFixtureRecords()
  const primaryAsyncPaths = new Set(
    PHYSICAL_ASYNC_BOUNDARIES.map((boundary) => fixturePaths(fixture, boundary).asyncRelative),
  )
  await Promise.all(PHYSICAL_ASYNC_BOUNDARIES.flatMap((boundary, index) => {
    const paths = fixturePaths(fixture, boundary)
    const secondaryLoads = secondaryRecords
      .filter((record) => record.boundary === boundary)
      .map((record) => `r.e(${record.id}).then(r.bind(r, ${record.id + 1000}))`)
    return [
      writeFileWithDirectories(
        paths.shell,
        `r.e(${index + 1}).then(r.bind(r, ${index + 1001}))\n`,
      ),
      writeFileWithDirectories(paths.style, '.page {}\n'),
      writeFileWithDirectories(
        paths.template,
        '<view class="fitness-native-first-paint-v1">WB-P00 · R5</view>\n',
      ),
      writeFileWithDirectories(
        paths.asyncFile,
        `module.exports = {"__fitnessWebpackChunkEnvelope":1,"registration":[[${index + 1}],{`
        + `"${index + 1001}":function(){${secondaryLoads.join(';')}}}]}\n`,
      ),
    ]
  }).concat(secondaryRecords
    .filter((record) => !primaryAsyncPaths.has(record.path))
    .map((record) => (
      writeFileWithDirectories(
        join(fixture, ...record.path.split('/')),
        `module.exports = {"__fitnessWebpackChunkEnvelope":1,"registration":[[${record.id}],{}]}\n`,
      )
    ))))

  return fixture
}

function validRuntimeSource() {
  const chunkMap = Object.fromEntries(PHYSICAL_ASYNC_BOUNDARIES.map((boundary, index) => [
    index + 1,
    `subpackages/${boundary.owner}/async/${boundary.chunk}`,
  ]))
  for (const record of secondaryFixtureRecords()) {
    chunkMap[record.id] = record.path.slice(0, -3)
  }
  const literalDispatch = [...new Set(Object.values(chunkMap))]
    .sort()
    .map((path) => (
      `case ${JSON.stringify(`${path}.js`)}: return require.async(${JSON.stringify(`./${path}.js`)});`
    ))
  return [
    'var r = {}',
    'r.O = function (value) { return value }',
    'r.O.j = function () { return true }',
    '[0].some(function (chunk) { return chunk !== 0 })',
    'var chunks = wx["webpackJsonp"] = wx["webpackJsonp"] || []',
    'var installWeappAsyncChunk = function (envelope) {',
    '  if (envelope.__fitnessWebpackChunkEnvelope !== 1) throw new Error("Invalid WeChat async chunk registration envelope")',
    '  chunks.push(envelope.registration)',
    '}',
    'function finish(value) { return r.O(value) }',
    'r.g = wx',
    'function loadChunk(path) {',
    '  switch (path) {',
    ...literalDispatch.map((line) => `    ${line}`),
    '    default: return undefined',
    '  }',
    '}',
    `r.u = function (id) { return ${JSON.stringify(chunkMap)}[id] + ".js" }`,
    '',
  ].join('\n')
}

function secondaryFixtureRecords() {
  let id = PHYSICAL_ASYNC_BOUNDARIES.length + 1
  return PHYSICAL_ASYNC_BOUNDARIES.flatMap((boundary) => (
    (boundary.secondaryChunks ?? []).map((secondary) => ({
      boundary,
      id: id++,
      path: `subpackages/${secondary.owner}/async/${secondary.chunk}.js`,
    }))
  ))
}

function fixturePaths(fixture, boundary) {
  const root = `subpackages/${boundary.owner}`
  const shellRelative = `${root}/pages/${boundary.page}/index.js`
  const styleRelative = `${root}/pages/${boundary.page}/index.wxss`
  const asyncRelative = `${root}/async/${boundary.chunk}.js`
  return {
    shellRelative,
    styleRelative,
    asyncRelative,
    shell: join(fixture, ...shellRelative.split('/')),
    style: join(fixture, ...styleRelative.split('/')),
    template: join(fixture, ...`${root}/pages/${boundary.page}/index.wxml`.split('/')),
    asyncFile: join(fixture, ...asyncRelative.split('/')),
  }
}

async function writeFileWithDirectories(path, content) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
}

async function writeJson(path, value) {
  await writeFileWithDirectories(path, `${JSON.stringify(value)}\n`)
}
