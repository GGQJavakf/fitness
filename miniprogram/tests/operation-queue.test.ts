import { describe, expect, it } from 'vitest'

import {
  acknowledgeOperation,
  createOperationQueue,
  enqueueOperation,
  restoreOperationQueue,
} from '../src/domain/sync/OperationQueue'

describe('workout operation queue', () => {
  it('assigns strictly increasing client sequence numbers', () => {
    const first = enqueueOperation(createOperationQueue(), {
      idempotencyKey: 'set-key-0001',
      type: 'UPSERT_SET',
      payload: { reps: 10 },
      createdAtUtc: '2026-07-24T08:00:00Z',
    })
    const second = enqueueOperation(first.queue, {
      idempotencyKey: 'set-key-0002',
      type: 'UPSERT_SET',
      payload: { reps: 9 },
      createdAtUtc: '2026-07-24T08:01:00Z',
    })

    expect(first.operation.clientOperationSeq).toBe(1)
    expect(second.operation.clientOperationSeq).toBe(2)
    expect(second.queue.nextClientOperationSeq).toBe(3)
  })

  it('does not clear an operation before ACK and compacts only an ACKed prefix', () => {
    const first = enqueueOperation(createOperationQueue(), operation('set-key-0001'))
    const second = enqueueOperation(first.queue, operation('set-key-0002'))

    const outOfOrderAck = acknowledgeOperation(second.queue, 2)
    expect(outOfOrderAck.operations.map(({ clientOperationSeq, status }) => [clientOperationSeq, status]))
      .toEqual([[1, 'PENDING'], [2, 'ACKED']])

    const compacted = acknowledgeOperation(outOfOrderAck, 1)
    expect(compacted.operations).toEqual([])
    expect(compacted.nextClientOperationSeq).toBe(3)
  })

  it('rejects damaged queues with duplicate or non-monotonic sequence state', () => {
    expect(() => restoreOperationQueue({
      nextClientOperationSeq: 2,
      operations: [
        { ...operation('set-key-0001'), clientOperationSeq: 1, status: 'PENDING' },
        { ...operation('set-key-0002'), clientOperationSeq: 1, status: 'PENDING' },
      ],
    })).toThrow('strictly increasing')
  })
})

function operation(idempotencyKey: string) {
  return {
    idempotencyKey,
    type: 'UPSERT_SET' as const,
    payload: { reps: 10 },
    createdAtUtc: '2026-07-24T08:00:00Z',
  }
}
