import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/workout-summary/index.scss'

export default createFeaturePageLoader(
  '训练总结',
  () => import(
    /* webpackChunkName: "subpackages/workout/async/workout-summary" */
    '../../../../presentation/pages/workout-summary'
  ),
)
