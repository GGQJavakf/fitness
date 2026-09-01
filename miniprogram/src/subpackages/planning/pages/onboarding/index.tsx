import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/onboarding/index.scss'

export default createFeaturePageLoader(
  '档案功能',
  () => import(
    /* webpackChunkName: "subpackages/planning/async/onboarding" */
    '../../../../presentation/pages/onboarding'
  ),
)
