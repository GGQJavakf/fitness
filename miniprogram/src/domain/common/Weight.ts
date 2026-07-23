export type WeightUnit = 'KG'

export interface Weight {
  readonly hundredths: number
  readonly unit: WeightUnit
  readonly equipmentProfileId?: string
}

export function createP0Weight(
  hundredths: number,
  equipmentProfileId?: string,
): Weight {
  if (!Number.isSafeInteger(hundredths) || hundredths < 0) {
    throw new Error('weight must be a non-negative fixed-point integer')
  }
  if (equipmentProfileId !== undefined && equipmentProfileId.trim() === '') {
    throw new Error('equipmentProfileId must not be blank')
  }

  return {
    hundredths,
    unit: 'KG',
    ...(equipmentProfileId === undefined ? {} : { equipmentProfileId }),
  }
}
