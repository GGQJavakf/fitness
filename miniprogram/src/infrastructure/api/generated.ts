import type { components } from './schema.generated'

export type { operations, paths } from './schema.generated'

export type UtcDateTime = components['schemas']['UtcDateTime']
export type RequestId = components['schemas']['RequestId']
export type ExpectedVersion = components['schemas']['ExpectedVersion']
export type IdempotencyKey = string
export type WeightUnit = components['schemas']['WeightUnit']
export type ErrorCode = components['schemas']['ErrorCode']

type WeightSchema = components['schemas']['Weight']
type RuleReferenceSchema = components['schemas']['RuleReference']
type FieldErrorSchema = components['schemas']['FieldError']
type ApiErrorSchema = components['schemas']['ApiError']
type ResponseMetaSchema = components['schemas']['ResponseMeta']
type ErrorMetaSchema = components['schemas']['ErrorMeta']
type ApiErrorResponseSchema = components['schemas']['ApiErrorResponse']

export interface Weight extends WeightSchema {}
export interface RuleReference extends RuleReferenceSchema {}
export interface FieldError extends FieldErrorSchema {}
export interface ApiError extends ApiErrorSchema {}
export interface ResponseMeta extends ResponseMetaSchema {}
export interface ErrorMeta extends ErrorMetaSchema {}
export interface ApiErrorResponse extends ApiErrorResponseSchema {}

export interface ApiResponse<T>
  extends Omit<components['schemas']['ApiResponse'], 'data'> {
  data: T
}

export type ValidationSeverity = components['schemas']['ValidationSeverity']
export type AiExplanationStatus = components['schemas']['AiExplanationStatus']
export type WeightStatus = components['schemas']['WeightStatus']
export type LockStatus = components['schemas']['LockStatus']
export type SessionStatus = components['schemas']['SessionStatus']
export type SyncOperationStatus = components['schemas']['SyncOperationStatus']
export type ProgressionDecision = components['schemas']['ProgressionDecision']
