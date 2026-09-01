import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/plan/index.scss'
import '../../../../presentation/components/main-navigation/index.scss'

export default createFeaturePageLoader(
  '训练计划',
  () => import(
    /* webpackChunkName: "subpackages/planning/async/plan" */
    '../../../../presentation/pages/plan'
  ),
)
