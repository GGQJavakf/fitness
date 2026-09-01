import { createHash } from 'node:crypto'
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')
const repositoryRoot = resolve(projectRoot, '..')
const assetRoot = resolve(projectRoot, 'src/subpackages/exercise-guide/assets/exercise-guides')

interface CatalogExercise {
  readonly code: string
  readonly active: boolean
  readonly assetStatus: string
  readonly imageRef?: string
}

interface StaticStage {
  readonly id: string
  readonly label: string
  readonly description: string
  readonly file: string
  readonly phase: number
  readonly sourceFile: string
  readonly sourceGridIndex?: number
  readonly sha256: string
  readonly sizeBytes: number
}

interface StaticManifestEntry {
  readonly exerciseCode: string
  readonly primaryRef: string
  readonly stages: readonly StaticStage[]
}

interface StaticManifest {
  readonly format: string
  readonly character: {
    readonly id: string
    readonly modelSheet: string
    readonly modelSheetSha256: string
    readonly rigPartSheet: string
    readonly rigPartSheetSha256: string
  }
  readonly assets: readonly StaticManifestEntry[]
}

function activeExercises(): readonly CatalogExercise[] {
  const catalog = JSON.parse(
    readFileSync(resolve(repositoryRoot, 'rule-config/validated/exercises-v1.json'), 'utf8')
  ) as { exercises: CatalogExercise[] }
  return catalog.exercises.filter((exercise) => exercise.active)
}

function manifest(root = assetRoot): StaticManifest {
  return JSON.parse(readFileSync(resolve(root, 'static-manifest.json'), 'utf8')) as StaticManifest
}

function sha256(value: Buffer): string {
  return createHash('sha256').update(value).digest('hex')
}

function pngColorType(path: string): number {
  const value = readFileSync(path)
  expect(value.subarray(1, 4).toString('ascii'), path).toBe('PNG')
  return value[25]
}

describe('original golden-cat static exercise breakdown pack', () => {
  it('covers every active action with two to four static JPEG stages', () => {
    const exercises = activeExercises()
    const pack = manifest()
    const byCode = new Map(pack.assets.map((entry) => [entry.exerciseCode, entry]))

    expect(exercises).toHaveLength(63)
    expect([...byCode.keys()]).toEqual(expect.arrayContaining([
      'DUMBBELL_BICEPS_CURL',
      'CABLE_TRICEPS_PUSHDOWN',
      'DUMBBELL_LATERAL_RAISE',
      'ONE_ARM_DUMBBELL_ROW',
      'DUMBBELL_SHRUG'
    ]))
    expect(pack.format).toBe('static-keyframes-v1')
    expect(pack.character.id).toBe('golden-shaded-cat-coach-v4-rigged')
    expect(pack.assets).toHaveLength(exercises.length)
    expect(new Set(pack.assets.map((entry) => entry.stages.length))).toEqual(new Set([2, 3, 4]))

    for (const exercise of exercises) {
      const slug = exercise.code.toLowerCase().replace(/_/g, '-')
      const entry = byCode.get(exercise.code)
      expect(entry, exercise.code).toBeDefined()
      expect(entry?.stages.length, exercise.code).toBeGreaterThanOrEqual(2)
      expect(entry?.stages.length, exercise.code).toBeLessThanOrEqual(4)
      expect(exercise.assetStatus, exercise.code).toBe('PROJECT_ORIGINAL_STATIC_BREAKDOWN')
      expect(exercise.imageRef, exercise.code)
        .toBe(`asset://exercise-guides/${slug}-01-${entry?.stages[0].id}.jpg`)
      expect(entry?.primaryRef, exercise.code).toBe(exercise.imageRef)

      for (const [index, stage] of (entry?.stages ?? []).entries()) {
        expect(stage.file, `${exercise.code} stage ${index + 1}`)
          .toBe(`${slug}-${String(index + 1).padStart(2, '0')}-${stage.id}.jpg`)
        expect(stage.label.trim().length, exercise.code).toBeGreaterThan(0)
        expect(stage.description.trim().length, exercise.code).toBeGreaterThan(3)
        expect(stage.phase, exercise.code).toBeGreaterThanOrEqual(0)
        expect(stage.phase, exercise.code).toBeLessThanOrEqual(1)

        const path = resolve(assetRoot, stage.file)
        expect(existsSync(path), stage.file).toBe(true)
        const bytes = readFileSync(path)
        expect([...bytes.subarray(0, 2)], stage.file).toEqual([0xff, 0xd8])
        expect([...bytes.subarray(-2)], stage.file).toEqual([0xff, 0xd9])
        expect(statSync(path).size, stage.file).toBeLessThanOrEqual(40 * 1024)
        expect(stage.sizeBytes, stage.file).toBe(statSync(path).size)
        expect(stage.sha256, stage.file).toBe(sha256(bytes))
      }
    }
  })

  it('keeps generated exercise images within 1.40 MiB for code and shared dependency headroom', () => {
    const totalBytes = manifest().assets
      .flatMap((entry) => entry.stages)
      .reduce((sum, stage) => sum + statSync(resolve(assetRoot, stage.file)).size, 0)

    expect(totalBytes).toBeLessThanOrEqual(1.40 * 1024 * 1024)
  })

  it('locks every stage to the approved character and excludes dynamic or third-party runtime media', () => {
    const pack = manifest()
    const modelSheet = readFileSync(resolve(projectRoot, pack.character.modelSheet))
    const rigPartSheet = readFileSync(resolve(projectRoot, pack.character.rigPartSheet))
    const runtimeFiles = readdirSync(assetRoot)

    expect(pack.character.modelSheetSha256).toBe(sha256(modelSheet))
    expect(pack.character.rigPartSheetSha256).toBe(sha256(rigPartSheet))
    expect(runtimeFiles.some((file) => /\.(?:gif|webp|mp4|webm)$/i.test(file))).toBe(false)
    expect(existsSync(resolve(assetRoot, 'THIRD_PARTY_NOTICES.md'))).toBe(false)
    expect(pack.assets.flatMap((entry) => entry.stages).every(
      (stage) => stage.file.endsWith('.jpg')
    )).toBe(true)
  })

  it('uses curated source poses for actions whose generic frame selection is ambiguous', () => {
    const byCode = new Map(
      manifest().assets.map((entry) => [entry.exerciseCode, entry])
    )

    expect(byCode.get('INCLINE_PUSH_UP')?.stages.map((stage) => stage.sourceGridIndex))
      .toEqual([0, 2, 4])
    const floorPressStages = byCode.get('DUMBBELL_FLOOR_PRESS')?.stages ?? []
    expect(floorPressStages.map((stage) => stage.sourceGridIndex))
      .toEqual([2, 5, 3])
    expect(floorPressStages.map((stage) => stage.sourceFile))
      .toEqual([
        'assets-source/exercise-guides/dumbbell-floor-press-sprite-v3.png',
        'assets-source/exercise-guides/dumbbell-floor-press-sprite-v3.png',
        'assets-source/exercise-guides/dumbbell-floor-press-sprite-v3.png',
      ])
    expect(byCode.get('SINGLE_ARM_DUMBBELL_PRESS')?.stages.map((stage) => stage.sourceGridIndex))
      .toEqual([0, 8, 3])
    expect(byCode.get('STANDING_WALL_CALF_RAISE')?.stages.map((stage) => stage.sourceGridIndex))
      .toEqual([0, 1, 2])

    const curatedTriptychs = new Map([
      ['DUMBBELL_LATERAL_RAISE', 'dumbbell-lateral-raise-sprite-v4.png'],
      ['SINGLE_ARM_DUMBBELL_LATERAL_RAISE', 'single-arm-dumbbell-lateral-raise-sprite-v4.png'],
      ['CABLE_LATERAL_RAISE', 'cable-lateral-raise-sprite-v4.png'],
      ['CABLE_TRICEPS_PUSHDOWN', 'cable-triceps-pushdown-sprite-v4.png'],
      ['DUMBBELL_OVERHEAD_TRICEPS_EXTENSION', 'dumbbell-overhead-triceps-extension-sprite-v4.png'],
      ['DUMBBELL_LYING_TRICEPS_EXTENSION', 'dumbbell-lying-triceps-extension-sprite-v4.png'],
      ['CABLE_REVERSE_FLY', 'cable-reverse-fly-sprite-v4.png'],
      ['MACHINE_SHRUG', 'machine-shrug-sprite-v4.png'],
      ['SMITH_FLAT_BENCH_PRESS', 'smith-flat-bench-press-sprite-v1.png'],
      ['INCLINE_DUMBBELL_BENCH_PRESS_30', 'incline-dumbbell-bench-press-30-sprite-v1.png'],
      ['SEATED_MACHINE_SHOULDER_PRESS', 'seated-machine-shoulder-press-sprite-v1.png'],
      ['LEANING_PEC_DECK_FLY', 'leaning-pec-deck-fly-sprite-v1.png'],
      ['MACHINE_SEATED_ROW', 'machine-seated-row-sprite-v1.png'],
      ['REVERSE_PEC_DECK_FLY', 'reverse-pec-deck-fly-sprite-v1.png'],
      ['SMITH_SQUAT', 'smith-squat-sprite-v1.png'],
      ['SEATED_LEG_PRESS', 'seated-leg-press-sprite-v1.png'],
      ['DUMBBELL_REVERSE_LUNGE', 'dumbbell-reverse-lunge-sprite-v1.png'],
      ['SEATED_LEG_EXTENSION', 'seated-leg-extension-sprite-v1.png'],
      ['MACHINE_CRUNCH', 'machine-crunch-sprite-v1.png'],
      ['INCLINE_DUMBBELL_FLY', 'incline-dumbbell-fly-sprite-v1.png'],
      ['MACHINE_HIP_THRUST', 'machine-hip-thrust-sprite-v1.png'],
      ['MACHINE_LEG_CURL', 'machine-leg-curl-sprite-v1.png'],
      ['MACHINE_HIP_ABDUCTION', 'machine-hip-abduction-sprite-v1.png'],
      ['STANDING_CALF_RAISE', 'standing-calf-raise-sprite-v1.png'],
    ])
    for (const [exerciseCode, sourceFile] of curatedTriptychs) {
      const stages = byCode.get(exerciseCode)?.stages ?? []
      expect(stages, exerciseCode).toHaveLength(3)
      expect(stages.map((stage) => stage.sourceFile), exerciseCode)
        .toEqual(Array(3).fill(`assets-source/exercise-guides/${sourceFile}`))
      expect(stages.map((stage) => stage.sourceGridIndex), exerciseCode)
        .toEqual([undefined, undefined, undefined])
      expect(
        [0, 2, 3].includes(pngColorType(resolve(projectRoot, 'assets-source/exercise-guides', sourceFile))),
        `${sourceFile} must not contain an alpha channel that can reveal a dark viewer background`
      ).toBe(true)
    }
  })

  it('rebuilds the complete static pack byte-for-byte from the approved locked rig', () => {
    const generator = resolve(projectRoot, 'scripts/build_static_exercise_guides.py')
    const rebuiltRoot = mkdtempSync(resolve(tmpdir(), 'fitness-static-exercise-guides-'))
    try {
      const rebuilt = spawnSync(
        'python',
        [generator, '--output', rebuiltRoot],
        { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024 }
      )
      expect(rebuilt.status, rebuilt.stderr).toBe(0)

      const current = manifest()
      const copy = manifest(rebuiltRoot)
      expect(copy).toEqual(current)
      for (const stage of current.assets.flatMap((entry) => entry.stages)) {
        expect(
          sha256(readFileSync(resolve(rebuiltRoot, stage.file))),
          stage.file
        ).toBe(sha256(readFileSync(resolve(assetRoot, stage.file))))
      }
    } finally {
      rmSync(rebuiltRoot, { recursive: true, force: true })
    }
  }, 20_000)

  it('documents the static-only copyright and package boundary', () => {
    const readme = readFileSync(resolve(assetRoot, 'README.md'), 'utf8')

    expect(readme).toContain('原创金渐层猫静态动作分解图')
    expect(readme).toContain(`${manifest().assets.length} 个动作`)
    expect(readme).toContain('2～4 张')
    expect(readme).toContain('不使用 GIF、动态 WebP、MP4')
    expect(readme).toContain('不包含第三方动作素材')
    expect(readme).toContain('assets-source')
    expect(readme).toContain('不会进入小程序包')
  })
})
