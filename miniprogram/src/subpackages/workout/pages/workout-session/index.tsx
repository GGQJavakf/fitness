import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/workout-session/index.scss'
import '../../../exercise-guide/components/exercise-motion-guide/index.scss'

export default createFeaturePageLoader(
  '训练记录',
  () => import(
    /* webpackChunkName: "subpackages/workout/async/workout-session" */
    '../../../../presentation/pages/workout-session'
  ),
)
