import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { runSingleFlight } from '../src/presentation/pages/home/loginSingleFlight'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('home page product-quality contract', () => {
  it('communicates the product value and keeps safety boundaries explicit', () => {
    const page = source('src/presentation/pages/home/index.tsx')

    for (const label of ['科学计划', '规则计算', '长期进化']) {
      expect(page).toContain(label)
    }
    expect(page).toContain('一般健身计划，不提供医疗诊断或康复处方')
    expect(page).not.toContain('P0 · 微信小程序 · KG')
  })

  it('prevents duplicate login attempts and releases the state after completion', async () => {
    const state = { current: false }
    const busyStates: boolean[] = []
    let finishLogin: (() => void) | undefined
    let callCount = 0
    const operation = (): Promise<void> => {
      callCount += 1
      return new Promise((resolve) => {
        finishLogin = resolve
      })
    }

    const firstAttempt = runSingleFlight(state, (busy) => busyStates.push(busy), operation)
    const duplicateAttempt = runSingleFlight(state, (busy) => busyStates.push(busy), operation)

    expect(callCount).toBe(1)
    expect(state.current).toBe(true)
    expect(busyStates).toEqual([true])

    finishLogin?.()
    await Promise.all([firstAttempt, duplicateAttempt])

    expect(state.current).toBe(false)
    expect(busyStates).toEqual([true, false])
  })

  it('releases the login state after a rejected operation', async () => {
    const state = { current: false }
    const busyStates: boolean[] = []
    const failure = new Error('login failed')

    await expect(runSingleFlight(
      state,
      (busy) => busyStates.push(busy),
      () => Promise.reject(failure),
    )).rejects.toBe(failure)

    expect(state.current).toBe(false)
    expect(busyStates).toEqual([true, false])
  })

  it('keeps loading feedback and every established destination', () => {
    const page = source('src/presentation/pages/home/index.tsx')

    expect(page).toContain('isLoggingIn')
    expect(page).toContain('loading={isLoggingIn}')
    expect(page).toContain('disabled={isLoggingIn}')
    expect(page).toContain('runSingleFlight(loginInFlight, setIsLoggingIn')
    for (const destination of ['WORKOUT_SESSION', 'PLAN', 'ONBOARDING', 'MY']) {
      expect(page).toContain(`'${destination}'`)
    }
  })

  it('uses a restrained health-tech system with explicit safe-area handling', () => {
    const styles = source('src/presentation/pages/home/index.scss')

    for (const token of ['#082f28', '#0b5c4d', '#55d6a6', '#f7f8f3']) {
      expect(styles.toLowerCase()).toContain(token)
    }
    expect(styles).toContain('env(safe-area-inset-bottom)')
    expect(styles).toMatch(/@media\s+\(min-width:/)
    expect(styles).toMatch(/home-page__action--ghost[\s\S]*min-height:\s*88px/)
  })
})
