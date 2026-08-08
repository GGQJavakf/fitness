import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const MAIN_PACKAGE_LIMIT_BYTES = 2 * 1024 * 1024
export const MAIN_PACKAGE_WARNING_BYTES = Math.floor(1.8 * 1024 * 1024)

export function evaluatePackageSize(totalBytes) {
  if (!Number.isSafeInteger(totalBytes) || totalBytes < 0) {
    throw new Error('Package size must be a non-negative safe integer')
  }
  if (totalBytes > MAIN_PACKAGE_LIMIT_BYTES) {
    return {
      level: 'BLOCKED',
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

function normalizePackageRoot(root) {
  const normalized = root.replaceAll('\\', '/').replace(/^\/+|\/+$/g, '')
  if (
    !normalized
    || normalized === '.'
    || normalized.split('/').some((segment) => segment === '..')
  ) {
    throw new Error(`Invalid subpackage root: ${root}`)
  }
  return normalized
}

export function packageSizes(files, subpackageRoots) {
  const roots = subpackageRoots.map(normalizePackageRoot)
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
  return {
    mainBytes: normalizedFiles
      .filter((file) => !roots.some((root) => belongsTo(file.path, root)))
      .reduce((total, file) => total + file.sizeBytes, 0),
    subpackages: roots.map((root) => ({
      root,
      totalBytes: normalizedFiles
        .filter((file) => belongsTo(file.path, root))
        .reduce((total, file) => total + file.sizeBytes, 0),
    })),
  }
}

function formatMiB(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(3)} MiB`
}

function run() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url))
  const distDirectory = resolve(scriptDirectory, '..', 'dist')
  const appConfig = JSON.parse(readFileSync(resolve(distDirectory, 'app.json'), 'utf8'))
  const subpackageRoots = (appConfig.subPackages ?? appConfig.subpackages ?? [])
    .map((subpackage) => subpackage.root)
  const sizes = packageSizes(directoryFiles(distDirectory), subpackageRoots)
  const packages = [
    { label: '微信主包', totalBytes: sizes.mainBytes },
    ...sizes.subpackages.map((subpackage) => ({
      label: `微信分包 ${subpackage.root}`,
      totalBytes: subpackage.totalBytes,
    })),
  ]
  let blocked = false
  for (const item of packages) {
    const result = evaluatePackageSize(item.totalBytes)
    const message = `${formatMiB(result.totalBytes)} / ${formatMiB(MAIN_PACKAGE_LIMIT_BYTES)}`
    if (result.level === 'BLOCKED') {
      console.error(`[BLOCKED] ${item.label} ${message}，已超出硬限制。`)
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
