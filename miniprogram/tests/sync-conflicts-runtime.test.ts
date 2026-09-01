import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const application = vi.hoisted(() => ({
  listSyncConflicts: vi.fn(),
  resolveSyncConflict: vi.fn(),
  reconcileSyncConflicts: vi.fn(),
  resolveSyncConflictWithLocalState: vi.fn(),
  workouts: {
    convergeConflict: vi.fn(),
    rememberConflictResolution: vi.fn(),
    pendingConflictResolutions: vi.fn(),
  },
  telemetry: {
    track: vi.fn(),
  },
  navigation: {
    back: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/progressCompositionRoot', () => ({
  getProgressApplication: () => application,
}))

const { default: SyncConflictsPage } = await import(
  '../src/presentation/pages/sync-conflicts'
)

const conflict = {
  id: 'conflict-runtime-0001',
  entityType: 'WORKOUT_SET',
  entityKey: 'set-runtime-0001',
  localEvidence: { actualReps: '8', actualWeightKg: '25' },
  serverEvidence: { actualReps: '9', actualWeightKg: '30' },
  status: 'OPEN' as const,
  version: 0,
  createdAt: '2026-08-12T00:00:00Z',
}

function result(resolution: 'KEEP_LOCAL' | 'KEEP_SERVER' | 'KEEP_BOTH') {
  return {
    conflictId: conflict.id,
    clientOperationSeq: 1,
    clientKey: conflict.entityKey,
    resolution,
    outcome: resolution === 'KEEP_LOCAL' ? 'ACKNOWLEDGED' as const : 'ABANDONED' as const,
    authoritativeSessionVersion: 2,
    authoritativePayload: {
      kind: 'WORKOUT_SET',
      sessionId: 'server-session-runtime',
      authoritativeSessionVersion: 2,
    },
  }
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('sync conflicts runtime interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.listSyncConflicts
      .mockResolvedValueOnce([conflict])
      .mockResolvedValue([])
    application.resolveSyncConflict.mockImplementation(
      async (_id: string, request: { resolution: 'KEEP_LOCAL' | 'KEEP_SERVER' | 'KEEP_BOTH' }) => (
        result(request.resolution)
      ),
    )
    application.workouts.convergeConflict.mockResolvedValue(null)
    application.workouts.rememberConflictResolution.mockResolvedValue(true)
    application.workouts.pendingConflictResolutions.mockResolvedValue([])
    application.reconcileSyncConflicts.mockImplementation(async () => {
      const remembered = await application.workouts.pendingConflictResolutions()
      for (const intent of remembered) {
        const authoritative = await application.resolveSyncConflict(intent.conflictId, {
          resolution: intent.resolution,
          expectedVersion: intent.expectedConflictVersion,
        })
        await application.workouts.convergeConflict(authoritative)
      }
      return application.listSyncConflicts()
    })
    application.resolveSyncConflictWithLocalState.mockImplementation(async (intent) => {
      await application.workouts.rememberConflictResolution(intent)
      const authoritative = await application.resolveSyncConflict(intent.conflictId, {
        resolution: intent.resolution,
        expectedVersion: intent.expectedConflictVersion,
      })
      await application.workouts.convergeConflict(authoritative)
      return authoritative
    })
  })

  it.each([
    ['尝试保留设备记录', 'KEEP_LOCAL'],
    ['使用已同步记录', 'KEEP_SERVER'],
    ['尝试两份都保留', 'KEEP_BOTH'],
  ] as const)('resolves %s and converges the local queue from the authoritative response', async (label, resolution) => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    await act(async () => {
      button(renderer!, label).props.onClick()
      await flushPage()
    })

    expect(application.resolveSyncConflict).toHaveBeenCalledWith(conflict.id, {
      resolution,
      expectedVersion: 0,
    })
    expect(application.workouts.rememberConflictResolution).toHaveBeenCalledWith({
      conflictId: conflict.id,
      clientKey: conflict.entityKey,
      resolution,
      expectedConflictVersion: 0,
    })
    expect(application.workouts.convergeConflict).toHaveBeenCalledWith(result(resolution))
    expect(application.telemetry.track).toHaveBeenCalledWith('sync_conflict_resolved', {
      resolution: ({ KEEP_LOCAL: 'keep_local', KEEP_SERVER: 'keep_server', KEEP_BOTH: 'keep_both' } as const)[resolution],
    })
    expect(application.listSyncConflicts).toHaveBeenCalledTimes(2)
  })

  it('keeps both records visible and retryable when local convergence fails', async () => {
    application.workouts.convergeConflict.mockRejectedValue(new Error('storage full'))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    await act(async () => {
      button(renderer!, '使用已同步记录').props.onClick()
      await flushPage()
    })

    expect(JSON.stringify(renderer.toJSON())).toContain('记录不会丢失，请稍后重试同一选择')
    expect(application.listSyncConflicts).toHaveBeenCalledOnce()
  })

  it('replays a durable local decision before asking the server for open conflicts', async () => {
    const intent = {
      conflictId: conflict.id,
      clientKey: conflict.entityKey,
      resolution: 'KEEP_SERVER' as const,
      expectedConflictVersion: 0,
    }
    application.workouts.pendingConflictResolutions.mockResolvedValue([intent])
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    expect(application.resolveSyncConflict).toHaveBeenCalledWith(conflict.id, {
      resolution: 'KEEP_SERVER', expectedVersion: 0,
    })
    expect(application.workouts.convergeConflict).toHaveBeenCalledWith(result('KEEP_SERVER'))
    expect(application.resolveSyncConflict.mock.invocationCallOrder[0])
      .toBeLessThan(application.listSyncConflicts.mock.invocationCallOrder[0])
  })

  it('recovers an initial list failure through the visible retry action', async () => {
    application.listSyncConflicts.mockReset()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce([conflict])
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('暂时无法完成上次选择')
    await act(async () => {
      button(renderer!, '重新检查').props.onClick()
      await flushPage()
    })

    expect(application.listSyncConflicts).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('同一训练组有两份记录')
  })

  it('coalesces rapid retry taps so durable intents are not replayed twice', async () => {
    let resolveRetry: ((value: unknown) => void) | undefined
    application.listSyncConflicts.mockReset()
      .mockRejectedValueOnce(new Error('offline'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    act(() => {
      button(renderer!, '重新检查').props.onClick()
      button(renderer!, '重新检查').props.onClick()
    })
    await act(async () => {
      resolveRetry?.([conflict])
      await flushPage()
    })

    expect(application.listSyncConflicts).toHaveBeenCalledTimes(2)
    expect(application.workouts.pendingConflictResolutions).toHaveBeenCalledTimes(2)
  })

  it('coalesces rapid conflict-decision clicks before the disabled state renders', async () => {
    const resolution = deferred<ReturnType<typeof result>>()
    application.resolveSyncConflict.mockReturnValueOnce(resolution.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(SyncConflictsPage))
      await flushPage()
    })
    if (!renderer) throw new Error('sync conflict page did not render')

    const action = button(renderer, '使用已同步记录')
    act(() => {
      action.props.onClick()
      action.props.onClick()
    })
    expect(application.workouts.rememberConflictResolution).toHaveBeenCalledOnce()
    await act(async () => {
      await flushPage()
    })
    expect(application.resolveSyncConflict).toHaveBeenCalledOnce()

    await act(async () => {
      resolution.resolve(result('KEEP_SERVER'))
      await flushPage()
    })
  })
})
