import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  assertBackendVerificationRuntime,
  externalDatabaseVerificationConfigured,
  inspectDockerServer,
} from '../../scripts/assert-backend-verification-runtime.mjs'
import {
  assertMavenTestReports,
  collectMavenTestReports,
  parseTestsuiteReport,
  summarizeMavenTestReports,
} from '../../scripts/assert-maven-test-reports.mjs'

function completedDocker(version = '28.4.0') {
  return () => ({ status: 0, stdout: `${version}\n`, stderr: '', error: undefined })
}

function unavailableDocker() {
  return () => ({ status: 1, stdout: '', stderr: 'unavailable', error: undefined })
}

function suiteXml(name, { tests, failures = 0, errors = 0, skipped = 0 }) {
  return `<?xml version="1.0"?><testsuite name="${name}" tests="${tests}" failures="${failures}" errors="${errors}" skipped="${skipped}"></testsuite>`
}

describe('backend verification runtime gate', () => {
  it('accepts Docker only when the server responds', () => {
    expect(inspectDockerServer(completedDocker())).toEqual({ available: true, version: '28.4.0' })
    expect(assertBackendVerificationRuntime({}, completedDocker())).toEqual({
      mode: 'DOCKER',
      version: '28.4.0',
    })
    expect(() => assertBackendVerificationRuntime({}, unavailableDocker()))
      .toThrow(/requires a reachable Docker server/)
  })

  it('accepts a complete external migration and packaged-smoke database configuration', () => {
    const environment = {
      FITNESS_TEST_MYSQL_JDBC_URL: 'jdbc:mysql://127.0.0.1:3306/fitness_verify_20260827abcd',
      FITNESS_TEST_MYSQL_USERNAME: 'fitness',
      FITNESS_SMOKE_MYSQL_JDBC_URL: 'jdbc:mysql://127.0.0.1:3306/fitness_verify_20260827abcd',
      FITNESS_SMOKE_MYSQL_USERNAME: 'fitness',
    }
    expect(externalDatabaseVerificationConfigured(environment)).toBe(true)
    expect(assertBackendVerificationRuntime(environment, unavailableDocker()))
      .toEqual({ mode: 'EXTERNAL_MYSQL' })
  })

  it('does not treat a partial external configuration as an alternative to Docker', () => {
    expect(externalDatabaseVerificationConfigured({
      FITNESS_TEST_MYSQL_JDBC_URL: 'jdbc:mysql://127.0.0.1:3306/fitness_verify_20260827abcd',
      FITNESS_TEST_MYSQL_USERNAME: 'fitness',
    })).toBe(false)
  })
})

describe('Maven zero-skip report gate', () => {
  it('parses and summarizes Surefire testsuite attributes', () => {
    const suites = parseTestsuiteReport(suiteXml('example', { tests: 4, skipped: 1 }))
    expect(suites).toEqual([expect.objectContaining({ tests: 4, skipped: 1 })])
    expect(summarizeMavenTestReports([{ name: 'x', suites }])).toEqual({
      discovered: 4,
      executed: 3,
      failures: 0,
      errors: 0,
      skipped: 1,
    })
  })

  it('fails closed when a testsuite counter is missing or malformed', () => {
    expect(() => parseTestsuiteReport(
      '<testsuite tests="1" failures="0" errors="0"></testsuite>',
      'missing-counter.xml',
    )).toThrow(/missing or invalid skipped attribute: missing-counter\.xml/)

    expect(() => parseTestsuiteReport(
      '<testsuite tests="1" failures="broken" errors="0" skipped="0"></testsuite>',
      'malformed-counter.xml',
    )).toThrow(/missing or invalid failures attribute: malformed-counter\.xml/)
  })

  it('rejects testsuite counters outside the JavaScript safe integer range', () => {
    const unsafeTests = '9'.repeat(400)
    expect(() => parseTestsuiteReport(
      `<testsuite tests="${unsafeTests}" failures="0" errors="0" skipped="0"></testsuite>`,
      'unsafe-counter.xml',
    )).toThrow(/unsafe tests attribute: unsafe-counter\.xml/)
  })

  it('requires both critical suites and rejects any skipped test', () => {
    const reports = [
      {
        name: 'TEST-com.aifitness.assistant.database.MigrationTest.xml',
        suites: parseTestsuiteReport(suiteXml('MigrationTest', { tests: 12 })),
      },
      {
        name: 'TEST-com.aifitness.assistant.release.PackagedApplicationSmokeIT.xml',
        suites: parseTestsuiteReport(suiteXml('PackagedApplicationSmokeIT', { tests: 1 })),
      },
      {
        name: 'TEST-com.aifitness.assistant.OtherTest.xml',
        suites: parseTestsuiteReport(suiteXml('OtherTest', { tests: 2 })),
      },
    ]
    expect(assertMavenTestReports(reports)).toEqual({
      discovered: 15,
      executed: 15,
      failures: 0,
      errors: 0,
      skipped: 0,
    })
    reports[2] = {
      ...reports[2],
      suites: parseTestsuiteReport(suiteXml('OtherTest', { tests: 2, skipped: 1 })),
    }
    expect(() => assertMavenTestReports(reports)).toThrow(/zero skips are required/)
  })

  it('collects only TEST XML reports from Surefire and Failsafe directories', () => {
    const root = mkdtempSync(resolve(tmpdir(), 'fitness-maven-reports-'))
    const surefire = resolve(root, 'surefire')
    const failsafe = resolve(root, 'failsafe')
    try {
      mkdirSync(surefire)
      mkdirSync(failsafe)
      writeFileSync(
        resolve(surefire, 'TEST-com.aifitness.assistant.database.MigrationTest.xml'),
        suiteXml('MigrationTest', { tests: 12 }),
      )
      writeFileSync(
        resolve(failsafe, 'TEST-com.aifitness.assistant.release.PackagedApplicationSmokeIT.xml'),
        suiteXml('PackagedApplicationSmokeIT', { tests: 1 }),
      )
      writeFileSync(resolve(surefire, 'com.aifitness.assistant.database.MigrationTest.txt'), 'ignored')

      const reports = collectMavenTestReports([surefire, failsafe])
      expect(reports.map((report) => report.name).sort()).toEqual([
        'TEST-com.aifitness.assistant.database.MigrationTest.xml',
        'TEST-com.aifitness.assistant.release.PackagedApplicationSmokeIT.xml',
      ])
      expect(assertMavenTestReports(reports).skipped).toBe(0)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
