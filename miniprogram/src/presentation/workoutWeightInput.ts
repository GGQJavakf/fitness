export function toWeightInputValue(value: unknown): string | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? String(value)
    : null
}
