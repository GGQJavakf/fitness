import { describe, expect, it, vi } from 'vitest'

import type { AiGeneratedContent } from '../src/application/ai'
import type {
  AiConsentPort,
  ValidatedAiContentGenerator,
} from '../src/application/cloudbaseAi'
import type { WorkoutSessionSummary } from '../src/application/history'
import { createWorkoutSummaryRequest } from '../src/application/workoutSummary'

interface TestGenerationGuard {
  capture(): number
  assertCurrent(generation: number): void
  begin(): number
}

class TestGenerationInvalidatedError extends Error {}

function createGenerationGuard(): TestGenerationGuard {
  let generation = 0
  return {
    capture: () => generation,
    assertCurrent(expected): void {
      if (expected !== generation) throw new TestGenerationInvalidatedError()
    },
    begin(): number {
      generation += 1
      return generation
    },
  }
}

const ruleSummary: AiGeneratedContent = {
  status: 'DEGRADED',
  content: '规则训练回顾',
  validationStatus: 'AI_DISABLED',
}

function setup(
  consentGranted: boolean,
  aiEnabled = true,
  operationGuard?: TestGenerationGuard,
) {
  const consent: AiConsentPort = {
    hasConsent: vi.fn().mockResolvedValue(consentGranted),
  }
  const getSummaryFacts = vi.fn().mockResolvedValue({
    sessionId: 'session-1',
    status: 'COMPLETED' as const,
    completedWorkSets: 12,
    completedVolumeKg: 720,
    completedReps: 120,
    usesExternalLoad: true,
  })
  const generate: ValidatedAiContentGenerator = {
    generate: vi.fn().mockResolvedValue({
      status: 'READY', content: 'AI 回顾', validationStatus: 'VALID',
    }),
  }
  const fallback = vi.fn().mockResolvedValue(ruleSummary)
  return {
    consent,
    getSummaryFacts,
    generate,
    fallback,
    request: createWorkoutSummaryRequest({
      aiEnabled, consent, getSummaryFacts, generate, fallback, operationGuard,
    }),
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('workout summary AI request', () => {
  it('uses the deterministic server summary without reading facts or calling AI when consent is absent', async () => {
    const subject = setup(false)

    await expect(subject.request('session-1')).resolves.toEqual(ruleSummary)

    expect(subject.consent.hasConsent).toHaveBeenCalledWith('WORKOUT_SUMMARY')
    expect(subject.getSummaryFacts).not.toHaveBeenCalled()
    expect(subject.generate.generate).not.toHaveBeenCalled()
    expect(subject.fallback).toHaveBeenCalledWith('session-1')
  })

  it('reads the owned session summary directly and sends only approved facts after consent', async () => {
    const subject = setup(true)

    await expect(subject.request('session-1')).resolves.toMatchObject({ status: 'READY' })

    expect(subject.getSummaryFacts).toHaveBeenCalledWith('session-1')
    expect(subject.generate.generate).toHaveBeenCalledWith('WORKOUT_SUMMARY', {
      sessionId: 'session-1',
      status: 'COMPLETED',
      completedWorkSets: 12,
      completedVolumeKg: 720,
      reasonCodes: [],
      progressionConclusion: null,
    }, expect.any(Function))
  })

  it('falls back to the server summary when authoritative facts cannot be read', async () => {
    const subject = setup(true)
    subject.getSummaryFacts.mockRejectedValue(new Error('offline'))

    await expect(subject.request('session-1')).resolves.toEqual(ruleSummary)

    expect(subject.generate.generate).not.toHaveBeenCalled()
    expect(subject.fallback).toHaveBeenCalledWith('session-1')
  })

  it('skips consent and AI completely when the release flag is disabled', async () => {
    const subject = setup(true, false)

    await expect(subject.request('session-1')).resolves.toEqual(ruleSummary)

    expect(subject.consent.hasConsent).not.toHaveBeenCalled()
    expect(subject.getSummaryFacts).not.toHaveBeenCalled()
    expect(subject.generate.generate).not.toHaveBeenCalled()
  })

  it('does not read facts or fall back for a new user when consent resolves after generation changes', async () => {
    const generation = createGenerationGuard()
    const consent = deferred<boolean>()
    const subject = setup(true, true, generation)
    vi.mocked(subject.consent.hasConsent).mockReturnValueOnce(consent.promise)

    const pending = subject.request('session-a')
    generation.begin()
    consent.resolve(true)

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(subject.getSummaryFacts).not.toHaveBeenCalled()
    expect(subject.generate.generate).not.toHaveBeenCalled()
    expect(subject.fallback).not.toHaveBeenCalled()
  })

  it('does not call AI or fallback for a new user when facts resolve after generation changes', async () => {
    const generation = createGenerationGuard()
    const facts = deferred<WorkoutSessionSummary>()
    const subject = setup(true, true, generation)
    subject.getSummaryFacts.mockReturnValueOnce(facts.promise)

    const pending = subject.request('session-a')
    await Promise.resolve()
    generation.begin()
    facts.resolve({
      sessionId: 'session-a',
      status: 'COMPLETED',
      completedWorkSets: 8,
      completedVolumeKg: 480,
      completedReps: 80,
      usesExternalLoad: true,
    })

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(subject.generate.generate).not.toHaveBeenCalled()
    expect(subject.fallback).not.toHaveBeenCalled()
  })
})
