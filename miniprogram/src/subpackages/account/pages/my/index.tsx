import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/my/index.scss'
import '../../../../presentation/components/main-navigation/index.scss'

export default createFeaturePageLoader(
  '账户功能',
  () => import(
    /* webpackChunkName: "subpackages/account/async/my" */
    '../../../../presentation/pages/my'
  ),
)
