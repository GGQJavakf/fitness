import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/plan-candidates/index.scss'

export default createFeaturePageLoader(
  '训练方案',
  () => import(
    /* webpackChunkName: "subpackages/planning/async/plan-candidates" */
    '../../../../presentation/pages/plan-candidates'
  ),
)
