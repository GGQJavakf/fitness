import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const contractRoot = resolve(import.meta.dirname, '../../contract')
const generatedTypes = resolve(
  import.meta.dirname,
  '../src/infrastructure/api/generated.ts',
)
const generatedSchema = resolve(
  import.meta.dirname,
  '../src/infrastructure/api/schema.generated.ts',
)

function readContract(relativePath: string): string {
  const path = resolve(contractRoot, relativePath)
  expect(existsSync(path), `missing contract file: ${path}`).toBe(true)
  return readFileSync(path, 'utf8')
}

describe('OpenAPI client contract', () => {
  it('publishes the OpenAPI 3.1 contract and all domain schema files', () => {
    expect(readContract('openapi.yaml')).toContain('openapi: 3.1.0')
    for (const schema of [
      'common.yaml',
      'profile.yaml',
      'plan.yaml',
      'workout.yaml',
      'progression.yaml',
    ]) {
      expect(readContract(`schemas/${schema}`)).toContain('components:')
    }
  })

  it('keeps ownership server-derived and exposes stable synchronization contracts', () => {
    const contract = [
      readContract('openapi.yaml'),
      readContract('schemas/common.yaml'),
      readContract('schemas/profile.yaml'),
      readContract('schemas/plan.yaml'),
      readContract('schemas/workout.yaml'),
      readContract('schemas/progression.yaml'),
    ].join('\n')

    expect(contract).toContain('Idempotency-Key')
    expect(contract).toContain('expectedVersion')
    expect(contract).toContain('VERSION_CONFLICT')
    expect(contract).not.toMatch(/^\s+userId:/m)
  })

  it('publishes generated TypeScript types for API and domain boundaries', () => {
    expect(existsSync(generatedTypes), `missing generated types: ${generatedTypes}`).toBe(true)
    const generated = readFileSync(generatedTypes, 'utf8')
    expect(generated).toContain('export interface ApiResponse<T>')
    expect(generated).toContain('export interface ApiError')
    expect(generated).toContain('export interface FieldError')
    expect(generated).toContain('export interface RuleReference')
    const schema = readFileSync(generatedSchema, 'utf8')
    expect(schema).toContain('WeightUnit: "KG"')
    expect(schema).toContain('"/api/v1/workout-sessions"')
    expect(schema).toContain('"/api/v1/plans/{planId}/exercise-options"')
  })
})
