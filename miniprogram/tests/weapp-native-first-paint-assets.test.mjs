import { createRequire } from 'node:module'

import { describe, expect, it } from 'vitest'

const require = createRequire(import.meta.url)
const {
  NATIVE_FIRST_PAINT_DIAGNOSTIC,
  NATIVE_FIRST_PAINT_MARKER,
  decorateWeappNativeFirstPaintAssets,
  registeredPageWxmlAssets,
} = require('../scripts/weapp-native-first-paint-assets.cjs')
const taroBuildPlugin = require('../scripts/taro-build-plugin.cjs')

class TestSource {
  constructor(value) {
    this.value = String(value)
  }

  source() {
    return this.value
  }

  size() {
    return Buffer.byteLength(this.value)
  }
}

describe('WeChat native first-paint asset decorator', () => {
  it('protects every registered page before the Taro root is ready', () => {
    const assets = fixtureAssets()

    const result = decorateWeappNativeFirstPaintAssets(assets, TestSource)

    expect(result.protectedPages).toEqual([
      'presentation/pages/home/index.wxml',
      'subpackages/planning/pages/plan/index.wxml',
    ])
    expect(result.changed).toEqual([
      'presentation/pages/home/index.wxml',
      'subpackages/planning/pages/plan/index.wxml',
      'app.wxss',
    ])
    for (const page of result.protectedPages) {
      const source = assets[page].source().toString()
      expect(source).toContain(NATIVE_FIRST_PAINT_MARKER)
      expect(source).toContain(NATIVE_FIRST_PAINT_DIAGNOSTIC)
      expect(source).toContain('hidden="{{root.cn.length}}"')
      expect(source).toContain('<template is="taro_tmpl"')
    }
    expect(assets['app.wxss'].source().toString()).toContain(NATIVE_FIRST_PAINT_MARKER)
  })

  it('is idempotent across repeated build-asset hooks', () => {
    const assets = fixtureAssets()
    decorateWeappNativeFirstPaintAssets(assets, TestSource)

    const result = decorateWeappNativeFirstPaintAssets(assets, TestSource)

    expect(result.changed).toEqual([])
    expect(
      assets['presentation/pages/home/index.wxml'].source().toString()
        .split(NATIVE_FIRST_PAINT_MARKER),
    ).toHaveLength(2)
  })

  it('fails the build when a registered page cannot receive native protection', () => {
    const assets = fixtureAssets()
    delete assets['subpackages/planning/pages/plan/index.wxml']

    expect(() => decorateWeappNativeFirstPaintAssets(assets, TestSource)).toThrow(
      'Required WeChat build asset is missing: subpackages/planning/pages/plan/index.wxml',
    )
  })

  it('rejects unsafe page paths and is wired into the project build runner', () => {
    expect(() => registeredPageWxmlAssets({ pages: ['../outside'] })).toThrow(
      'WeChat app.json contains an unsafe page path: ../outside',
    )
    expect(taroBuildPlugin.decorateWeappNativeFirstPaintAssets).toBe(
      decorateWeappNativeFirstPaintAssets,
    )
  })
})

function fixtureAssets() {
  return {
    'app.json': new TestSource(JSON.stringify({
      pages: ['presentation/pages/home/index'],
      subPackages: [{
        root: 'subpackages/planning',
        pages: ['pages/plan/index'],
      }],
    })),
    'app.wxss': new TestSource('.app { color: #123; }\n'),
    'presentation/pages/home/index.wxml': new TestSource(
      '<import src="../../../base.wxml"/>\n<template is="taro_tmpl" data="{{root:root}}" />\n',
    ),
    'subpackages/planning/pages/plan/index.wxml': new TestSource(
      '<import src="../../../../base.wxml"/>\n<template is="taro_tmpl" data="{{root:root}}" />\n',
    ),
  }
}
