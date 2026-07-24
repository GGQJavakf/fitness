import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(import.meta.dirname, '../src')
const coreDirectories = ['domain', 'application']
const pageRoot = join(sourceRoot, 'presentation', 'pages')
const platformRoot = join(sourceRoot, 'platform', 'weapp')

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
      if (name === 'application') {
        expect(source, file).not.toMatch(/from\s+['"][^'"]*infrastructure/)
      }
    }
  })

  it('keeps page navigation, network, storage, and login away from direct platform calls', () => {
    for (const file of collectTypeScriptFiles(pageRoot)) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/\bwx\./)
      expect(source, file).not.toMatch(/from\s+['"]@tarojs\/taro['"]/)
    }
  })

  it('concentrates direct Taro platform imports under platform/weapp', () => {
    const allSourceFiles = collectTypeScriptFiles(sourceRoot)
    for (const file of allSourceFiles.filter((path) => !path.startsWith(platformRoot))) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/from\s+['"]@tarojs\/taro['"]/)
      expect(source, file).not.toMatch(/\bwx\./)
    }

    const platformSources = collectTypeScriptFiles(platformRoot)
      .map((file) => readFileSync(file, 'utf8'))
      .join('\n')
    expect(platformSources).toMatch(/from\s+['"]@tarojs\/taro['"]/)
  })
})
