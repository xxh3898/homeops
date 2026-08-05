import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getSystemSummary, isAuthorizationError, shouldRetryQuery } from './client'

describe('HomeOps API client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses no-store and same-origin credentials for status data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          agentStatus: 'OFFLINE',
          lastUpdatedAt: null,
          stale: true,
          host: null,
          docker: { total: 0, running: 0, notRunning: 0, unhealthy: 0 },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getSystemSummary()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/system/summary',
      expect.objectContaining({ cache: 'no-store', credentials: 'same-origin' }),
    )
  })

  it('returns a safe authorization message without response body details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('sensitive upstream body', { status: 401 })))

    await expect(getSystemSummary()).rejects.toEqual(
      new ApiError(401, 'Tailscale identity is not authorized for HomeOps.'),
    )
  })

  it.each([401, 403])('recognizes status %s as an authorization error', (status) => {
    expect(isAuthorizationError(new ApiError(status, 'access denied'))).toBe(true)
  })

  it('does not classify transient or unrelated errors as authorization failures', () => {
    expect(isAuthorizationError(new ApiError(500, 'temporarily unavailable'))).toBe(false)
    expect(isAuthorizationError(new Error('network unavailable'))).toBe(false)
  })

  it('never retries authorization errors but retains one retry for transient failures', () => {
    expect(shouldRetryQuery(0, new ApiError(401, 'access denied'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(403, 'access denied'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(500, 'temporarily unavailable'))).toBe(true)
    expect(shouldRetryQuery(1, new Error('network unavailable'))).toBe(false)
  })
})
