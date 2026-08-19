export type WorkoutOperationType = 'UPSERT_SET' | 'UPDATE_SESSION_STATUS' | 'COMPLETE_SESSION'
export type WorkoutOperationStatus = 'PENDING' | 'ACKED' | 'CONFLICT' | 'REJECTED' | 'ABANDONED'

export interface WorkoutOperation<TPayload = unknown> {
  clientOperationSeq: number
  idempotencyKey: string
  type: WorkoutOperationType
  payload: TPayload
  createdAtUtc: string
  status: WorkoutOperationStatus
  conflictId?: string
  reasonCode?: string
  conflictResolutionIntent?: {
    resolution: 'KEEP_LOCAL' | 'KEEP_SERVER' | 'KEEP_BOTH'
    expectedConflictVersion: number
  }
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
      || !operationStatuses.has(operation.status)
      || (operation.status === 'CONFLICT' && !operation.conflictId)) {
      throw new Error('workout operation is invalid')
    }
    const intent = operation.conflictResolutionIntent
    if (intent && (operation.status !== 'CONFLICT'
      || !['KEEP_LOCAL', 'KEEP_SERVER', 'KEEP_BOTH'].includes(intent.resolution)
      || !Number.isSafeInteger(intent.expectedConflictVersion)
      || intent.expectedConflictVersion < 0)) {
      throw new Error('workout conflict resolution intent is invalid')
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
  return compactTerminalPrefix({
    nextClientOperationSeq: restored.nextClientOperationSeq,
    operations: acknowledged,
  })
}

export function markOperationConflict(
  queue: OperationQueue,
  clientOperationSeq: number,
  conflictId: string,
  reasonCode?: string,
): OperationQueue {
  if (!conflictId) throw new Error('sync conflict identity is required')
  return updateOperation(queue, clientOperationSeq, (operation) => ({
    ...operation,
    status: 'CONFLICT',
    conflictId,
    ...(reasonCode ? { reasonCode } : {}),
  }))
}

export function markOperationRejected(
  queue: OperationQueue,
  clientOperationSeq: number,
  reasonCode?: string,
): OperationQueue {
  return updateOperation(queue, clientOperationSeq, (operation) => ({
    ...operation,
    status: 'REJECTED',
    ...(reasonCode ? { reasonCode } : {}),
  }))
}

export function abandonBlockedOperations(queue: OperationQueue): OperationQueue {
  const restored = restoreOperationQueue(queue)
  return compactTerminalPrefix({
    nextClientOperationSeq: restored.nextClientOperationSeq,
    operations: restored.operations.map((operation) => (
      operation.status === 'CONFLICT' || operation.status === 'REJECTED'
        ? { ...operation, status: 'ABANDONED' as const }
        : operation
    )),
  })
}

export function abandonUnresolvedOperations(queue: OperationQueue): OperationQueue {
  const restored = restoreOperationQueue(queue)
  return compactTerminalPrefix({
    nextClientOperationSeq: restored.nextClientOperationSeq,
    operations: restored.operations.map((operation) => (
      operation.status === 'PENDING'
      || operation.status === 'CONFLICT'
      || operation.status === 'REJECTED'
        ? { ...operation, status: 'ABANDONED' as const }
        : operation
    )),
  })
}

export function abandonOperation(queue: OperationQueue, clientOperationSeq: number): OperationQueue {
  return compactTerminalPrefix(updateOperation(queue, clientOperationSeq, (operation) => ({
    ...operation,
    status: 'ABANDONED',
  })))
}

export function rememberConflictResolution(
  queue: OperationQueue,
  input: {
    conflictId: string
    clientKey: string
    resolution: 'KEEP_LOCAL' | 'KEEP_SERVER' | 'KEEP_BOTH'
    expectedConflictVersion: number
  },
): OperationQueue {
  const restored = restoreOperationQueue(queue)
  if (!input.conflictId || !input.clientKey
    || !['KEEP_LOCAL', 'KEEP_SERVER', 'KEEP_BOTH'].includes(input.resolution)
    || !Number.isSafeInteger(input.expectedConflictVersion) || input.expectedConflictVersion < 0) {
    throw new Error('conflict resolution intent is invalid')
  }
  const operation = restored.operations.find((item) => (
    item.status === 'CONFLICT'
    && item.conflictId === input.conflictId
    && item.idempotencyKey === input.clientKey
  ))
  if (!operation) return restored
  const existing = operation.conflictResolutionIntent
  if (existing && (existing.resolution !== input.resolution
    || existing.expectedConflictVersion !== input.expectedConflictVersion)) {
    throw new Error('a different conflict resolution is already pending')
  }
  if (existing) return restored
  return updateOperation(restored, operation.clientOperationSeq, (item) => ({
    ...item,
    conflictResolutionIntent: {
      resolution: input.resolution,
      expectedConflictVersion: input.expectedConflictVersion,
    },
  }))
}

export function rebuildRejectedOperations(queue: OperationQueue, createdAtUtc: string): OperationQueue {
  const rejected = restoreOperationQueue(queue).operations.filter((operation) => operation.status === 'REJECTED')
  return rejected.reduce((current, operation) => {
    const abandoned = abandonOperation(current, operation.clientOperationSeq)
    return enqueueOperation(abandoned, {
      idempotencyKey: operation.idempotencyKey,
      type: operation.type,
      payload: operation.payload,
      createdAtUtc,
    }).queue
  }, restoreOperationQueue(queue))
}

export function hasBlockingOperations(queue: OperationQueue): boolean {
  return restoreOperationQueue(queue).operations.some((operation) => (
    operation.status === 'PENDING'
    || operation.status === 'CONFLICT'
    || operation.status === 'REJECTED'
  ))
}

const operationStatuses = new Set<WorkoutOperationStatus>([
  'PENDING',
  'ACKED',
  'CONFLICT',
  'REJECTED',
  'ABANDONED',
])

function updateOperation(
  queue: OperationQueue,
  clientOperationSeq: number,
  update: (operation: WorkoutOperation) => WorkoutOperation,
): OperationQueue {
  const restored = restoreOperationQueue(queue)
  if (!Number.isSafeInteger(clientOperationSeq) || clientOperationSeq < 1
    || clientOperationSeq >= restored.nextClientOperationSeq) {
    throw new Error('operation sequence is outside the local operation range')
  }
  return {
    nextClientOperationSeq: restored.nextClientOperationSeq,
    operations: restored.operations.map((operation) => (
      operation.clientOperationSeq === clientOperationSeq ? update(operation) : operation
    )),
  }
}

function compactTerminalPrefix(queue: OperationQueue): OperationQueue {
  const firstUnresolved = queue.operations.findIndex((operation) => (
    operation.status !== 'ACKED' && operation.status !== 'ABANDONED'
  ))
  return {
    nextClientOperationSeq: queue.nextClientOperationSeq,
    operations: firstUnresolved < 0 ? [] : queue.operations.slice(firstUnresolved),
  }
}
