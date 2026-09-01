import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const miniprogramRoot = resolve(scriptDirectory, '..')

export const DEFAULT_DIST_APP_CONFIGURATION = resolve(miniprogramRoot, 'dist', 'app.json')
export const DEFAULT_PRIVATE_CONFIGURATIONS = Object.freeze([
  resolve(miniprogramRoot, '..', 'project.private.config.json'),
  resolve(miniprogramRoot, 'project.private.config.json'),
])

function normalizePagePath(value) {
  return typeof value === 'string'
    ? value.trim().replace(/^\/+|\/+$/g, '')
    : ''
}

export function registeredMiniProgramPages(appConfiguration) {
  const pages = new Set()
  for (const page of appConfiguration?.pages ?? []) {
    const normalized = normalizePagePath(page)
    if (normalized) pages.add(normalized)
  }
  const subpackages = appConfiguration?.subPackages ?? appConfiguration?.subpackages ?? []
  for (const subpackage of subpackages) {
    const root = normalizePagePath(subpackage?.root)
    if (!root) continue
    for (const page of subpackage?.pages ?? []) {
      const normalized = normalizePagePath(page)
      if (normalized) pages.add(`${root}/${normalized}`)
    }
  }
  return pages
}

async function readJson(path, label, errors) {
  let source
  try {
    source = await readFile(path, 'utf8')
  } catch (error) {
    errors.push(`${label} cannot be read: ${path} (${error.message})`)
    return undefined
  }
  try {
    return JSON.parse(source)
  } catch {
    errors.push(`${label} is not valid JSON: ${path}`)
    return undefined
  }
}

export async function validatePreviewLaunchConditions({
  distAppConfigurationPath = DEFAULT_DIST_APP_CONFIGURATION,
  privateConfigurationPaths = DEFAULT_PRIVATE_CONFIGURATIONS,
} = {}) {
  const errors = []
  const appConfiguration = await readJson(
    distAppConfigurationPath,
    'dist app configuration',
    errors,
  )
  if (!appConfiguration) return errors
  const registeredPages = registeredMiniProgramPages(appConfiguration)
  if (registeredPages.size === 0) {
    errors.push(`dist app configuration has no registered pages: ${distAppConfigurationPath}`)
    return errors
  }

  const uniquePrivateConfigurationPaths = new Set(
    privateConfigurationPaths.map((path) => resolve(path)),
  )
  for (const privateConfigurationPath of uniquePrivateConfigurationPaths) {
    if (!existsSync(privateConfigurationPath)) continue
    const privateConfiguration = await readJson(
      privateConfigurationPath,
      'private project configuration',
      errors,
    )
    if (!privateConfiguration) continue
    const conditions = privateConfiguration?.condition?.miniprogram?.list
    if (conditions === undefined) continue
    if (!Array.isArray(conditions)) {
      errors.push(`private project launch conditions must be an array: ${privateConfigurationPath}`)
      continue
    }
    for (const condition of conditions) {
      const pathName = normalizePagePath(condition?.pathName)
      if (!pathName || registeredPages.has(pathName)) continue
      const name = typeof condition?.name === 'string' && condition.name.trim()
        ? condition.name.trim()
        : '<unnamed>'
      errors.push(
        `private project launch condition is not registered: ${name} -> ${pathName} (${privateConfigurationPath})`,
      )
    }
  }
  return errors
}

async function run() {
  const errors = await validatePreviewLaunchConditions()
  if (errors.length > 0) {
    console.error(`[BLOCKED] WeChat preview launch-condition validation failed (${errors.length}):`)
    for (const error of errors) console.error(`- ${error}`)
    process.exitCode = 2
    return
  }
  console.log('[PASS] WeChat preview launch conditions reference registered dist pages.')
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  await run()
}
