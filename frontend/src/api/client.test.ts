import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  API_REQUEST_TIMEOUT_MS,
  ApiConnectionError,
  ApiContractError,
  ApiError,
  getContainerAction,
  getContainerDetail,
  getContainerLogs,
  getMetricHistory,
  getSessionCsrfToken,
  getSystemSummary,
  isAmbiguousContainerActionSubmissionError,
  isAuthorizationError,
  isConnectionError,
  isContainerDetailTerminalError,
  shouldRetryContainerDetailQuery,
  shouldRetryQuery,
  submitContainerAction,
} from './client'

const CONTAINER_ID = '0123456789ab'
const OPERATION_ID = '10000000-0000-4000-8000-000000000001'
const IDEMPOTENCY_KEY = '20000000-0000-4000-8000-000000000002'

describe('HomeOps API client', () => {
  afterEach(() => {
    vi.useRealTimers()
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
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
      }),
    )
  })

  it('encodes the bounded history period in a same-origin GET request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        period: '6h',
        from: '2026-08-17T06:00:00Z',
        to: '2026-08-17T12:00:00Z',
        bucketSeconds: 300,
        points: [],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getMetricHistory('6h')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/system/metrics/history?period=6h',
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'same-origin',
      }),
    )
  })

  it('encodes the container identifier as one URL path component', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getContainerDetail('abc/def')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/containers/abc%2Fdef',
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'same-origin',
      }),
    )
  })

  it('requests bounded container logs with no-store and explicit tail', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        containerId: '0123456789ab',
        requestedTail: 100,
        collectedAt: '2026-08-18T00:00:00Z',
        truncated: false,
        redactionApplied: false,
        lines: [],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getContainerLogs('abc/def', 100)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/containers/abc%2Fdef/logs?tail=100',
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
      }),
    )
  })

  it('accepts only the fixed X-XSRF-TOKEN session contract', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        principal: 'private-principal',
        csrfHeader: 'X-XSRF-TOKEN',
        csrfToken: 'bounded-csrf-token',
      }))
      .mockResolvedValueOnce(jsonResponse({
        principal: 'private-principal',
        csrfHeader: 'X-Arbitrary-Header',
        csrfToken: 'bounded-csrf-token',
      }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getSessionCsrfToken()).resolves.toBe('bounded-csrf-token')
    await expect(getSessionCsrfToken()).rejects.toEqual(
      new ApiContractError('HomeOps returned an invalid session security contract.'),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/session',
      expect.objectContaining({
        method: 'GET',
        cache: 'no-store',
        credentials: 'same-origin',
      }),
    )
  })

  it('submits only the fixed action route, headers, and exact confirmation body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(actionResponse(), 202))
    vi.stubGlobal('fetch', fetchMock)

    await expect(submitContainerAction(
      CONTAINER_ID,
      'START',
      IDEMPOTENCY_KEY,
      'bounded-csrf-token',
    )).resolves.toEqual(actionResponse())

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/containers/${CONTAINER_ID}/actions`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        body: JSON.stringify({ operation: 'START', confirmation: `START:${CONTAINER_ID}` }),
      }),
    )
    expect(init.headers).toEqual({
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': IDEMPOTENCY_KEY,
      'X-XSRF-TOKEN': 'bounded-csrf-token',
    })
    expect(init.headers).not.toHaveProperty('Origin')
    expect(init.headers).not.toHaveProperty('Host')
    expect(init.headers).not.toHaveProperty('X-Forwarded-Proto')
  })

  it('accepts 200 terminal and rejects mismatched success semantics as ambiguous', async () => {
    const terminal = actionResponse({
      status: 'APPLIED',
      reasonCode: 'APPLIED',
      completedAt: '2026-08-21T00:00:01Z',
    })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(terminal, 200))
      .mockResolvedValueOnce(jsonResponse(actionResponse(), 200))
    vi.stubGlobal('fetch', fetchMock)

    await expect(submitContainerAction(
      CONTAINER_ID,
      'START',
      IDEMPOTENCY_KEY,
      'bounded-csrf-token',
    )).resolves.toEqual(terminal)
    await expect(submitContainerAction(
      CONTAINER_ID,
      'START',
      IDEMPOTENCY_KEY,
      'bounded-csrf-token',
    )).rejects.toBeInstanceOf(ApiContractError)
  })

  it('polls only the fixed public operation resource and validates its identifier', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(actionResponse()))
    vi.stubGlobal('fetch', fetchMock)

    await getContainerAction(OPERATION_ID)

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/container-actions/${OPERATION_ID}`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    await expect(getContainerAction('../private')).rejects.toBeInstanceOf(ApiContractError)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('classifies only ambiguous submission outcomes for same-key recovery', () => {
    expect(isAmbiguousContainerActionSubmissionError(new ApiConnectionError())).toBe(true)
    expect(isAmbiguousContainerActionSubmissionError(new ApiContractError())).toBe(true)
    expect(isAmbiguousContainerActionSubmissionError(new ApiError(408, 'timeout'))).toBe(true)
    expect(isAmbiguousContainerActionSubmissionError(new ApiError(503, 'unavailable'))).toBe(true)
    expect(isAmbiguousContainerActionSubmissionError(new ApiError(422, 'denied'))).toBe(false)
    expect(isAmbiguousContainerActionSubmissionError(new ApiError(429, 'limited'))).toBe(false)
  })

  it('converts fetch failures to safe reachability errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch private host')))

    await expect(getSystemSummary()).rejects.toEqual(new ApiConnectionError())
  })

  it('cleans the request deadline and caller listener after success', async () => {
    vi.useFakeTimers()
    const controller = new AbortController()
    const removeEventListener = vi.spyOn(controller.signal, 'removeEventListener')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      ),
    )

    await getSystemSummary(controller.signal)

    expect(vi.getTimerCount()).toBe(0)
    expect(removeEventListener).toHaveBeenCalledWith('abort', expect.any(Function))
  })

  it('aborts and reports a safe reachability error after eight seconds', async () => {
    vi.useFakeTimers()
    const fetchMock = abortAwareFetch()
    vi.stubGlobal('fetch', fetchMock)

    const request = getSystemSummary()
    let settled = false
    void request.then(
      () => {
        settled = true
      },
      () => {
        settled = true
      },
    )
    const expectation = expect(request).rejects.toEqual(new ApiConnectionError())

    await vi.advanceTimersByTimeAsync(API_REQUEST_TIMEOUT_MS - 1)
    expect(settled).toBe(false)

    await vi.advanceTimersByTimeAsync(1)
    await expectation

    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('preserves a caller cancellation before the request starts', async () => {
    const fetchMock = vi.fn()
    const controller = new AbortController()
    const reason = new DOMException('query cancelled', 'AbortError')
    controller.abort(reason)
    vi.stubGlobal('fetch', fetchMock)

    await expect(getSystemSummary(controller.signal)).rejects.toBe(reason)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('forwards an in-flight caller cancellation without misclassifying it', async () => {
    const fetchMock = abortAwareFetch()
    const controller = new AbortController()
    const reason = new DOMException('query cancelled', 'AbortError')
    vi.stubGlobal('fetch', fetchMock)

    const expectation = expect(getSystemSummary(controller.signal)).rejects.toBe(reason)
    controller.abort(reason)

    await expectation
    expect(fetchMock).toHaveBeenCalledOnce()
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

  it('recognizes only bounded reachability failures as connection errors', () => {
    expect(isConnectionError(new ApiConnectionError())).toBe(true)
    expect(isConnectionError(new ApiError(503, 'temporarily unavailable'))).toBe(false)
    expect(isConnectionError(new Error('unrelated'))).toBe(false)
  })

  it('never retries authorization errors but retains one retry for transient failures', () => {
    expect(shouldRetryQuery(0, new ApiError(401, 'access denied'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(403, 'access denied'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(500, 'temporarily unavailable'))).toBe(true)
    expect(shouldRetryQuery(0, new ApiConnectionError())).toBe(true)
    expect(shouldRetryQuery(1, new Error('network unavailable'))).toBe(false)
  })

  it.each([400, 404, 409])('treats container detail status %s as terminal', (status) => {
    const error = new ApiError(status, 'deterministic resource failure')

    expect(isContainerDetailTerminalError(error)).toBe(true)
    expect(shouldRetryContainerDetailQuery(0, error)).toBe(false)
  })

  it('keeps container detail authorization and transient retries aligned with the global policy', () => {
    expect(shouldRetryContainerDetailQuery(0, new ApiError(401, 'access denied'))).toBe(false)
    expect(shouldRetryContainerDetailQuery(0, new ApiError(403, 'access denied'))).toBe(false)
    expect(shouldRetryContainerDetailQuery(0, new ApiError(503, 'inventory unavailable'))).toBe(true)
    expect(shouldRetryContainerDetailQuery(0, new ApiConnectionError())).toBe(true)
    expect(shouldRetryContainerDetailQuery(1, new ApiError(503, 'inventory unavailable'))).toBe(false)
    expect(isContainerDetailTerminalError(new ApiError(503, 'inventory unavailable'))).toBe(false)
  })
})

function abortAwareFetch() {
  return vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal
      if (!(signal instanceof AbortSignal)) {
        reject(new Error('expected an AbortSignal'))
        return
      }
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    }),
  )
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function actionResponse(overrides: Record<string, unknown> = {}) {
  return {
    operationId: OPERATION_ID,
    containerId: CONTAINER_ID,
    operation: 'START',
    status: 'REQUESTED',
    reasonCode: null,
    requestedAt: '2026-08-21T00:00:00Z',
    completedAt: null,
    ...overrides,
  }
}
