import { describe, expect, it } from 'vitest'

import {
  buildOsvQueries,
  collectOsvMatches,
  parseRuntimeDependencies,
} from '../../scripts/audit-maven-runtime-osv.mjs'

describe('Maven runtime OSV audit', () => {
  it('keeps only unique runtime and compile coordinates', () => {
    expect(parseRuntimeDependencies(`
The following files have been resolved:
   org.example:runtime-lib:jar:1.2.3:runtime
   org.example:compile-lib:jar:4.5.6:compile -- module example
   org.example:classified-lib:jar:native:5.6.7:runtime
   org.example:web-app:war:8.9.0:runtime
   org.example:colored-lib:jar:9.0.1:compile\u001b[36m -- module colored\u001b[0;1m [auto]\u001b[m
   org.example:test-lib:jar:7.8.9:test
   org.example:runtime-lib:jar:1.2.3:runtime
`)).toEqual([
      { name: 'org.example:classified-lib', version: '5.6.7' },
      { name: 'org.example:colored-lib', version: '9.0.1' },
      { name: 'org.example:compile-lib', version: '4.5.6' },
      { name: 'org.example:runtime-lib', version: '1.2.3' },
      { name: 'org.example:web-app', version: '8.9.0' },
    ])
  })

  it('builds Maven ecosystem queries and maps results without exposing credentials', () => {
    const dependencies = [{ name: 'org.example:runtime-lib', version: '1.2.3' }]
    expect(buildOsvQueries(dependencies)).toEqual([{
      package: { ecosystem: 'Maven', name: 'org.example:runtime-lib' },
      version: '1.2.3',
    }])
    expect(collectOsvMatches(dependencies, {
      results: [{ vulns: [{ id: 'GHSA-example' }] }],
    })).toEqual([{
      dependency: dependencies[0],
      id: 'GHSA-example',
    }])
  })

  it('fails closed when OSV returns a misaligned response', () => {
    expect(() => collectOsvMatches([{ name: 'a:b', version: '1' }], { results: [] }))
      .toThrow(/does not match/)
  })

  it('fails closed instead of accepting partial or malformed OSV results', () => {
    const dependencies = [{ name: 'a:b', version: '1' }]
    expect(() => collectOsvMatches(dependencies, {
      results: [{ vulns: [], next_page_token: 'more-results' }],
    })).toThrow(/partial result/)
    expect(() => collectOsvMatches(dependencies, {
      results: [{ vulns: [{ summary: 'missing id' }] }],
    })).toThrow(/without an identifier/)
    expect(() => collectOsvMatches(dependencies, { results: [null] }))
      .toThrow(/malformed result entry/)
    expect(() => collectOsvMatches(dependencies, { results: ['not-an-object'] }))
      .toThrow(/malformed result entry/)
  })

  it('fails closed when a runtime coordinate cannot be parsed', () => {
    expect(() => parseRuntimeDependencies('org.example:broken:jar:too:many:segments:1.0:runtime'))
      .toThrow(/Cannot parse Maven runtime dependency coordinate/)
  })
})
