import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/plan-presets/index.scss'

export default createFeaturePageLoader(
  '训练预设',
  () => import(
    /* webpackChunkName: "subpackages/planning/async/plan-presets" */
    '../../../../presentation/pages/plan-presets'
  ),
)
