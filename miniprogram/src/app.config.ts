export default defineAppConfig({
  pages: [
    'presentation/pages/home/index',
    'presentation/pages/onboarding/index',
    'presentation/pages/plan-candidates/index',
    'presentation/pages/plan-editor/index',
    'presentation/pages/plan/index',
    'presentation/pages/my/index',
    'presentation/pages/workout-prepare/index',
    'presentation/pages/workout-session/index',
    'presentation/pages/workout-summary/index',
    'presentation/pages/sync-conflicts/index'
  ],
  window: {
    navigationBarTitleText: 'AI 健身助手',
    navigationBarBackgroundColor: '#f7f8fa',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f7f8fa'
  }
})
