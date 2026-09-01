import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import { currentWeappRouteParameter } from '../adapters'

function createExerciseGuideApplication() {
  const core = getWeappFeatureCore()
  return {
    navigation: core.navigation,
    getExercise: (idOrCode: string) => core.api.getExercise(idOrCode),
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}

export const getExerciseGuideApplication = createRetryableLazyValue(
  createExerciseGuideApplication,
)
