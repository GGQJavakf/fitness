import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/exercise-trend/index.scss'

export default createFeaturePageLoader(
  '动作趋势',
  () => import(
    /* webpackChunkName: "subpackages/progress/async/exercise-trend" */
    '../../../../presentation/pages/exercise-trend'
  ),
)
