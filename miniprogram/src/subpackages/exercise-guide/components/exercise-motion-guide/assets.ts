import {
  staticGuidesByExerciseCode,
  staticSourcesByAssetRef,
} from '../../assets/exercise-guides/static-assets.generated'

export interface ExerciseGuideStage {
  readonly id: string
  readonly label: string
  readonly description: string
  readonly source: string
}

export interface ExerciseGuideAsset {
  readonly primaryRef: string
  readonly kind: 'BREAKDOWN' | 'STATIC_COVER'
  readonly stages: readonly ExerciseGuideStage[]
}

const guidesByExerciseCode: Readonly<Record<string, ExerciseGuideAsset>> =
  Object.fromEntries(
    Object.entries(staticGuidesByExerciseCode).map(([exerciseCode, guide]) => [
      exerciseCode,
      { ...guide, kind: 'BREAKDOWN' as const },
    ])
  )

export function resolveExerciseGuide(
  primaryRef: string | undefined,
  exerciseCode: string,
  fallbackRef?: string
): ExerciseGuideAsset | undefined {
  return (
    guidesByExerciseCode[exerciseCode]
    ?? resolveStaticCover(fallbackRef)
    ?? resolveStaticCover(primaryRef)
  )
}

function resolveStaticCover(ref: string | undefined): ExerciseGuideAsset | undefined {
  if (!ref) return undefined
  const source = (staticSourcesByAssetRef as Readonly<Record<string, string>>)[ref]
  if (!source) return undefined
  return {
    primaryRef: ref,
    kind: 'STATIC_COVER',
    stages: [{
      id: 'cover',
      label: '静态封面',
      description: '分解图尚未补齐，请结合动作步骤和安全提示练习。',
      source,
    }],
  }
}
