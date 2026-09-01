import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/sync-conflicts/index.scss'

export default createFeaturePageLoader(
  '同步处理',
  () => import(
    /* webpackChunkName: "subpackages/progress/async/sync-conflicts" */
    '../../../../presentation/pages/sync-conflicts'
  ),
)
