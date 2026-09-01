export default defineAppConfig({
  pages: [
    'presentation/pages/home/index'
  ],
  subPackages: [
    {
      root: 'subpackages/startup',
      name: 'startup',
      pages: ['pages/home/index']
    },
    {
      root: 'subpackages/planning',
      name: 'planning',
      pages: [
        'pages/onboarding/index',
        'pages/plan-candidates/index',
        'pages/plan-presets/index',
        'pages/plan/index',
        'pages/plan-editor/index'
      ]
    },
    {
      root: 'subpackages/workout',
      name: 'workout',
      pages: [
        'pages/workout-prepare/index',
        'pages/workout-session/index',
        'pages/workout-summary/index'
      ]
    },
    {
      root: 'subpackages/progress',
      name: 'progress',
      pages: [
        'pages/sync-conflicts/index',
        'pages/history/index',
        'pages/exercise-trend/index'
      ]
    },
    {
      root: 'subpackages/account',
      name: 'account',
      pages: [
        'pages/my/index',
        'pages/exercise-preferences/index'
      ]
    },
    {
      root: 'subpackages/exercise-guide',
      name: 'exercise-guide',
      pages: ['pages/detail/index']
    }
  ],
  window: {
    navigationBarTitleText: 'AI 健身助手',
    navigationBarBackgroundColor: '#f5f7f3',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f7f3'
  }
})
