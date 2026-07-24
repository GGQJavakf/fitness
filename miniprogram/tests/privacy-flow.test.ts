import { describe, expect, it, vi } from 'vitest'

import {
  createPrivacyUseCases,
  createVerifiedPrivacyUseCases,
  type PrivacyPort,
} from '../src/application/privacy'
import { FitnessApiClient } from '../src/infrastructure/api/client'

describe('privacy flow', () => {
  it('requires identity re-verification and exact second confirmation before deletion', async () => {
    const port: PrivacyPort = {
      exportData: vi.fn(),
      requestDeletion: vi.fn().mockResolvedValue({
        id: 'request-1', status: 'REQUESTED', requestedAt: '2026-07-24T08:00:00Z',
        deletionScope: ['PROFILE'], retainedCategories: ['SECURITY_AUDIT'],
      }),
      getDeletionRequest: vi.fn(),
    }
    const privacy = createPrivacyUseCases(port)

    await expect(privacy.requestDeletion('', 'DELETE')).rejects.toThrow('重新验证身份')
    await expect(privacy.requestDeletion('fresh-proof', '删除')).rejects.toThrow('输入 DELETE')
    await privacy.requestDeletion('fresh-proof', 'DELETE')

    expect(port.requestDeletion).toHaveBeenCalledExactlyOnceWith({
      reauthenticationProof: 'fresh-proof',
      confirmationText: 'DELETE',
    })
  })

  it('exports only declared categories and keeps retained data visibly separate', async () => {
    const port: PrivacyPort = {
      exportData: vi.fn().mockResolvedValue({
        generatedAt: '2026-07-24T08:00:00Z',
        scope: ['PROFILE', 'EQUIPMENT'],
        excludedRetentionCategories: ['SECURITY_AUDIT', 'LEGAL_HOLD'],
      }),
      requestDeletion: vi.fn(),
      getDeletionRequest: vi.fn(),
    }
    const privacy = createPrivacyUseCases(port)

    const view = await privacy.exportData('fresh-proof')

    expect(view.scopeLabel).toBe('档案、器械')
    expect(view.retentionNotice).toContain('安全审计、法定保留')
    expect(JSON.stringify(view)).not.toContain('other-user')
  })

  it('polls only the authenticated users opaque request id', async () => {
    const port: PrivacyPort = {
      exportData: vi.fn(),
      requestDeletion: vi.fn(),
      getDeletionRequest: vi.fn().mockResolvedValue({
        id: 'request-1', status: 'COMPLETED', requestedAt: '2026-07-24T08:00:00Z',
        deletionScope: ['PROFILE'], retainedCategories: ['SECURITY_AUDIT'],
      }),
    }
    const privacy = createPrivacyUseCases(port)

    const status = await privacy.getDeletionStatus('request-1')

    expect(status.statusLabel).toBe('已完成')
    expect(port.getDeletionRequest).toHaveBeenCalledWith('request-1')
  })

  it('sends fresh proof only to the privacy endpoint and keeps user identity out of commands', async () => {
    const request = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: {
        data: {
          generatedAt: '2026-07-24T08:00:00Z',
          scope: ['PROFILE'],
          excludedRetentionCategories: ['SECURITY_AUDIT'],
        },
        meta: { requestId: 'request-1', serverTime: '2026-07-24T08:00:00Z' },
      },
    })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request },
      {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-07-24T09:00:00Z',
        }),
        save: vi.fn(),
        clear: vi.fn(),
      },
    )

    await client.exportData('fresh-wechat-code')

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/v1/privacy/export',
      method: 'GET',
      headers: expect.objectContaining({
        'X-Reauthentication-Proof': 'fresh-wechat-code',
      }),
    }))
    expect(JSON.stringify(request.mock.calls[0])).not.toContain('userId')
  })

  it('obtains a fresh platform proof inside the application layer for every sensitive action', async () => {
    const port: PrivacyPort = {
      exportData: vi.fn().mockResolvedValue({
        generatedAt: '2026-07-24T08:00:00Z',
        scope: [],
        excludedRetentionCategories: [],
      }),
      requestDeletion: vi.fn().mockResolvedValue({
        id: 'request-1', status: 'REQUESTED', requestedAt: '2026-07-24T08:00:00Z',
        deletionScope: ['PROFILE'], retainedCategories: ['SECURITY_AUDIT'],
      }),
      getDeletionRequest: vi.fn(),
    }
    const proof = { getProof: vi.fn().mockResolvedValue('one-time-wechat-code') }
    const privacy = createVerifiedPrivacyUseCases(port, proof)

    await privacy.exportData()
    await privacy.requestDeletion('DELETE')

    expect(proof.getProof).toHaveBeenCalledTimes(2)
    expect(port.exportData).toHaveBeenCalledWith('one-time-wechat-code')
    expect(port.requestDeletion).toHaveBeenCalledWith({
      reauthenticationProof: 'one-time-wechat-code',
      confirmationText: 'DELETE',
    })
  })

  it('does not clear the valid app session when only reauthentication proof is rejected', async () => {
    const clear = vi.fn()
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        request: vi.fn().mockResolvedValue({
          statusCode: 401,
          data: {
            error: {
              code: 'REAUTHENTICATION_REQUIRED',
              message: 'upstream detail',
              fieldErrors: [],
              details: {},
              retryable: false,
            },
            meta: { requestId: 'request-1' },
          },
        }),
      },
      {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted', refreshToken: 'refresh-redacted',
          expiresAt: '2026-07-24T09:00:00Z',
        }),
        save: vi.fn(),
        clear,
      },
    )

    const error = await client.exportData('wrong-code').catch((reason: unknown) => reason)

    expect(error).toMatchObject({
      code: 'REAUTHENTICATION_REQUIRED',
      message: '请重新验证身份后继续',
    })
    expect(clear).not.toHaveBeenCalled()
  })
})
