import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  ALLOWED_RELEASE_ENVIRONMENT_KEYS,
  DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY,
  loadMergedReleaseEnvironment,
  resolveTaroBuildEnvironment
} from '../../scripts/release-environment.mjs'

const require = createRequire(import.meta.url)
const { Config, Kernel } = require('@tarojs/service')
const { dotenvParse, patchEnv } = require('@tarojs/helper')

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = parseArguments(process.argv.slice(2))

const deviceApiBaseUrlOverride = process.env[DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]
const taroBuildEnvironment = resolveTaroBuildEnvironment(
  process.env,
  loadMergedReleaseEnvironment({}),
  deviceApiBaseUrlOverride
)
for (const key of ALLOWED_RELEASE_ENVIRONMENT_KEYS) {
  if (!key.startsWith('TARO_APP_')) delete process.env[key]
}
delete process.env[DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]
for (const [key, value] of Object.entries(taroBuildEnvironment)) {
  if (key.startsWith('TARO_APP_')) process.env[key] = value
}

process.env.NODE_ENV ||= args.watch ? 'development' : 'production'
process.env.TARO_ENV = 'weapp'
const mode = args.mode || process.env.NODE_ENV
const expandedEnvironment = dotenvParse(projectRoot, args.envPrefix, mode)

const config = new Config({ appPath: projectRoot, disableGlobalConfig: true })
await config.init({ mode, command: 'build' })
if (!config.isInitSuccess) {
  throw new Error(`Unable to load Taro config from ${config.configPath}`)
}

config.initialConfig.env = patchEnv(config.initialConfig, expandedEnvironment)
const kernel = new Kernel({
  appPath: projectRoot,
  config,
  presets: [],
  plugins: [
    resolve(projectRoot, 'scripts/taro-build-plugin.cjs'),
    '@tarojs/plugin-platform-weapp',
    '@tarojs/plugin-framework-react',
  ],
})

await kernel.run({
  name: 'build',
  opts: {
    _: ['build'],
    options: {
      platform: 'weapp',
      isWatch: args.watch,
    },
  },
})

export function parseArguments(argv) {
  const parsed = { watch: false, mode: undefined, envPrefix: undefined }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--watch') {
      parsed.watch = true
      continue
    }
    if (argument === '--mode' || argument === '--env-prefix') {
      const value = argv[index + 1]?.trim()
      if (!value || value.startsWith('--')) {
        throw new Error(`${argument} requires a value`)
      }
      if (argument === '--mode') parsed.mode = value
      if (argument === '--env-prefix') parsed.envPrefix = value
      index += 1
      continue
    }
    throw new Error(`Unsupported build option: ${argument}`)
  }
  return parsed
}
