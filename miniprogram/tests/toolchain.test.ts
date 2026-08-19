import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')
const repositoryRoot = resolve(projectRoot, '..')
const require = createRequire(import.meta.url)

describe('Taro weapp toolchain', () => {
  it('provides the application entry and a home page', () => {
    expect(existsSync(resolve(projectRoot, 'src/app.tsx'))).toBe(true)
    expect(existsSync(resolve(projectRoot, 'src/presentation/pages/home/index.tsx'))).toBe(true)
  })

  it('registers onboarding, plan, and plan editing pages without App release targets', () => {
    const config = readFileSync(resolve(projectRoot, 'src/app.config.ts'), 'utf8')
    for (const page of ['home', 'onboarding', 'plan-candidates', 'plan', 'plan-editor']) {
      expect(config).toContain(`presentation/pages/${page}/index`)
    }
    expect(config).not.toMatch(/android|ios|app-release/i)
  })

  it('lets WeChat DevTools import the repository code directory directly', () => {
    const rootConfigPath = resolve(repositoryRoot, 'project.config.json')
    expect(existsSync(rootConfigPath)).toBe(true)

    const config = JSON.parse(readFileSync(rootConfigPath, 'utf8')) as {
      compileType?: string
      miniprogramRoot?: string
    }
    expect(config.compileType).toBe('miniprogram')
    expect(config.miniprogramRoot).toBe('miniprogram/dist/')
  })

  it('injects runtime configuration at build time without Node globals in app code', () => {
    const taroConfig = readFileSync(resolve(projectRoot, 'config/index.ts'), 'utf8')
    const compositionRoot = readFileSync(
      resolve(projectRoot, 'src/platform/weapp/compositionRoot.ts'),
      'utf8',
    )

    expect(taroConfig).toContain('defineConstants')
    expect(compositionRoot).toContain('__FITNESS_API_BASE_URL__')
    expect(compositionRoot).not.toMatch(/\bprocess\s*\./)
  })

  it('uses an explicit mobile API timeout instead of the 60 second platform default', () => {
    const adapter = readFileSync(
      resolve(projectRoot, 'src/platform/weapp/adapters.ts'),
      'utf8',
    )

    expect(adapter).toContain('WEAPP_REQUEST_TIMEOUT_MS = 20_000')
    expect(adapter).toContain('requestTimeoutMs ?? WEAPP_REQUEST_TIMEOUT_MS')
    expect(adapter).toContain('timeout: requestTimeoutMs')
    expect(adapter).toContain('withCloudBaseTimeout')
  })

  it('uses the project-local weapp runner without the general-purpose template downloader', () => {
    const packageJson = JSON.parse(readFileSync(resolve(projectRoot, 'package.json'), 'utf8')) as {
      scripts?: Record<string, string>
      devDependencies?: Record<string, string>
      overrides?: Record<string, unknown>
    }
    const lock = JSON.parse(readFileSync(resolve(projectRoot, 'package-lock.json'), 'utf8')) as {
      packages?: Record<string, { version?: string }>
    }

    expect(packageJson.devDependencies).not.toHaveProperty('@tarojs/cli')
    expect(packageJson.devDependencies).toMatchObject({
      '@tarojs/service': '4.2.1',
      '@tarojs/plugin-doctor': '0.0.13',
    })
    expect(packageJson.scripts?.['build:weapp']).toContain('scripts/taro-weapp-build.mjs')
    expect(packageJson.scripts?.verify).toContain('audit:toolchain')
    expect(packageJson.scripts?.['audit:packaged']).toContain('--audit-level=low')
    expect(packageJson.scripts?.['audit:toolchain']).toContain('--audit-level=low')
    expect(lock.packages).not.toHaveProperty('node_modules/@tarojs/cli')
    expect(lock.packages).not.toHaveProperty('node_modules/download-git-repo')
    expect(lock.packages).not.toHaveProperty('node_modules/decompress')
    expect(Object.values(lock.packages ?? {}).some(pkg =>
      typeof (pkg as { resolved?: unknown }).resolved === 'string'
      && (pkg as { resolved: string }).resolved.startsWith('file:'),
    )).toBe(false)
  })

  it('keeps project-local build output inside the configured dist directory', () => {
    const methods = new Map<string, (input: { filePath: string; content: string }) => void>()
    const plugin = require(resolve(projectRoot, 'scripts/taro-build-plugin.cjs')) as (
      context: Record<string, unknown>,
    ) => void
    plugin({
      paths: { outputPath: resolve(projectRoot, 'dist') },
      registerMethod(name: string, implementation?: (input: {
        filePath: string
        content: string
      }) => void) {
        if (implementation) methods.set(name, implementation)
      },
      registerCommand() {},
    })

    const writeFileToDist = methods.get('writeFileToDist')
    expect(writeFileToDist).toBeTypeOf('function')
    expect(() => writeFileToDist?.({
      filePath: '../outside-project-config.json',
      content: '{}',
    })).toThrow('cannot write outside the output directory')
  })

  it('keeps the legacy globs callback API compatible while patching safe transitive dependencies', async () => {
    const packageJson = JSON.parse(readFileSync(resolve(projectRoot, 'package.json'), 'utf8')) as {
      overrides?: Record<string, unknown>
    }
    const lock = JSON.parse(readFileSync(resolve(projectRoot, 'package-lock.json'), 'utf8')) as {
      packages?: Record<string, { version?: string }>
    }

    expect(packageJson.overrides).toMatchObject({
      'fast-uri': '3.1.5',
      'minimatch@3.1.5': { 'brace-expansion': '1.1.18' },
      nanoid: '3.3.18',
      vm2: '3.11.6',
      webpack: '5.109.2',
      webpackbar: '7.0.0',
      'webpack-dev-server': '5.2.6',
    })
    expect(packageJson.overrides).not.toHaveProperty('globs')
    expect(lock.packages?.['node_modules/globs']?.version).toBe('0.1.4')
    expect(lock.packages?.['node_modules/globs/node_modules/glob']?.version).toBe('7.2.3')
    expect(lock.packages?.['node_modules/minimatch/node_modules/brace-expansion']?.version)
      .toBe('1.1.18')
    expect(lock.packages?.['node_modules/fast-uri']?.version).toBe('3.1.5')
    expect(lock.packages?.['node_modules/nanoid']?.version).toBe('3.3.18')
    expect(lock.packages?.['node_modules/vm2']?.version).toBe('3.11.6')
    expect(lock.packages?.['node_modules/webpack']?.version).toBe('5.109.2')
    expect(lock.packages?.['node_modules/webpackbar']?.version).toBe('7.0.0')
    expect(lock.packages?.['node_modules/webpack-dev-server']?.version).toBe('5.2.6')

    const globs = require('globs') as (
      patterns: string[],
      options: { cwd: string },
      callback: (error: Error | null, files: string[]) => void,
    ) => void
    const files = await new Promise<string[]>((resolveFiles, reject) => {
      globs(['package.json'], { cwd: projectRoot }, (error, matches) => {
        if (error) {
          reject(error)
          return
        }
        resolveFiles(matches)
      })
    })
    expect(files).toEqual(['package.json'])
  })

  it('checks the aggregate WeChat main-package size after every production build', () => {
    const packageJson = JSON.parse(readFileSync(resolve(projectRoot, 'package.json'), 'utf8')) as {
      scripts?: Record<string, string>
    }

    expect(packageJson.scripts?.['build:weapp']).toContain('scripts/check-package-size.mjs')
  })
})
