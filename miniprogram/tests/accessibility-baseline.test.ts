import { readdirSync, readFileSync } from 'node:fs'
import { extname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(projectRoot, 'src')

function filesBelow(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? filesBelow(path) : [path]
  })
}

describe('mini-program accessibility baseline', () => {
  it('keeps every explicit supporting-copy font size at or above 22 px', () => {
    const undersized = filesBelow(sourceRoot)
      .filter((path) => extname(path) === '.scss')
      .flatMap((path) => readFileSync(path, 'utf8').split(/\r?\n/).map((line, index) => ({
        line: index + 1,
        path: path.slice(projectRoot.length + 1).replace(/\\/g, '/'),
        source: line.trim(),
        sizes: [...line.matchAll(/font-size:\s*([0-9.]+)px/g)].map((match) => Number(match[1])),
      })))
      .filter(({ sizes }) => sizes.some((size) => size < 22))
      .map(({ path, line, source }) => `${path}:${line} ${source}`)

    expect(undersized, undersized.join('\n')).toEqual([])
  })

  it('keeps key navigation, guide, workout, and editor targets at least 88 px high', () => {
    expect(readFileSync(resolve(projectRoot, 'src/app.scss'), 'utf8'))
      .toMatch(/button,\s*\ninput[\s\S]*?min-height:\s*88px\s*!important/)
    const expectations: Array<[string, RegExp]> = [
      ['src/presentation/components/main-navigation/index.scss', /main-navigation__item[\s\S]*?min-height:\s*88px/],
      ['src/subpackages/exercise-guide/components/exercise-motion-guide/index.scss', /motion-guide__stage-tab[\s\S]*?min-height:\s*88px/],
      ['src/presentation/pages/workout-session/index.scss', /metric-input-wrap[\s\S]*?min-height:\s*88px/],
      ['src/presentation/pages/plan-editor/index.scss', /editor-field__input[\s\S]*?min-height:\s*88px/],
    ]

    for (const [path, pattern] of expectations) {
      expect(readFileSync(resolve(projectRoot, path), 'utf8'), path).toMatch(pattern)
    }
  })
})
