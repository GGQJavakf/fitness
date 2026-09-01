import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const MAIN_PACKAGE_LIMIT_BYTES = 2 * 1024 * 1024
export const MAIN_PACKAGE_WARNING_BYTES = Math.floor(1.8 * 1024 * 1024)
export const MIN_PACKAGE_HEADROOM_BYTES = 128 * 1024

export function evaluatePackageSize(totalBytes) {
  if (!Number.isSafeInteger(totalBytes) || totalBytes < 0) {
    throw new Error('Package size must be a non-negative safe integer')
  }
  if (totalBytes > MAIN_PACKAGE_LIMIT_BYTES) {
    return {
      level: 'BLOCKED',
      reason: 'HARD_LIMIT_EXCEEDED',
      totalBytes,
      headroomBytes: MAIN_PACKAGE_LIMIT_BYTES - totalBytes
    }
  }
  if (MAIN_PACKAGE_LIMIT_BYTES - totalBytes < MIN_PACKAGE_HEADROOM_BYTES) {
    return {
      level: 'BLOCKED',
      reason: 'INSUFFICIENT_HEADROOM',
      totalBytes,
      headroomBytes: MAIN_PACKAGE_LIMIT_BYTES - totalBytes
    }
  }
  if (totalBytes >= MAIN_PACKAGE_WARNING_BYTES) {
    return {
      level: 'WARN',
      totalBytes,
      headroomBytes: MAIN_PACKAGE_LIMIT_BYTES - totalBytes
    }
  }
  return {
    level: 'PASS',
    totalBytes,
    headroomBytes: MAIN_PACKAGE_LIMIT_BYTES - totalBytes
  }
}

export function directorySize(directory) {
  return directoryFiles(directory).reduce((total, file) => total + file.sizeBytes, 0)
}

export function directoryFiles(directory, prefix = '') {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolutePath = resolve(directory, entry.name)
    const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.isDirectory()) return directoryFiles(absolutePath, relativePath)
    if (entry.isFile()) return [{ path: relativePath, sizeBytes: statSync(absolutePath).size }]
    return []
  })
}

function normalizeSubpackage(subpackage) {
  const root = typeof subpackage === 'string' ? subpackage : subpackage.root
  const normalized = root.replaceAll('\\', '/').replace(/^\/+|\/+$/g, '')
  if (
    !normalized
    || normalized === '.'
    || normalized.split('/').some((segment) => segment === '..')
  ) {
    throw new Error(`Invalid subpackage root: ${root}`)
  }
  return {
    root: normalized,
    independent: typeof subpackage === 'object' && subpackage.independent === true,
  }
}

export function packageSizes(files, subpackages) {
  const definitions = subpackages.map(normalizeSubpackage)
  const roots = definitions.map((subpackage) => subpackage.root)
  if (new Set(roots).size !== roots.length) {
    throw new Error('Subpackage roots must be unique')
  }
  const normalizedFiles = files.map((file) => ({
    path: file.path.replaceAll('\\', '/').replace(/^\/+/, ''),
    sizeBytes: file.sizeBytes,
  }))
  for (const file of normalizedFiles) {
    if (!Number.isSafeInteger(file.sizeBytes) || file.sizeBytes < 0) {
      throw new Error(`Invalid file size for ${file.path}`)
    }
  }
  const belongsTo = (path, root) => path === root || path.startsWith(`${root}/`)
  // The preview service measures a non-independent subpackage together with
  // the top-level JavaScript chunks it executes from the main package.
  const sharedTopLevelJavaScriptBytes = normalizedFiles
    .filter((file) => !file.path.includes('/') && file.path.endsWith('.js'))
    .reduce((total, file) => total + file.sizeBytes, 0)
  return {
    mainBytes: normalizedFiles
      .filter((file) => !roots.some((root) => belongsTo(file.path, root)))
      .reduce((total, file) => total + file.sizeBytes, 0),
    sharedTopLevelJavaScriptBytes,
    subpackages: definitions.map(({ root, independent }) => {
      const rawBytes = normalizedFiles
        .filter((file) => belongsTo(file.path, root))
        .reduce((total, file) => total + file.sizeBytes, 0)
      const sharedBytes = independent ? 0 : sharedTopLevelJavaScriptBytes
      return {
        root,
        independent,
        rawBytes,
        sharedBytes,
        totalBytes: rawBytes + sharedBytes,
      }
    }),
  }
}

function formatMiB(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(3)} MiB`
}

function run() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url))
  const distDirectory = resolve(scriptDirectory, '..', 'dist')
  const appConfig = JSON.parse(readFileSync(resolve(distDirectory, 'app.json'), 'utf8'))
  const subpackages = appConfig.subPackages ?? appConfig.subpackages ?? []
  const sizes = packageSizes(directoryFiles(distDirectory), subpackages)
  const packages = [
    { label: '微信主包', totalBytes: sizes.mainBytes },
    ...sizes.subpackages.map((subpackage) => ({
      label: `微信分包 ${subpackage.root}`,
      totalBytes: subpackage.totalBytes,
      detail: subpackage.independent
        ? '独立分包物理文件'
        : `物理文件 ${formatMiB(subpackage.rawBytes)} + 主包共享 JS ${formatMiB(subpackage.sharedBytes)}`,
    })),
  ]
  let blocked = false
  for (const item of packages) {
    const result = evaluatePackageSize(item.totalBytes)
    const message = `${formatMiB(result.totalBytes)} / ${formatMiB(MAIN_PACKAGE_LIMIT_BYTES)}`
      + (item.detail ? `（${item.detail}）` : '')
    if (result.level === 'BLOCKED') {
      const reason = result.reason === 'HARD_LIMIT_EXCEEDED'
        ? '已超出硬限制'
        : `剩余 ${formatMiB(result.headroomBytes)}，低于 ${formatMiB(MIN_PACKAGE_HEADROOM_BYTES)} 安全余量`
      console.error(`[BLOCKED] ${item.label} ${message}，${reason}。`)
      blocked = true
    } else if (result.level === 'WARN') {
      console.warn(`[WARN] ${item.label} ${message}，剩余 ${formatMiB(result.headroomBytes)}。`)
    } else {
      console.log(`[PASS] ${item.label} ${message}，剩余 ${formatMiB(result.headroomBytes)}。`)
    }
  }
  if (blocked) process.exitCode = 2
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
