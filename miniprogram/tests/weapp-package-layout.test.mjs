import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import ts from 'typescript'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const expectedSubpackages = {
  'subpackages/startup': ['pages/home/index'],
  'subpackages/planning': [
    'pages/onboarding/index',
    'pages/plan-candidates/index',
    'pages/plan-presets/index',
    'pages/plan/index',
    'pages/plan-editor/index',
  ],
  'subpackages/workout': [
    'pages/workout-prepare/index',
    'pages/workout-session/index',
    'pages/workout-summary/index',
  ],
  'subpackages/progress': [
    'pages/sync-conflicts/index',
    'pages/history/index',
    'pages/exercise-trend/index',
  ],
  'subpackages/account': [
    'pages/my/index',
    'pages/exercise-preferences/index',
  ],
  'subpackages/exercise-guide': ['pages/detail/index'],
}

const expectedRoutes = {
  HOME: '/subpackages/startup/pages/home/index',
  ONBOARDING: '/subpackages/planning/pages/onboarding/index',
  PLAN_CANDIDATES: '/subpackages/planning/pages/plan-candidates/index',
  PLAN_PRESETS: '/subpackages/planning/pages/plan-presets/index',
  PLAN: '/subpackages/planning/pages/plan/index',
  PLAN_EDITOR: '/subpackages/planning/pages/plan-editor/index',
  MY: '/subpackages/account/pages/my/index',
  WORKOUT_PREPARE: '/subpackages/workout/pages/workout-prepare/index',
  WORKOUT_SESSION: '/subpackages/workout/pages/workout-session/index',
  WORKOUT_SUMMARY: '/subpackages/workout/pages/workout-summary/index',
  SYNC_CONFLICTS: '/subpackages/progress/pages/sync-conflicts/index',
  HISTORY: '/subpackages/progress/pages/history/index',
  EXERCISE_TREND: '/subpackages/progress/pages/exercise-trend/index',
  EXERCISE_DETAIL: '/subpackages/exercise-guide/pages/detail/index',
  EXERCISE_PREFERENCES: '/subpackages/account/pages/exercise-preferences/index',
}

function parse(file) {
  return ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
}

function propertyName(property) {
  const name = property.name
  return name && (ts.isIdentifier(name) || ts.isStringLiteral(name)) ? name.text : undefined
}

function stringArray(node) {
  if (!node || !ts.isArrayLiteralExpression(node)) return []
  return node.elements.filter(ts.isStringLiteral).map((element) => element.text)
}

function objectProperty(object, name) {
  return object.properties.find((property) => (
    ts.isPropertyAssignment(property) && propertyName(property) === name
  ))
}

function readAppLayout() {
  const source = parse(resolve(projectRoot, 'src/app.config.ts'))
  let config
  function visit(node) {
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && node.expression.text === 'defineAppConfig'
      && node.arguments[0]
      && ts.isObjectLiteralExpression(node.arguments[0])
    ) {
      config = node.arguments[0]
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  if (!config) throw new Error('defineAppConfig object was not found')

  const pagesProperty = objectProperty(config, 'pages')
  const subpackagesProperty = objectProperty(config, 'subPackages')
  if (!pagesProperty || !subpackagesProperty) throw new Error('pages/subPackages were not found')

  const subpackages = {}
  if (!ts.isArrayLiteralExpression(subpackagesProperty.initializer)) {
    throw new Error('subPackages must be an array literal')
  }
  for (const element of subpackagesProperty.initializer.elements) {
    if (!ts.isObjectLiteralExpression(element)) continue
    const rootProperty = objectProperty(element, 'root')
    const childPagesProperty = objectProperty(element, 'pages')
    if (
      rootProperty
      && ts.isStringLiteral(rootProperty.initializer)
      && childPagesProperty
    ) {
      subpackages[rootProperty.initializer.text] = stringArray(childPagesProperty.initializer)
    }
  }
  return {
    pages: stringArray(pagesProperty.initializer),
    subpackages,
  }
}

function readPageDestinations() {
  const source = parse(resolve(projectRoot, 'src/application/navigation.ts'))
  for (const statement of source.statements) {
    if (
      ts.isTypeAliasDeclaration(statement)
      && statement.name.text === 'PageDestination'
      && ts.isUnionTypeNode(statement.type)
    ) {
      return statement.type.types
        .filter(ts.isLiteralTypeNode)
        .map((type) => type.literal)
        .filter(ts.isStringLiteral)
        .map((literal) => literal.text)
    }
  }
  throw new Error('PageDestination union was not found')
}

function readPageRoutes() {
  const source = parse(resolve(projectRoot, 'src/platform/weapp/adapters.ts'))
  for (const statement of source.statements) {
    if (!ts.isVariableStatement(statement)) continue
    for (const declaration of statement.declarationList.declarations) {
      if (
        ts.isIdentifier(declaration.name)
        && declaration.name.text === 'pageRoutes'
        && declaration.initializer
        && ts.isObjectLiteralExpression(declaration.initializer)
      ) {
        return Object.fromEntries(declaration.initializer.properties.flatMap((property) => {
          if (!ts.isPropertyAssignment(property)) return []
          const name = propertyName(property)
          if (!name) return []
          if (ts.isStringLiteral(property.initializer)) {
            return [[name, property.initializer.text]]
          }
          if (ts.isObjectLiteralExpression(property.initializer)) {
            const pathProperty = objectProperty(property.initializer, 'path')
            if (pathProperty && ts.isStringLiteral(pathProperty.initializer)) {
              return [[name, pathProperty.initializer.text]]
            }
          }
          return []
        }))
      }
    }
  }
  throw new Error('pageRoutes object was not found')
}

describe('WeChat package layout', () => {
  it('keeps only the paint-safe bootstrap page in the main package', () => {
    const layout = readAppLayout()
    expect(layout.pages).toEqual(['presentation/pages/home/index'])
    expect(layout.subpackages).toEqual(expectedSubpackages)

    const allRegisteredPages = [
      ...layout.pages,
      ...Object.entries(layout.subpackages).flatMap(([root, pages]) => (
        pages.map((page) => `${root}/${page}`)
      )),
    ]
    expect(new Set(allRegisteredPages).size).toBe(allRegisteredPages.length)
  })

  it('registers every PageDestination exactly once at its isolated package path', () => {
    const destinations = readPageDestinations().sort()
    const routes = readPageRoutes()
    expect(Object.keys(routes).sort()).toEqual(destinations)
    expect(routes).toEqual(expectedRoutes)

    const layout = readAppLayout()
    const registeredBusinessPages = Object.entries(layout.subpackages).flatMap(([root, pages]) => (
      pages.map((page) => `/${root}/${page}`)
    ))
    expect(Object.values(routes).sort()).toEqual(registeredBusinessPages.sort())
    expect(new Set(Object.values(routes)).size).toBe(Object.values(routes).length)
  })

  it('keeps authentication recovery on the paint-safe bootstrap instead of the business HOME route', () => {
    const adapters = readFileSync(resolve(projectRoot, 'src/platform/weapp/adapters.ts'), 'utf8')
    const recovery = readFileSync(resolve(projectRoot, 'src/platform/weapp/appRecovery.ts'), 'utf8')
    expect(adapters).not.toMatch(/LOGIN\s*:\s*['"]HOME['"]/)
    expect(adapters).toContain('/presentation/pages/home/index')
    expect(recovery).toContain("const SAFE_HOME_ROUTE = '/presentation/pages/home/index'")
    expect(recovery).toMatch(/reLaunch\([\s\S]*url:\s*SAFE_HOME_ROUTE/)
  })
})
