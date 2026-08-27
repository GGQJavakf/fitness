import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DEFAULT_REPORT_DIRECTORIES = [
  resolve(repositoryRoot, 'backend', 'target', 'surefire-reports'),
  resolve(repositoryRoot, 'backend', 'target', 'failsafe-reports'),
]
const CRITICAL_REPORTS = [
  'TEST-com.aifitness.assistant.database.MigrationTest.xml',
  'TEST-com.aifitness.assistant.release.PackagedApplicationSmokeIT.xml',
]

function integerAttribute(attributes, name) {
  const match = attributes.match(new RegExp(`\\b${name}="(\\d+)"`))
  return match ? Number(match[1]) : 0
}

export function parseTestsuiteReport(source, path = '<memory>') {
  const matches = [...String(source).matchAll(/<testsuite\b([^>]*)>/g)]
  if (matches.length === 0) throw new Error(`Maven test report contains no testsuite: ${path}`)
  return matches.map((match) => ({
    path,
    tests: integerAttribute(match[1], 'tests'),
    failures: integerAttribute(match[1], 'failures'),
    errors: integerAttribute(match[1], 'errors'),
    skipped: integerAttribute(match[1], 'skipped'),
  }))
}

export function collectMavenTestReports(reportDirectories = DEFAULT_REPORT_DIRECTORIES) {
  const reports = []
  for (const directory of reportDirectories) {
    if (!existsSync(directory)) continue
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (!entry.isFile() || !/^TEST-.+\.xml$/.test(entry.name)) continue
      const path = resolve(directory, entry.name)
      reports.push({
        name: entry.name,
        path,
        suites: parseTestsuiteReport(readFileSync(path, 'utf8'), path),
      })
    }
  }
  return reports
}

export function summarizeMavenTestReports(reports) {
  const totals = reports.flatMap((report) => report.suites).reduce(
    (summary, suite) => ({
      discovered: summary.discovered + suite.tests,
      failures: summary.failures + suite.failures,
      errors: summary.errors + suite.errors,
      skipped: summary.skipped + suite.skipped,
    }),
    { discovered: 0, failures: 0, errors: 0, skipped: 0 },
  )
  return { ...totals, executed: totals.discovered - totals.skipped }
}

export function assertMavenTestReports(reports) {
  if (reports.length === 0) throw new Error('Maven verification produced no TEST-*.xml reports')
  const byName = new Map(reports.map((report) => [report.name, report]))
  for (const name of CRITICAL_REPORTS) {
    const report = byName.get(name)
    if (!report) throw new Error(`Critical Maven verification report is missing: ${name}`)
    const summary = summarizeMavenTestReports([report])
    if (summary.discovered < 1) throw new Error(`Critical Maven suite executed no tests: ${name}`)
    if (summary.skipped > 0) throw new Error(`Critical Maven suite contains skipped tests: ${name}`)
  }

  const summary = summarizeMavenTestReports(reports)
  if (summary.failures > 0 || summary.errors > 0) {
    throw new Error(
      `Maven reports contain failures=${summary.failures}, errors=${summary.errors}`,
    )
  }
  if (summary.skipped > 0) {
    throw new Error(`Maven reports contain ${summary.skipped} skipped test(s); zero skips are required`)
  }
  return summary
}

function run() {
  try {
    const summary = assertMavenTestReports(collectMavenTestReports())
    console.log(
      `[verify] Maven tests: discovered=${summary.discovered}, executed=${summary.executed}, `
      + `skipped=${summary.skipped}, failures=${summary.failures}, errors=${summary.errors}.`,
    )
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
