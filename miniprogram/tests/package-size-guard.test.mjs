import { describe, expect, it } from 'vitest'

import {
  MAIN_PACKAGE_LIMIT_BYTES,
  MAIN_PACKAGE_WARNING_BYTES,
  evaluatePackageSize,
  packageSizes,
} from '../scripts/check-package-size.mjs'

describe('WeChat main package size guard', () => {
  it('uses a 2 MiB hard limit with an earlier warning threshold', () => {
    expect(MAIN_PACKAGE_LIMIT_BYTES).toBe(2 * 1024 * 1024)
    expect(MAIN_PACKAGE_WARNING_BYTES).toBeLessThan(MAIN_PACKAGE_LIMIT_BYTES)
  })

  it('passes, warns, and blocks at the expected aggregate boundaries', () => {
    expect(evaluatePackageSize(MAIN_PACKAGE_WARNING_BYTES - 1))
      .toMatchObject({ level: 'PASS' })
    expect(evaluatePackageSize(MAIN_PACKAGE_WARNING_BYTES))
      .toMatchObject({ level: 'WARN' })
    expect(evaluatePackageSize(MAIN_PACKAGE_LIMIT_BYTES + 1))
      .toMatchObject({ level: 'BLOCKED' })
  })

  it('excludes declared subpackage roots from the main-package aggregate', () => {
    const sizes = packageSizes(
      [
        { path: 'app.js', sizeBytes: 100 },
        { path: 'subpackages/exercise-guide/pages/detail.js', sizeBytes: 60 },
        { path: 'subpackages/exercise-guide/assets/goblet-squat-01-setup.jpg', sizeBytes: 80 },
      ],
      ['subpackages/exercise-guide']
    )

    expect(sizes.mainBytes).toBe(100)
    expect(sizes.subpackages).toEqual([
      { root: 'subpackages/exercise-guide', totalBytes: 140 },
    ])
  })
})
