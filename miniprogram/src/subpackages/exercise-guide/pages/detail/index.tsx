import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import './index.scss'
import '../../components/exercise-motion-guide/index.scss'

export default createFeaturePageLoader(
  '动作说明',
  () => import(
    /* webpackChunkName: "subpackages/exercise-guide/async/detail" */
    './DetailPage'
  ),
)
