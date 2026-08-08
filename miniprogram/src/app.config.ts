export default defineAppConfig({
  pages: [
    'presentation/pages/home/index',
    'presentation/pages/onboarding/index',
    'presentation/pages/plan-candidates/index',
    'presentation/pages/plan/index',
    'presentation/pages/plan-editor/index',
    'presentation/pages/my/index',
    'presentation/pages/workout-prepare/index',
    'presentation/pages/workout-summary/index',
    'presentation/pages/sync-conflicts/index',
    'presentation/pages/history/index',
    'presentation/pages/exercise-trend/index',
    'presentation/pages/exercise-preferences/index'
  ],
  subPackages: [
    {
      root: 'subpackages/exercise-guide',
      pages: [
        'pages/detail/index',
        'pages/workout-session/index'
      ]
    }
  ],
  window: {
    navigationBarTitleText: 'AI 健身助手',
    navigationBarBackgroundColor: '#f5f7f3',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f7f3'
  }
})
