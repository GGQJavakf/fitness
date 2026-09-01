import Taro from '@tarojs/taro'

import { recordStartupFailure } from './startupDiagnostics'

const SAFE_HOME_ROUTE = '/presentation/pages/home/index'
const STARTUP_SUBPACKAGE = 'startup'
const STARTUP_HOME_ROUTE = '/subpackages/startup/pages/home/index'

interface WeappBootstrapRuntime {
  loadSubpackage?(options: {
    name: string
    success: () => void
    fail: (error: unknown) => void
  }): unknown
}

declare const wx: WeappBootstrapRuntime | undefined

export async function recoverWeappHome(): Promise<void> {
  try {
    await Taro.reLaunch({
      url: SAFE_HOME_ROUTE,
    })
  } catch (error) {
    recordStartupFailure('SAFE_HOME_NAVIGATION', 'NAVIGATION')
    throw error
  }
}

export async function openWeappStartupHome(): Promise<void> {
  const runtime = typeof wx === 'undefined' ? undefined : wx
  const loadSubpackage = runtime?.loadSubpackage
  if (loadSubpackage) {
    try {
      await new Promise<void>((resolve, reject) => {
        loadSubpackage.call(runtime, {
          name: STARTUP_SUBPACKAGE,
          success: resolve,
          fail: reject,
        })
      })
    } catch (error) {
      recordStartupFailure('BOOTSTRAP_SUBPACKAGE_LOAD', 'SUBPACKAGE_LOAD')
      throw error
    }
  }
  try {
    await Taro.redirectTo({ url: STARTUP_HOME_ROUTE })
  } catch (error) {
    recordStartupFailure('BOOTSTRAP_REDIRECT', 'NAVIGATION')
    throw error
  }
}
