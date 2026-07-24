export type WorkoutOperationType = 'UPSERT_SET' | 'UPDATE_SESSION_STATUS' | 'COMPLETE_SESSION'
export type WorkoutOperationStatus = 'PENDING' | 'ACKED'

export interface WorkoutOperation<TPayload = unknown> {
  clientOperationSeq: number
  idempotencyKey: string
  type: WorkoutOperationType
  payload: TPayload
  createdAtUtc: string
  status: WorkoutOperationStatus
}

export interface OperationQueue {
  nextClientOperationSeq: number
  operations: readonly WorkoutOperation[]
}

export type NewWorkoutOperation<TPayload = unknown> = Omit<
  WorkoutOperation<TPayload>, 'clientOperationSeq' | 'status'
>

const operationTypes = new Set<WorkoutOperationType>([
  'UPSERT_SET',
  'UPDATE_SESSION_STATUS',
  'COMPLETE_SESSION',
])

export function createOperationQueue(): OperationQueue {
  return { nextClientOperationSeq: 1, operations: [] }
}

export function restoreOperationQueue(value: OperationQueue): OperationQueue {
  if (!Number.isSafeInteger(value.nextClientOperationSeq) || value.nextClientOperationSeq < 1) {
    throw new Error('workout operation queue next sequence is invalid')
  }
  let previous = 0
  for (const operation of value.operations) {
    if (!Number.isSafeInteger(operation.clientOperationSeq)
      || operation.clientOperationSeq <= previous
      || operation.clientOperationSeq >= value.nextClientOperationSeq) {
      throw new Error('workout operation sequence must be strictly increasing')
    }
    if (operation.idempotencyKey.length < 8 || operation.idempotencyKey.length > 128
      || !operationTypes.has(operation.type) || !operation.createdAtUtc
      || (operation.status !== 'PENDING' && operation.status !== 'ACKED')) {
      throw new Error('workout operation is invalid')
    }
    previous = operation.clientOperationSeq
  }
  return {
    nextClientOperationSeq: value.nextClientOperationSeq,
    operations: value.operations.map((operation) => ({ ...operation })),
  }
}

export function enqueueOperation<TPayload>(
  queue: OperationQueue,
  input: NewWorkoutOperation<TPayload>,
): { queue: OperationQueue; operation: WorkoutOperation<TPayload> } {
  const restored = restoreOperationQueue(queue)
  if (input.idempotencyKey.length < 8 || input.idempotencyKey.length > 128
    || !operationTypes.has(input.type) || !input.createdAtUtc) {
    throw new Error('workout operation identity and time are required')
  }
  const operation: WorkoutOperation<TPayload> = {
    ...input,
    clientOperationSeq: restored.nextClientOperationSeq,
    status: 'PENDING',
  }
  return {
    operation,
    queue: {
      nextClientOperationSeq: restored.nextClientOperationSeq + 1,
      operations: [...restored.operations, operation],
    },
  }
}

export function acknowledgeOperation(queue: OperationQueue, clientOperationSeq: number): OperationQueue {
  const restored = restoreOperationQueue(queue)
  if (!Number.isSafeInteger(clientOperationSeq) || clientOperationSeq < 1
    || clientOperationSeq >= restored.nextClientOperationSeq) {
    throw new Error('ACK sequence is outside the local operation range')
  }
  const found = restored.operations.some((operation) => operation.clientOperationSeq === clientOperationSeq)
  if (!found) return restored

  const acknowledged = restored.operations.map((operation) => operation.clientOperationSeq === clientOperationSeq
    ? { ...operation, status: 'ACKED' as const }
    : operation)
  const firstPending = acknowledged.findIndex((operation) => operation.status !== 'ACKED')
  return {
    nextClientOperationSeq: restored.nextClientOperationSeq,
    operations: firstPending < 0 ? [] : acknowledged.slice(firstPending),
  }
}
