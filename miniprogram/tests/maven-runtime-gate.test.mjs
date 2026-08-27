import { describe, expect, it } from 'vitest'

import {
  expectedMavenVersion,
  inspectMavenRuntime,
  mavenVersionInvocation,
} from '../../scripts/assert-maven-runtime.mjs'

const properties = [
  'wrapperVersion=3.3.4',
  'distributionType=only-script',
  'distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.13/apache-maven-3.9.13-bin.zip',
  '',
].join('\n')

function completedRuntime(maven = '3.9.13', java = '21.0.12') {
  return () => ({
    status: 0,
    signal: null,
    error: undefined,
    stdout: `Apache Maven ${maven}\nJava version: ${java}, vendor: Eclipse Adoptium\n`,
    stderr: '',
  })
}

describe('Maven runtime verification gate', () => {
  it('requires the exact HTTPS Maven Central distribution URL', () => {
    expect(expectedMavenVersion(properties)).toBe('3.9.13')
    expect(() => expectedMavenVersion(
      properties.replace('https://repo.maven.apache.org/maven2', 'http://mirror.invalid/maven-public'),
    )).toThrow(/exact HTTPS Maven Central/)
  })

  it('uses the platform wrapper command without changing Maven goals', () => {
    expect(mavenVersionInvocation('linux')).toEqual({
      executable: 'sh',
      arguments: ['./mvnw', '-version'],
    })
    expect(mavenVersionInvocation('win32')).toEqual({
      executable: 'mvnw.cmd',
      arguments: ['-version'],
    })
  })

  it('accepts only the configured Maven version running on Java 21', () => {
    expect(inspectMavenRuntime({
      platform: 'linux',
      spawn: completedRuntime(),
      propertiesSource: properties,
    })).toEqual({ mavenVersion: '3.9.13', javaMajorVersion: 21 })

    expect(() => inspectMavenRuntime({
      platform: 'linux',
      spawn: completedRuntime('3.9.12'),
      propertiesSource: properties,
    })).toThrow(/must be 3.9.13/)

    expect(() => inspectMavenRuntime({
      platform: 'linux',
      spawn: completedRuntime('3.9.13', '17.0.12'),
      propertiesSource: properties,
    })).toThrow(/must use Java 21/)
  })

  it('fails closed when wrapper bootstrap exceeds the bounded timeout', () => {
    expect(() => inspectMavenRuntime({
      platform: 'linux',
      spawn: () => ({
        status: null,
        signal: 'SIGTERM',
        error: Object.assign(new Error('timed out'), { code: 'ETIMEDOUT' }),
        stdout: '',
        stderr: '',
      }),
      timeoutMs: 1_000,
      propertiesSource: properties,
    })).toThrow(/exceeded 1 seconds/)
  })
})
