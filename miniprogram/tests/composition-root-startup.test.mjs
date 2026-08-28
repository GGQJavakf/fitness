import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const miniprogramRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = readFileSync(
  resolve(miniprogramRoot, 'src', 'platform', 'weapp', 'compositionRoot.ts'),
  'utf8',
)

describe('WeChat application composition startup', () => {
  it('defers runtime adapter construction until the application is requested', () => {
    const factoryStart = source.indexOf('function createWeappApplication()')
    expect(factoryStart).toBeGreaterThan(-1)

    for (const marker of [
      '  const localUserData = createWeappUserScopedDataLifecycle()',
      '  const api = new FitnessApiClient(',
      '  initializeWeappCloudBase(__FITNESS_CLOUDBASE_ENV_ID__)',
      '  const telemetry = createWechatTelemetryReporter()',
    ]) {
      expect(source.indexOf(marker)).toBeGreaterThan(factoryStart)
    }

    expect(source).toContain(
      'export const getWeappApplication = createRetryableLazyValue(createWeappApplication)',
    )
  })
})
