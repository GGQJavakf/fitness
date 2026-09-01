import { createFeaturePageLoader } from '../../../shared/createFeaturePageLoader'
import '../../../../presentation/pages/plan-editor/index.scss'

export default createFeaturePageLoader(
  '计划编辑',
  () => import(
    /* webpackChunkName: "subpackages/planning/async/plan-editor" */
    '../../../../presentation/pages/plan-editor'
  ),
)
