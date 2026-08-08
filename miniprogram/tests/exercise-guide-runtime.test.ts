import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { describe, expect, it, vi } from 'vitest'

import { resolveExerciseGuide } from '../src/subpackages/exercise-guide/components/exercise-motion-guide/assets'
import { resolveExerciseGuidance } from '../src/subpackages/exercise-guide/exercise-guidance'

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Image: 'image',
  Text: 'text',
  View: 'view',
}))

const { default: ExerciseMotionGuide } = await import(
  '../src/subpackages/exercise-guide/components/exercise-motion-guide'
)

const repositoryRoot = resolve(import.meta.dirname, '..', '..')

interface CatalogExercise {
  readonly code: string
  readonly active: boolean
  readonly imageRef: string
  readonly movementPattern: string
  readonly plainLanguage: string
  readonly instructions: readonly string[]
  readonly safetyCues: readonly string[]
}

function activeExercises(): readonly CatalogExercise[] {
  const catalog = JSON.parse(
    readFileSync(resolve(repositoryRoot, 'rule-config/validated/exercises-v1.json'), 'utf8')
  ) as { exercises: CatalogExercise[] }
  return catalog.exercises.filter((exercise) => exercise.active)
}

function renderGuide(
  exerciseCode: string,
  primaryRef = 'asset://exercise-placeholder',
  fallbackRef?: string
): ReactTestRenderer {
  let renderer: ReactTestRenderer | undefined
  act(() => {
    renderer = TestRenderer.create(createElement(ExerciseMotionGuide, {
      exerciseCode,
      exerciseName: exerciseCode === 'UNKNOWN' ? '未知动作' : '高脚杯深蹲',
      primaryRef,
      fallbackRef,
    }))
  })
  if (!renderer) throw new Error('exercise guide did not render')
  return renderer
}

function renderedText(renderer: ReactTestRenderer): string {
  return JSON.stringify(renderer.toJSON())
}

describe('exercise static breakdown runtime behavior', () => {
  it('resolves every catalog action and provides breathing and common-error guidance', () => {
    for (const exercise of activeExercises()) {
      const guide = resolveExerciseGuide(exercise.imageRef, exercise.code)
      const guidance = resolveExerciseGuidance(exercise.code)

      expect(guide, exercise.code).toBeDefined()
      expect(guide?.primaryRef, exercise.code).toBe(exercise.imageRef)
      expect(guide?.stages.length, exercise.code).toBeGreaterThanOrEqual(2)
      expect(guide?.stages.length, exercise.code).toBeLessThanOrEqual(4)
      expect(guidance, exercise.code).toMatchObject({
        plainLanguage: exercise.plainLanguage,
        instructions: exercise.instructions,
        safetyCues: exercise.safetyCues,
      })
      expect(guidance?.breathingCues.length, exercise.code).toBeGreaterThan(0)
      expect(guidance?.commonMistakes.length, exercise.code).toBeGreaterThan(0)
    }

    expect(resolveExerciseGuide(
      'asset://exercise-placeholder',
      'GOBLET_SQUAT'
    )).toMatchObject({
      kind: 'BREAKDOWN',
      primaryRef: 'asset://exercise-guides/goblet-squat-01-setup.jpg',
    })
    expect(resolveExerciseGuide('asset://exercise-guides/missing.jpg', 'UNKNOWN'))
      .toBeUndefined()
  })

  it('switches a stage with one click and resets to the first stage for a new action', () => {
    const renderer = renderGuide('GOBLET_SQUAT')
    const guide = resolveExerciseGuide(undefined, 'GOBLET_SQUAT')!
    const tabs = renderer.root.findAll(
      (node) => node.type === 'button'
        && typeof node.props.className === 'string'
        && node.props.className.includes('motion-guide__stage-tab')
    )

    expect(tabs).toHaveLength(3)
    expect(renderer.root.findByProps({ className: 'motion-guide__image' }).props.src)
      .toBe(guide.stages[0].source)

    act(() => tabs[1].props.onClick())

    expect(renderer.root.findByProps({ className: 'motion-guide__image' }).props.src)
      .toBe(guide.stages[1].source)
    expect(renderedText(renderer)).toContain(guide.stages[1].description)

    act(() => {
      renderer.update(createElement(ExerciseMotionGuide, {
        exerciseCode: 'PLANK',
        exerciseName: '平板支撑',
        primaryRef: 'asset://exercise-placeholder',
      }))
    })
    const plank = resolveExerciseGuide(undefined, 'PLANK')!
    expect(renderer.root.findByProps({ className: 'motion-guide__image' }).props.src)
      .toBe(plank.stages[0].source)
  })

  it('never leaves a blank stage when a static image fails to load', () => {
    const renderer = renderGuide('GOBLET_SQUAT')
    const image = renderer.root.findByProps({ className: 'motion-guide__image' })

    act(() => image.props.onError())

    expect(renderer.root.findAllByProps({ className: 'motion-guide__fallback' })).toHaveLength(1)
    expect(renderedText(renderer)).toContain('静态示例暂时无法显示')
    expect(renderedText(renderer)).toContain('请继续按下方动作步骤练习')
  })

  it('uses an approved bundled static cover when breakdown images are not ready', () => {
    const renderer = renderGuide(
      'UNKNOWN',
      'asset://exercise-placeholder',
      'asset://exercise-guides/goblet-squat-01-setup.jpg'
    )
    const text = renderedText(renderer)

    expect(text).toContain('合规静态封面')
    expect(text).toContain('分解图尚未补齐')
    expect(resolveExerciseGuide(
      'asset://exercise-guides/goblet-squat-01-setup.jpg',
      'UNKNOWN'
    )).toMatchObject({
      kind: 'STATIC_COVER',
      stages: [{ id: 'cover' }],
    })
    expect(renderer.root.findAllByProps({ className: 'motion-guide__image' })).toHaveLength(1)
    expect(renderer.root.findAll(
      (node) => node.type === 'button'
        && typeof node.props.className === 'string'
        && node.props.className.includes('motion-guide__stage-tab')
    )).toHaveLength(0)
  })

  it('keeps dynamic or unknown fallback references on text-only guidance', () => {
    for (const fallbackRef of [
      'asset://exercise-guides/legacy.gif',
      'asset://exercise-guides/missing.jpg',
      'https://example.test/unapproved-cover.jpg',
    ]) {
      const renderer = renderGuide('UNKNOWN', fallbackRef, fallbackRef)
      const text = renderedText(renderer)

      expect(text).toContain('动作示例暂未补齐')
      expect(text).toContain('请继续按下方动作步骤练习')
      expect(renderer.root.findAllByProps({ className: 'motion-guide__image' })).toHaveLength(0)
    }
  })
})
