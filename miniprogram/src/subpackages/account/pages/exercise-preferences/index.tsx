import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/exercise-preferences/index.scss'

export default createFeaturePageLoader(
  '动作偏好',
  () => import(
    /* webpackChunkName: "subpackages/account/async/exercise-preferences" */
    '../../../../presentation/pages/exercise-preferences'
  ),
)
