import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

const require = createRequire(import.meta.url)
const { transformSync } = require('@babel/core')
const projectRoot = resolve(import.meta.dirname, '..')

describe('weapp standard-library compatibility transform', () => {
  it('uses pure helpers when every modern native capability is missing', () => {
    const source = [
      "const sequence = [1, 2]",
      "let receiverCalls = 0",
      "const missingSequence = () => { receiverCalls += 1; return undefined }",
      "if (missingSequence()?.includes(2) !== undefined) throw new Error('optional receiver changed')",
      "if (receiverCalls !== 1) throw new Error('optional receiver evaluated repeatedly')",
      "let sequenceCalls = 0",
      "const loadSequence = () => { sequenceCalls += 1; return sequence }",
      "if (loadSequence().flatMap(value => [value]).length !== 2) throw new Error('computed receiver failed')",
      "if (sequenceCalls !== 1) throw new Error('computed receiver evaluated repeatedly')",
      "if ('7'.padStart(2, '0') !== '07') throw new Error('padStart missing')",
      "if (!'fitness'.includes('fit')) throw new Error('string includes missing')",
      "if (!'fitness'.startsWith('fit')) throw new Error('startsWith missing')",
      "if (sequence.flatMap(value => [value, value]).join(',') !== '1,1,2,2') throw new Error('flatMap missing')",
      "if (sequence.at(-1) !== 2) throw new Error('at missing')",
      "if (!sequence.includes(2)) throw new Error('array includes missing')",
      "if (Array.from(new Set([1, 2])).length !== 2) throw new Error('Array.from missing')",
      "if (!Number.isSafeInteger(2)) throw new Error('isSafeInteger missing')",
      "if (Object.entries({ ready: true })[0][0] !== 'ready') throw new Error('entries missing')",
      "if (Object.fromEntries([['ready', true]]).ready !== true) throw new Error('fromEntries missing')",
      "if (Object.values({ ready: true })[0] !== true) throw new Error('values missing')",
      "Promise.resolve(1).finally(() => undefined).then(value => { if (value !== 1) throw new Error('Promise.finally missing') })",
    ].join('\n')
    const compatibilityOutput = transformSync(source, {
      cwd: projectRoot,
      envName: 'test',
      filename: resolve(projectRoot, 'src/platform/weapp/compatibilityProbe.ts'),
    })?.code
    const transformed = transformSync(compatibilityOutput, {
      configFile: false,
      plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')],
    })?.code

    expect(transformed).toContain('@babel/runtime-corejs3')
    expect(transformed).not.toContain('core-js/modules')
    expect(transformed).not.toMatch(
      /\.padStart\(|\.flatMap\(|\.at\(|Object\.(?:entries|fromEntries|values)\(|Array\.from\(|Number\.isSafeInteger\(|\.finally\(/,
    )

    const adapter = resolve(
      projectRoot,
      'src/platform/weapp/coreJsWeappGlobal.cjs',
    )
    const coreJsPureGlobalDetector = require.resolve(
      'core-js-pure/internals/global-this',
    )
    const probe = [
      "delete String.prototype.padStart",
      "delete String.prototype.includes",
      "delete String.prototype.startsWith",
      "delete Array.prototype.flatMap",
      "delete Array.prototype.at",
      "delete Array.prototype.includes",
      "delete Array.from",
      "delete Number.isSafeInteger",
      "delete Object.entries",
      "delete Object.fromEntries",
      "delete Object.values",
      "delete Promise.prototype.finally",
      `require.cache[${JSON.stringify(coreJsPureGlobalDetector)}] = { exports: require(${JSON.stringify(adapter)}) }`,
      `if (require(${JSON.stringify(coreJsPureGlobalDetector)}).Math !== Math) throw new Error('WeChat global adapter missing')`,
      transformed,
      "if (String.prototype.padStart !== undefined) throw new Error('String prototype was patched')",
      "if (Array.prototype.flatMap !== undefined) throw new Error('Array prototype was patched')",
      "if (Promise.prototype.finally !== undefined) throw new Error('Promise prototype was patched')",
    ].join(';')

    expect(() => execFileSync(process.execPath, ['-e', probe], {
      cwd: projectRoot,
      stdio: 'pipe',
    })).not.toThrow()
  })

  it('ignores third-party files and inherited object-map property names', () => {
    const thirdParty = transformSync("'7'.padStart(2, '0')", {
      cwd: projectRoot,
      filename: resolve(projectRoot, 'node_modules/feature-sdk/index.js'),
    })?.code
    const inheritedName = transformSync('value.constructor()', {
      cwd: projectRoot,
      filename: resolve(projectRoot, 'src/platform/weapp/inheritedNameProbe.ts'),
    })?.code

    expect(thirdParty).not.toContain('@babel/runtime-corejs3')
    expect(inheritedName).not.toContain('@babel/runtime-corejs3')
  })
})
