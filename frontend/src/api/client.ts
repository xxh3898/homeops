import type {
  ActivityPage,
  ContainerDetail,
  ContainerInventory,
  MetricHistory,
  MetricHistoryPeriod,
  SystemSummary,
} from './types'

export const API_REQUEST_TIMEOUT_MS = 8_000
export const API_CONNECTION_ERROR_MESSAGE =
  'HomeOps could not be reached. Check Tailscale and confirm the Mac mini is online.'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export class ApiConnectionError extends Error {
  constructor() {
    super(API_CONNECTION_ERROR_MESSAGE)
    this.name = 'ApiConnectionError'
  }
}

export function isAuthorizationError(error: unknown): error is ApiError {
  return error instanceof ApiError && (error.status === 401 || error.status === 403)
}

export function isConnectionError(error: unknown): error is ApiConnectionError {
  return error instanceof ApiConnectionError
}

export function shouldRetryQuery(failureCount: number, error: unknown) {
  return !isAuthorizationError(error) && failureCount < 1
}

export function isContainerDetailTerminalError(error: unknown): error is ApiError {
  return error instanceof ApiError && (error.status === 400 || error.status === 404 || error.status === 409)
}

export function shouldRetryContainerDetailQuery(failureCount: number, error: unknown) {
  if (isContainerDetailTerminalError(error)) {
    return false
  }
  return shouldRetryQuery(failureCount, error)
}

async function getJson<T>(path: string, callerSignal?: AbortSignal): Promise<T> {
  if (callerSignal?.aborted) {
    throw callerAbortReason(callerSignal)
  }

  const controller = new AbortController()
  let timeoutTriggered = false
  let abortFromCaller: (() => void) | undefined
  let timeoutId: ReturnType<typeof setTimeout> | undefined

  const timeoutPromise = new Promise<never>((_, reject) => {
    timeoutId = setTimeout(() => {
      timeoutTriggered = true
      controller.abort()
      reject(new ApiConnectionError())
    }, API_REQUEST_TIMEOUT_MS)
  })

  const cancellationPromise = callerSignal
    ? new Promise<never>((_, reject) => {
        abortFromCaller = () => {
          controller.abort(callerSignal.reason)
          reject(callerAbortReason(callerSignal))
        }
        callerSignal.addEventListener('abort', abortFromCaller, { once: true })
      })
    : undefined

  const requestPromise = requestJson<T>(path, controller.signal, callerSignal, () => timeoutTriggered)

  try {
    return await Promise.race([
      requestPromise,
      timeoutPromise,
      ...(cancellationPromise ? [cancellationPromise] : []),
    ])
  } finally {
    if (timeoutId !== undefined) {
      clearTimeout(timeoutId)
    }
    if (callerSignal && abortFromCaller) {
      callerSignal.removeEventListener('abort', abortFromCaller)
    }
  }
}

async function requestJson<T>(
  path: string,
  signal: AbortSignal,
  callerSignal: AbortSignal | undefined,
  timedOut: () => boolean,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      signal,
      headers: {
        Accept: 'application/json',
      },
    })
  } catch {
    if (callerSignal?.aborted) {
      throw callerAbortReason(callerSignal)
    }
    throw new ApiConnectionError()
  }

  if (!response.ok) {
    throw new ApiError(response.status, messageForStatus(response.status))
  }

  try {
    return (await response.json()) as T
  } catch (error) {
    if (callerSignal?.aborted) {
      throw callerAbortReason(callerSignal)
    }
    if (timedOut()) {
      throw new ApiConnectionError()
    }
    throw error
  }
}

function callerAbortReason(signal: AbortSignal) {
  return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError')
}

function messageForStatus(status: number) {
  if (status === 401 || status === 403) {
    return 'Tailscale identity is not authorized for HomeOps.'
  }
  if (status >= 500) {
    return 'HomeOps API is temporarily unavailable.'
  }
  return `HomeOps request failed with status ${status}.`
}

export function getSystemSummary(signal?: AbortSignal) {
  return getJson<SystemSummary>('/api/v1/system/summary', signal)
}

export function getMetricHistory(period: MetricHistoryPeriod, signal?: AbortSignal) {
  const parameters = new URLSearchParams({ period })
  return getJson<MetricHistory>(`/api/v1/system/metrics/history?${parameters}`, signal)
}

export function getContainers(signal?: AbortSignal) {
  return getJson<ContainerInventory>('/api/v1/containers', signal)
}

export function getContainerDetail(id: string, signal?: AbortSignal) {
  return getJson<ContainerDetail>(`/api/v1/containers/${encodeURIComponent(id)}`, signal)
}

export function getActivity(cursor?: string, signal?: AbortSignal) {
  const parameters = new URLSearchParams({ limit: '25' })
  if (cursor) parameters.set('cursor', cursor)
  return getJson<ActivityPage>(`/api/v1/activity?${parameters}`, signal)
}
