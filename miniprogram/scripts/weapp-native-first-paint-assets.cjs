'use strict'

const { RawSource } = require('webpack').sources

const NATIVE_FIRST_PAINT_MARKER = 'fitness-native-first-paint-v1'
const NATIVE_FIRST_PAINT_DIAGNOSTIC = 'WB-P00 · R5'

const NATIVE_FIRST_PAINT_WXML = [
  `<view class="fitness-native-first-paint ${NATIVE_FIRST_PAINT_MARKER}" hidden="{{root.cn.length}}">`,
  '  <view class="fitness-native-first-paint__brand">AI 科学训练系统</view>',
  '  <view class="fitness-native-first-paint__title">正在建立安全页面…</view>',
  `  <view class="fitness-native-first-paint__code">诊断码 ${NATIVE_FIRST_PAINT_DIAGNOSTIC}</view>`,
  '</view>',
].join('\n')

const NATIVE_FIRST_PAINT_WXSS = [
  `/* ${NATIVE_FIRST_PAINT_MARKER} */`,
  '.fitness-native-first-paint {',
  '  position: fixed;',
  '  top: 0;',
  '  right: 0;',
  '  bottom: 0;',
  '  left: 0;',
  '  z-index: 2147483647;',
  '  box-sizing: border-box;',
  '  display: flex;',
  '  flex-direction: column;',
  '  align-items: center;',
  '  justify-content: center;',
  '  min-height: 100vh;',
  '  padding: 48rpx;',
  '  color: #123f36;',
  '  background: #f5f7f3;',
  '}',
  '.fitness-native-first-paint__brand {',
  '  color: #087b68;',
  '  font-size: 26rpx;',
  '  font-weight: 700;',
  '  letter-spacing: 4rpx;',
  '}',
  '.fitness-native-first-paint__title {',
  '  margin-top: 22rpx;',
  '  font-size: 34rpx;',
  '  font-weight: 700;',
  '}',
  '.fitness-native-first-paint__code {',
  '  margin-top: 18rpx;',
  '  color: #718078;',
  '  font-size: 22rpx;',
  '}',
].join('\n')

function decorateWeappNativeFirstPaintAssets(assets, Source = RawSource) {
  if (!assets || typeof assets !== 'object' || Array.isArray(assets)) {
    throw new TypeError('WeChat build assets must be an object')
  }
  if (typeof Source !== 'function') {
    throw new TypeError('WeChat build asset decorator requires a Source constructor')
  }

  const appConfig = parseAppConfig(readRequiredAsset(assets, 'app.json'))
  const pageAssets = registeredPageWxmlAssets(appConfig)
  const changed = []

  for (const path of pageAssets) {
    const source = readRequiredAsset(assets, path)
    if (source.includes(NATIVE_FIRST_PAINT_MARKER)) continue
    if (!/<template\b[^>]*\bis=["']taro_tmpl["']/.test(source)) {
      throw new Error(`Registered WeChat page has no Taro root template: ${path}`)
    }
    assets[path] = new Source(`${NATIVE_FIRST_PAINT_WXML}\n${source}`)
    changed.push(path)
  }

  const appStyle = readRequiredAsset(assets, 'app.wxss')
  if (!appStyle.includes(NATIVE_FIRST_PAINT_MARKER)) {
    assets['app.wxss'] = new Source(`${appStyle.trimEnd()}\n\n${NATIVE_FIRST_PAINT_WXSS}\n`)
    changed.push('app.wxss')
  }

  return Object.freeze({
    changed: Object.freeze(changed),
    protectedPages: Object.freeze(pageAssets),
  })
}

function readRequiredAsset(assets, path) {
  const asset = assets[path]
  if (!asset || typeof asset.source !== 'function') {
    throw new Error(`Required WeChat build asset is missing: ${path}`)
  }
  return asset.source().toString()
}

function parseAppConfig(source) {
  let config
  try {
    config = JSON.parse(source)
  } catch (error) {
    throw new Error(`WeChat app.json is invalid: ${error.message}`)
  }
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    throw new Error('WeChat app.json must contain an object')
  }
  return config
}

function registeredPageWxmlAssets(appConfig) {
  if (!Array.isArray(appConfig.pages)) {
    throw new Error('WeChat app.json must declare main-package pages')
  }
  const paths = appConfig.pages.map(pageWxmlAsset)
  const subpackages = appConfig.subPackages ?? appConfig.subpackages ?? []
  if (!Array.isArray(subpackages)) {
    throw new Error('WeChat app.json subPackages must be an array')
  }
  for (const subpackage of subpackages) {
    if (
      !subpackage
      || typeof subpackage !== 'object'
      || typeof subpackage.root !== 'string'
      || !Array.isArray(subpackage.pages)
    ) {
      throw new Error('WeChat app.json contains an invalid subpackage')
    }
    for (const page of subpackage.pages) {
      paths.push(pageWxmlAsset(`${subpackage.root}/${page}`))
    }
  }
  return [...new Set(paths)].sort()
}

function pageWxmlAsset(page) {
  if (typeof page !== 'string' || !page || page.startsWith('/') || page.includes('..')) {
    throw new Error(`WeChat app.json contains an unsafe page path: ${String(page)}`)
  }
  return `${page}.wxml`
}

module.exports = {
  NATIVE_FIRST_PAINT_DIAGNOSTIC,
  NATIVE_FIRST_PAINT_MARKER,
  NATIVE_FIRST_PAINT_WXML,
  NATIVE_FIRST_PAINT_WXSS,
  decorateWeappNativeFirstPaintAssets,
  registeredPageWxmlAssets,
}
