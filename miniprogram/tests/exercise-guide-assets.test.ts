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

describe('original golden-cat static exercise breakdown pack', () => {
  it('covers every active action with two to four static JPEG stages', () => {
    const exercises = activeExercises()
    const pack = manifest()
    const byCode = new Map(pack.assets.map((entry) => [entry.exerciseCode, entry]))

    expect(exercises).toHaveLength(23)
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
    expect(byCode.get('DUMBBELL_FLOOR_PRESS')?.stages.map((stage) => stage.sourceGridIndex))
      .toEqual([0, 2, 3])
    expect(byCode.get('SINGLE_ARM_DUMBBELL_PRESS')?.stages.map((stage) => stage.sourceGridIndex))
      .toEqual([0, 8, 3])
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
    expect(readme).toContain('2～4 张')
    expect(readme).toContain('不使用 GIF、动态 WebP、MP4')
    expect(readme).toContain('不包含第三方动作素材')
    expect(readme).toContain('assets-source')
    expect(readme).toContain('不会进入小程序包')
  })
})
