import type { ExerciseContent } from '../../application/content'
import exerciseCatalog from '../../../../rule-config/validated/exercises-v1.json'

export interface ExerciseGuidance {
  readonly plainLanguage: string
  readonly instructions: readonly string[]
  readonly breathingCues: readonly string[]
  readonly commonMistakes: readonly string[]
  readonly safetyCues: readonly string[]
  readonly primaryRef?: string
  readonly fallbackRef?: string
}

interface CatalogExerciseGuidance {
  readonly code: string
  readonly movementPattern: string
  readonly plainLanguage: string
  readonly instructions: readonly string[]
  readonly safetyCues: readonly string[]
  readonly imageRef?: string
  readonly active: boolean
}

export function resolveExerciseGuidance(exerciseCode: string): ExerciseGuidance | undefined {
  return guidanceByExerciseCode.get(exerciseCode)
}

export function toExerciseGuidance(content: ExerciseContent): ExerciseGuidance {
  const coaching = coachingCues(content.code, content.movementPattern)
  return {
    plainLanguage: content.plainLanguage,
    instructions: content.instructions,
    breathingCues: coaching.breathingCues,
    commonMistakes: coaching.commonMistakes,
    safetyCues: content.safetyCues,
    primaryRef: content.image.primaryRef,
    fallbackRef: content.image.fallbackRef,
  }
}

interface CoachingCues {
  readonly breathingCues: readonly string[]
  readonly commonMistakes: readonly string[]
}

const coachingByMovementPattern: Readonly<Record<string, CoachingCues>> = {
  SQUAT: {
    breathingCues: ['下蹲时吸气并收紧躯干，起身发力时呼气'],
    commonMistakes: ['膝盖向内扣或脚跟离地', '为了追求深度而塌腰'],
  },
  HINGE: {
    breathingCues: ['送髋前吸气收紧躯干，站起接近完成时呼气'],
    commonMistakes: ['弓背下探或把动作做成深蹲', '负重离身体过远'],
  },
  HORIZONTAL_PUSH: {
    breathingCues: ['下降时吸气，推起通过最难位置时呼气'],
    commonMistakes: ['手腕后折或肘部过度外展', '肩膀耸起、脚部失去支撑'],
  },
  HORIZONTAL_PULL: {
    breathingCues: ['伸臂还原时吸气，拉向身体时呼气'],
    commonMistakes: ['大幅后仰或旋转身体借力', '耸肩代替肩胛后收'],
  },
  VERTICAL_PUSH: {
    breathingCues: ['下降时吸气，向上推举时呼气'],
    commonMistakes: ['腰部过度后仰', '推举时耸肩或手腕折弯'],
  },
  VERTICAL_PULL: {
    breathingCues: ['手臂伸展时吸气，下拉发力时呼气'],
    commonMistakes: ['握把拉到颈后', '身体摆动或突然放回重量'],
  },
  CORE: {
    breathingCues: ['保持自然呼吸，在伸展或发力时缓慢呼气'],
    commonMistakes: ['屏住呼吸', '腰背失去稳定后仍继续扩大幅度'],
  },
}

const coachingByExerciseCode: Readonly<Record<string, CoachingCues>> = {
  PLANK: {
    breathingCues: ['保持均匀自然呼吸，不要为了坚持时间而憋气'],
    commonMistakes: ['塌腰或臀部抬得过高', '肩膀耸向耳朵'],
  },
  DEAD_BUG: {
    breathingCues: ['手脚伸展时缓慢呼气，返回准备位时吸气'],
    commonMistakes: ['腰背拱起离开支撑面', '手脚下放过快导致躯干晃动'],
  },
  BIRD_DOG: {
    breathingCues: ['伸展对侧手脚时呼气，返回四点支撑时吸气'],
    commonMistakes: ['抬腿过高导致腰部旋转', '支撑肩塌陷或身体左右晃动'],
  },
}

function coachingCues(exerciseCode: string, movementPattern: string): CoachingCues {
  return coachingByExerciseCode[exerciseCode]
    ?? coachingByMovementPattern[movementPattern]
    ?? coachingByMovementPattern.CORE
}

const guidanceByExerciseCode = new Map(
  (exerciseCatalog.exercises as readonly CatalogExerciseGuidance[])
    .filter((exercise) => exercise.active)
    .map((exercise) => {
      const coaching = coachingCues(exercise.code, exercise.movementPattern)
      return [
        exercise.code,
        {
          plainLanguage: exercise.plainLanguage,
          instructions: exercise.instructions,
          breathingCues: coaching.breathingCues,
          commonMistakes: coaching.commonMistakes,
          safetyCues: exercise.safetyCues,
          primaryRef: exercise.imageRef,
          fallbackRef: exercise.imageRef,
        },
      ] as const
    })
)
