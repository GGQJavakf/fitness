import { describe, expect, it } from 'vitest'

import {
  MIN_PACKAGE_HEADROOM_BYTES,
  MAIN_PACKAGE_LIMIT_BYTES,
  MAIN_PACKAGE_WARNING_BYTES,
  evaluatePackageSize,
  packageSizes,
} from '../scripts/check-package-size.mjs'

describe('WeChat main package size guard', () => {
  it('uses a 2 MiB hard limit with an earlier warning threshold', () => {
    expect(MAIN_PACKAGE_LIMIT_BYTES).toBe(2 * 1024 * 1024)
    expect(MIN_PACKAGE_HEADROOM_BYTES).toBe(128 * 1024)
    expect(MAIN_PACKAGE_WARNING_BYTES).toBeLessThan(MAIN_PACKAGE_LIMIT_BYTES)
  })

  it('passes, warns, and blocks at the warning, reserve, and hard-limit boundaries', () => {
    expect(evaluatePackageSize(MAIN_PACKAGE_WARNING_BYTES - 1))
      .toMatchObject({ level: 'PASS' })
    expect(evaluatePackageSize(MAIN_PACKAGE_WARNING_BYTES))
      .toMatchObject({ level: 'WARN' })
    expect(evaluatePackageSize(MAIN_PACKAGE_LIMIT_BYTES - MIN_PACKAGE_HEADROOM_BYTES))
      .toMatchObject({ level: 'WARN', headroomBytes: MIN_PACKAGE_HEADROOM_BYTES })
    expect(evaluatePackageSize(MAIN_PACKAGE_LIMIT_BYTES - MIN_PACKAGE_HEADROOM_BYTES + 1))
      .toMatchObject({ level: 'BLOCKED', reason: 'INSUFFICIENT_HEADROOM' })
    expect(evaluatePackageSize(MAIN_PACKAGE_LIMIT_BYTES))
      .toMatchObject({ level: 'BLOCKED', reason: 'INSUFFICIENT_HEADROOM' })
    expect(evaluatePackageSize(MAIN_PACKAGE_LIMIT_BYTES + 1))
      .toMatchObject({ level: 'BLOCKED', reason: 'HARD_LIMIT_EXCEEDED' })
  })

  it('adds top-level shared JavaScript to non-independent subpackage source size', () => {
    const sizes = packageSizes(
      [
        { path: 'app.js', sizeBytes: 100 },
        { path: 'taro.js', sizeBytes: 20 },
        { path: 'runtime.js', sizeBytes: 5 },
        { path: 'presentation/pages/home/index.js', sizeBytes: 50 },
        { path: 'subpackages/exercise-guide/pages/detail.js', sizeBytes: 60 },
        { path: 'subpackages/exercise-guide/assets/goblet-squat-01-setup.jpg', sizeBytes: 80 },
        { path: 'subpackages/standalone/pages/index.js', sizeBytes: 40 },
      ],
      [
        { root: 'subpackages/exercise-guide' },
        { root: 'subpackages/standalone', independent: true },
      ]
    )

    expect(sizes.mainBytes).toBe(175)
    expect(sizes.sharedTopLevelJavaScriptBytes).toBe(125)
    expect(sizes.subpackages).toEqual([
      {
        root: 'subpackages/exercise-guide',
        independent: false,
        rawBytes: 140,
        sharedBytes: 125,
        totalBytes: 265,
      },
      {
        root: 'subpackages/standalone',
        independent: true,
        rawBytes: 40,
        sharedBytes: 0,
        totalBytes: 40,
      },
    ])
  })

  it('blocks the exact source closure that the preview uploader reported as 2169 KiB', () => {
    const sizes = packageSizes(
      [
        { path: 'app.js', sizeBytes: 116_174 },
        { path: 'taro.js', sizeBytes: 133_054 },
        { path: 'vendors.js', sizeBytes: 18_232 },
        { path: 'subpackages/exercise-guide/source.bin', sizeBytes: 1_954_363 },
      ],
      [{ root: 'subpackages/exercise-guide' }]
    )

    expect(sizes.subpackages[0]?.totalBytes).toBe(2_221_823)
    expect(evaluatePackageSize(sizes.subpackages[0]?.totalBytes ?? 0))
      .toMatchObject({ level: 'BLOCKED' })
  })
})
