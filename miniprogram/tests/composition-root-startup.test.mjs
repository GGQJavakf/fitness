import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import ts from 'typescript'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const weappRoot = resolve(projectRoot, 'src/platform/weapp')

const featureRoots = {
  startupCompositionRoot: 'getStartupApplication',
  planningCompositionRoot: 'getPlanningApplication',
  workoutCompositionRoot: 'getWorkoutApplication',
  progressCompositionRoot: 'getProgressApplication',
  accountCompositionRoot: 'getAccountApplication',
  exerciseGuideCompositionRoot: 'getExerciseGuideApplication',
}

function filesUnder(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? filesUnder(path) : [path]
  })
}

function findFeatureRoot(basename) {
  const matches = filesUnder(weappRoot).filter((file) => (
    file.endsWith(`/${basename}.ts`) || file.endsWith(`\\${basename}.ts`)
  ))
  expect(matches, `${basename}.ts must exist exactly once`).toHaveLength(1)
  return matches[0]
}

function exportedLazyGetters(file) {
  const source = ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  const getters = []
  for (const statement of source.statements) {
    if (!ts.isVariableStatement(statement)) continue
    const exported = statement.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword)
    if (!exported) continue
    for (const declaration of statement.declarationList.declarations) {
      if (
        ts.isIdentifier(declaration.name)
        && declaration.initializer
        && ts.isCallExpression(declaration.initializer)
        && ts.isIdentifier(declaration.initializer.expression)
        && declaration.initializer.expression.text === 'createRetryableLazyValue'
      ) {
        getters.push(declaration.name.text)
      }
    }
  }
  return getters
}

describe('WeChat feature composition roots', () => {
  it('replaces the all-features service locator with six retryable lazy facades', () => {
    const roots = Object.entries(featureRoots).map(([basename, getter]) => {
      const file = findFeatureRoot(basename)
      const source = readFileSync(file, 'utf8')
      expect(source).toContain('createRetryableLazyValue')
      expect(exportedLazyGetters(file)).toEqual([getter])
      return { basename, source }
    })

    expect(existsSync(resolve(weappRoot, 'compositionRoot.ts'))).toBe(false)
    expect(roots).toHaveLength(6)
  })

  it('does not couple one feature composition root to another feature root', () => {
    for (const [basename] of Object.entries(featureRoots)) {
      const source = readFileSync(findFeatureRoot(basename), 'utf8')
      const foreignRoots = Object.keys(featureRoots).filter((candidate) => candidate !== basename)
      expect(
        foreignRoots.filter((candidate) => source.includes(candidate)),
        `${basename} must build only its own facade`,
      ).toEqual([])
    }
  })

  it('removes all imports and calls to the retired global application getter', () => {
    const offenders = filesUnder(resolve(projectRoot, 'src'))
      .filter((file) => /\.[jt]sx?$/.test(file))
      .filter((file) => readFileSync(file, 'utf8').includes('getWeappApplication'))
    expect(offenders).toEqual([])
  })
})
