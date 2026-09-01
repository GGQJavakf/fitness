import {
  existsSync,
  readdirSync,
  readFileSync,
  statSync,
} from 'node:fs'
import { dirname, extname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import ts from 'typescript'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = resolve(projectRoot, 'src')
const sourceExtensions = ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs']

function sourceFilesUnder(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory()
      ? sourceFilesUnder(path)
      : sourceExtensions.includes(extname(entry.name))
        ? [path]
        : []
  })
}

function parseSource(file) {
  return ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    file.endsWith('.tsx') || file.endsWith('.jsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  )
}

function importHasRuntimeValue(node) {
  if (!node.importClause) return true
  if (node.importClause.isTypeOnly || node.importClause.name) {
    return !node.importClause.isTypeOnly
  }
  const bindings = node.importClause.namedBindings
  if (!bindings || ts.isNamespaceImport(bindings)) return true
  return bindings.elements.some((element) => !element.isTypeOnly)
}

function staticModuleSpecifiers(file) {
  const source = parseSource(file)
  const specifiers = []

  for (const statement of source.statements) {
    if (
      ts.isImportDeclaration(statement)
      && ts.isStringLiteral(statement.moduleSpecifier)
      && importHasRuntimeValue(statement)
    ) {
      specifiers.push(statement.moduleSpecifier.text)
    }
    if (
      ts.isExportDeclaration(statement)
      && !statement.isTypeOnly
      && statement.moduleSpecifier
      && ts.isStringLiteral(statement.moduleSpecifier)
    ) {
      specifiers.push(statement.moduleSpecifier.text)
    }
    if (
      ts.isImportEqualsDeclaration(statement)
      && !statement.isTypeOnly
      && ts.isExternalModuleReference(statement.moduleReference)
      && statement.moduleReference.expression
      && ts.isStringLiteral(statement.moduleReference.expression)
    ) {
      specifiers.push(statement.moduleReference.expression.text)
    }
  }

  function visit(node) {
    // import('...') is intentionally an asynchronous edge and must not join
    // the pre-first-paint dependency closure.
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && node.expression.text === 'require'
      && node.arguments.length === 1
      && ts.isStringLiteral(node.arguments[0])
    ) {
      specifiers.push(node.arguments[0].text)
    }
    ts.forEachChild(node, visit)
  }
  ts.forEachChild(source, visit)
  return specifiers
}

function resolveLocalModule(fromFile, specifier) {
  if (!specifier.startsWith('.')) return undefined
  const candidate = resolve(dirname(fromFile), specifier)
  const candidates = [
    candidate,
    ...sourceExtensions.map((extension) => `${candidate}${extension}`),
    ...sourceExtensions.map((extension) => resolve(candidate, `index${extension}`)),
  ]
  return candidates.find((path) => (
    existsSync(path)
    && statSync(path).isFile()
    && sourceExtensions.includes(extname(path))
  ))
}

function synchronousClosure(entryFiles) {
  const pending = [...entryFiles]
  const visited = new Set()
  while (pending.length) {
    const file = pending.pop()
    if (!file || visited.has(file)) continue
    visited.add(file)
    for (const specifier of staticModuleSpecifiers(file)) {
      const dependency = resolveLocalModule(file, specifier)
      if (dependency && !visited.has(dependency)) pending.push(dependency)
    }
  }
  return [...visited].map((file) => relative(sourceRoot, file).replaceAll('\\', '/'))
}

describe('WeChat first-paint import boundary', () => {
  it('keeps the main bootstrap synchronous closure free of business composition graphs', () => {
    const closure = synchronousClosure([
      resolve(sourceRoot, 'app.tsx'),
      resolve(sourceRoot, 'presentation/pages/home/index.tsx'),
    ])
    const forbidden = [
      /platform\/weapp\/compositionRoot\.[jt]sx?$/,
      /infrastructure\/api\/client\.[jt]sx?$/,
      /(?:startup|planning|workout|progress|account|exerciseGuide)CompositionRoot\.[jt]sx?$/,
    ]

    expect(
      closure.filter((file) => forbidden.some((pattern) => pattern.test(file))),
      `main-package synchronous closure:\n${closure.sort().join('\n')}`,
    ).toEqual([])
  })

  it('does not let any page depend on the retired all-features service locator', () => {
    const offenders = sourceFilesUnder(sourceRoot)
      .filter((file) => /[\\/]pages[\\/].+[\\/]index\.[jt]sx?$/.test(file))
      .filter((file) => {
        const source = readFileSync(file, 'utf8')
        return source.includes('getWeappApplication') || source.includes('/compositionRoot')
      })
      .map((file) => relative(sourceRoot, file).replaceAll('\\', '/'))
      .sort()

    expect(offenders).toEqual([])
  })
})
