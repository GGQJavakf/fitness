export type PageDestination =
  | 'HOME'
  | 'ONBOARDING'
  | 'PLAN_CANDIDATES'
  | 'PLAN'
  | 'PLAN_EDITOR'
  | 'MY'
  | 'WORKOUT_PREPARE'
  | 'WORKOUT_SESSION'
  | 'WORKOUT_SUMMARY'
  | 'SYNC_CONFLICTS'
  | 'HISTORY'
  | 'EXERCISE_TREND'
  | 'EXERCISE_DETAIL'
  | 'EXERCISE_PREFERENCES'

export type NavigationParameters = Readonly<Record<string, string>>

export interface PageNavigationPort {
  open(destination: PageDestination, parameters?: NavigationParameters): Promise<void> | void
  replace(destination: PageDestination, parameters?: NavigationParameters): Promise<void> | void
  back(): Promise<void> | void
}

export function createNavigationUseCases(port: PageNavigationPort) {
  return {
    open(destination: PageDestination, parameters?: NavigationParameters): Promise<void> {
      return Promise.resolve(port.open(destination, parameters))
    },
    replace(destination: PageDestination, parameters?: NavigationParameters): Promise<void> {
      return Promise.resolve(port.replace(destination, parameters))
    },
    back(): Promise<void> {
      return Promise.resolve(port.back())
    },
  }
}
