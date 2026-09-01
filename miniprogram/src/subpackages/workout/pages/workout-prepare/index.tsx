import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/workout-prepare/index.scss'

export default createFeaturePageLoader(
  '训练准备',
  () => import(
    /* webpackChunkName: "subpackages/workout/async/workout-prepare" */
    '../../../../presentation/pages/workout-prepare'
  ),
)
