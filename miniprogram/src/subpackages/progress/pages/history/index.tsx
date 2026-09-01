import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/history/index.scss'
import '../../../../presentation/components/main-navigation/index.scss'
import '../../../../presentation/components/progression-card/index.scss'

export default createFeaturePageLoader(
  '训练进展',
  () => import(
    /* webpackChunkName: "subpackages/progress/async/history" */
    '../../../../presentation/pages/history'
  ),
)
