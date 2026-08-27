import { existsSync, lstatSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const MAX_PROJECT_CONFIGURATION_BYTES = 64 * 1024
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const DEFAULT_ROOT_PROJECT_CONFIGURATION = resolve(repositoryRoot, 'project.config.json')
export const DEFAULT_MINIPROGRAM_PROJECT_CONFIGURATION = resolve(
  repositoryRoot,
  'miniprogram',
  'project.config.json',
)

export function deriveMiniprogramProjectConfiguration(configuration) {
  if (typeof configuration !== 'object' || configuration === null || Array.isArray(configuration)) {
    throw new Error('WeChat project configuration must be a JSON object')
  }
  if (configuration.compileType !== 'miniprogram') {
    throw new Error('WeChat project configuration compileType must be miniprogram')
  }
  if (typeof configuration.appid !== 'string' || !configuration.appid.trim()) {
    throw new Error('WeChat project configuration appid must be configured')
  }
  if (configuration.miniprogramRoot !== 'miniprogram/dist/') {
    throw new Error('Root project.config.json miniprogramRoot must be miniprogram/dist/')
  }
  return {
    ...configuration,
    appid: configuration.appid.trim(),
    miniprogramRoot: './dist',
  }
}

export function prepareWechatProjectConfiguration({
  sourcePath = DEFAULT_ROOT_PROJECT_CONFIGURATION,
  destinationPath = DEFAULT_MINIPROGRAM_PROJECT_CONFIGURATION,
} = {}) {
  if (!existsSync(sourcePath)) {
    throw new Error(
      'Missing local project.config.json; copy project.config.example.json and configure the AppID first',
    )
  }
  const stats = lstatSync(sourcePath)
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error('Local project.config.json must be a regular non-symlink file')
  }
  if (stats.size > MAX_PROJECT_CONFIGURATION_BYTES) {
    throw new Error('Local project.config.json exceeds the 64 KiB safety limit')
  }

  let parsed
  try {
    parsed = JSON.parse(readFileSync(sourcePath, 'utf8'))
  } catch {
    throw new Error('Local project.config.json is not valid JSON')
  }
  const derived = deriveMiniprogramProjectConfiguration(parsed)
  if (existsSync(destinationPath)) {
    const destinationStats = lstatSync(destinationPath)
    if (!destinationStats.isFile() || destinationStats.isSymbolicLink()) {
      throw new Error('Derived miniprogram/project.config.json must be a regular non-symlink file')
    }
  }
  writeFileSync(destinationPath, `${JSON.stringify(derived, null, 2)}\n`, 'utf8')
  return { sourcePath, destinationPath }
}

function run() {
  try {
    prepareWechatProjectConfiguration()
    console.log('[project-config] derived miniprogram/project.config.json from local root configuration (AppID hidden).')
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
