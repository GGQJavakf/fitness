import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const backendRoot = resolve(repositoryRoot, 'backend')
const dependencyList = resolve(backendRoot, 'target', 'runtime-dependencies-osv.txt')

export function parseRuntimeDependencies(content) {
  const dependencies = new Map()
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.replace(/\x1B\[[0-?]*[ -/]*[@-~]/g, '').trim()
    if (!/:(compile|runtime)(?:\s|$)/.test(trimmed)) continue
    const coordinate = trimmed.split(/\s+--\s+/, 1)[0]
    const parts = coordinate.split(':')
    if (parts.length !== 5 && parts.length !== 6) {
      throw new Error(`Cannot parse Maven runtime dependency coordinate: ${coordinate}`)
    }
    const [group, artifact] = parts
    const version = parts.at(-2)
    const scope = parts.at(-1)
    if (!group || !artifact || !version || (scope !== 'compile' && scope !== 'runtime')) {
      throw new Error(`Cannot parse Maven runtime dependency coordinate: ${coordinate}`)
    }
    dependencies.set(`${group}:${artifact}:${version}`, { name: `${group}:${artifact}`, version })
  }
  return [...dependencies.values()].sort((left, right) =>
    left.name.localeCompare(right.name) || left.version.localeCompare(right.version))
}

export function buildOsvQueries(dependencies) {
  return dependencies.map((dependency) => ({
    package: { ecosystem: 'Maven', name: dependency.name },
    version: dependency.version,
  }))
}

export function collectOsvMatches(dependencies, response) {
  if (!Array.isArray(response?.results) || response.results.length !== dependencies.length) {
    throw new Error('OSV response does not match the submitted Maven dependency set')
  }
  return response.results.flatMap((result, index) => {
    if (typeof result !== 'object' || result === null || Array.isArray(result)) {
      throw new Error('OSV response contains a malformed result entry')
    }
    if (result?.next_page_token) {
      throw new Error('OSV response is paginated; refusing to treat a partial result as complete')
    }
    if (result?.vulns !== undefined && !Array.isArray(result.vulns)) {
      throw new Error('OSV response contains a malformed vulnerability list')
    }
    return (result?.vulns ?? []).map((vulnerability) => {
      if (typeof vulnerability?.id !== 'string' || vulnerability.id.length === 0) {
        throw new Error('OSV response contains a vulnerability without an identifier')
      }
      return {
        dependency: dependencies[index],
        id: vulnerability.id,
      }
    })
  })
}

function runMavenDependencyList() {
  const windows = process.platform === 'win32'
  const outcome = spawnSync(windows ? 'mvnw.cmd' : 'sh', windows
    ? ['-q', 'dependency:list', '-DincludeScope=runtime', '-DexcludeTransitive=false',
      '-DoutputAbsoluteArtifactFilename=false', '-DoutputFile=target/runtime-dependencies-osv.txt']
    : ['./mvnw', '-q', 'dependency:list', '-DincludeScope=runtime', '-DexcludeTransitive=false',
      '-DoutputAbsoluteArtifactFilename=false', '-DoutputFile=target/runtime-dependencies-osv.txt'], {
    cwd: backendRoot,
    shell: windows,
    stdio: 'inherit',
  })
  if (outcome.error) throw outcome.error
  if (outcome.status !== 0) throw new Error(`Maven dependency inventory failed with exit code ${outcome.status}`)
}

async function queryOsv(dependencies) {
  const response = await fetch('https://api.osv.dev/v1/querybatch', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ queries: buildOsvQueries(dependencies) }),
    signal: AbortSignal.timeout(30_000),
  })
  if (!response.ok) throw new Error(`OSV dependency query failed with HTTP ${response.status}`)
  return collectOsvMatches(dependencies, await response.json())
}

async function run() {
  try {
    runMavenDependencyList()
    const dependencies = parseRuntimeDependencies(readFileSync(dependencyList, 'utf8'))
    if (dependencies.length === 0) throw new Error('Maven runtime dependency inventory is empty')
    const matches = await queryOsv(dependencies)
    if (matches.length > 0) {
      console.error(`OSV found ${matches.length} known vulnerability match(es) in Maven runtime dependencies:`)
      for (const match of matches) {
        console.error(`- ${match.dependency.name}@${match.dependency.version}: ${match.id}`)
      }
      process.exitCode = 1
      return
    }
    console.log(`[PASS] OSV checked ${dependencies.length} Maven runtime coordinates; no known matches.`)
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await run()
