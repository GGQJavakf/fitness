export type PageDestination =
  | 'HOME'
  | 'ONBOARDING'
  | 'PLAN_CANDIDATES'
  | 'PLAN_EDITOR'
  | 'PLAN'
  | 'MY'

export interface PageNavigationPort {
  open(destination: PageDestination): Promise<void> | void
  replace(destination: PageDestination): Promise<void> | void
  back(): Promise<void> | void
}

export function createNavigationUseCases(port: PageNavigationPort) {
  return {
    open(destination: PageDestination): Promise<void> {
      return Promise.resolve(port.open(destination))
    },
    replace(destination: PageDestination): Promise<void> {
      return Promise.resolve(port.replace(destination))
    },
    back(): Promise<void> {
      return Promise.resolve(port.back())
    },
  }
}
