import type { getStartupApplication } from '../../../../platform/weapp/featureRoots/startupCompositionRoot'
import { recordStartupFailure } from '../../../../platform/weapp/startupDiagnostics'

export type StartupApplication = ReturnType<typeof getStartupApplication>

export async function loadStartupApplication(): Promise<StartupApplication> {
  let module: typeof import('../../../../platform/weapp/featureRoots/startupCompositionRoot')
  try {
    module = await import(
      /* webpackChunkName: "subpackages/startup/async/startup-application" */
      '../../../../platform/weapp/featureRoots/startupCompositionRoot'
    )
  } catch (error) {
    recordStartupFailure('STARTUP_MODULE_LOAD', 'MODULE_LOAD')
    throw error
  }
  try {
    return module.getStartupApplication()
  } catch (error) {
    recordStartupFailure('STARTUP_COMPOSITION_ROOT', 'COMPOSITION_ROOT')
    throw error
  }
}
