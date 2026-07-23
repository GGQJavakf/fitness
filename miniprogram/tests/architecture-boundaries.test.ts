import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(import.meta.dirname, '../src')
const coreDirectories = ['domain', 'application']

function collectTypeScriptFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory()
      ? collectTypeScriptFiles(path)
      : ['.ts', '.tsx'].includes(extname(entry.name))
        ? [path]
        : []
  })
}

describe('client DDD boundaries', () => {
  it.each(coreDirectories)('%s exists and has no platform dependency', (name) => {
    const directory = join(sourceRoot, name)
    expect(existsSync(directory)).toBe(true)

    for (const file of collectTypeScriptFiles(directory)) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/\bwx\./)
      expect(source, file).not.toMatch(/from\s+['"]@tarojs\//)
      expect(source, file).not.toMatch(/from\s+['"]react/)
    }
  })
})
