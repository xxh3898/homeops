import type {
  ActivityPage,
  ActivityTypeFilter,
  ContainerActionResponse,
  ContainerDetail,
  ContainerInventory,
  ContainerLogResponse,
  ContainerLogTail,
  ContainerControlOperation,
  MetricHistory,
  MetricHistoryPeriod,
  SystemSummary,
} from './types'
import { containerActionStatuses, containerControlOperations } from './types'

export const API_REQUEST_TIMEOUT_MS = 8_000
export const API_CONNECTION_ERROR_MESSAGE =
  'HomeOps could not be reached. Check Tailscale and confirm the Mac mini is online.'
export const CONTAINER_ACTION_ORIGIN_REJECTED_PROBLEM_TYPE =
  'urn:homeops:problem:container-action-origin-rejected'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly problemType: string | null = null,
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

export class ApiContractError extends Error {
  constructor(message = 'HomeOps returned an unexpected API response.') {
    super(message)
    this.name = 'ApiContractError'
  }
}

export function isAuthorizationError(error: unknown): error is ApiError {
  return error instanceof ApiError
    && (error.status === 401
      || (error.status === 403 && !isContainerActionOriginRejectedError(error)))
}

export function isContainerActionOriginRejectedError(error: unknown): error is ApiError {
  return error instanceof ApiError
    && error.status === 403
    && error.problemType === CONTAINER_ACTION_ORIGIN_REJECTED_PROBLEM_TYPE
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

export function isInvalidActivityCursorError(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 400
}

export function shouldRetryActivityQuery(failureCount: number, error: unknown) {
  if (isInvalidActivityCursorError(error)) {
    return false
  }
  return shouldRetryQuery(failureCount, error)
}

export function isAmbiguousContainerActionSubmissionError(error: unknown) {
  return error instanceof ApiConnectionError
    || error instanceof ApiContractError
    || (error instanceof ApiError && (error.status === 408 || error.status >= 500))
}

interface JsonRequest {
  method: 'GET' | 'POST'
  headers: Record<string, string>
  body?: string
}

interface JsonResponse<T> {
  status: number
  body: T
}

async function getJson<T>(path: string, callerSignal?: AbortSignal): Promise<T> {
  const response = await sendJson<T>(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
  }, callerSignal)
  return response.body
}

async function sendJson<T>(
  path: string,
  request: JsonRequest,
  callerSignal?: AbortSignal,
): Promise<JsonResponse<T>> {
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

  const requestPromise = requestJson<T>(
    path,
    request,
    controller.signal,
    callerSignal,
    () => timeoutTriggered,
  )

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
  request: JsonRequest,
  signal: AbortSignal,
  callerSignal: AbortSignal | undefined,
  timedOut: () => boolean,
): Promise<JsonResponse<T>> {
  let response: Response
  try {
    response = await fetch(path, {
      method: request.method,
      credentials: 'same-origin',
      cache: 'no-store',
      signal,
      headers: request.headers,
      ...(request.body === undefined ? {} : { body: request.body }),
    })
  } catch {
    if (callerSignal?.aborted) {
      throw callerAbortReason(callerSignal)
    }
    throw new ApiConnectionError()
  }

  if (!response.ok) {
    const problemType = await readKnownProblemType(response)
    if (callerSignal?.aborted) {
      throw callerAbortReason(callerSignal)
    }
    if (timedOut()) {
      throw new ApiConnectionError()
    }
    throw new ApiError(
      response.status,
      messageForStatus(response.status, problemType),
      problemType,
    )
  }

  try {
    return {
      status: response.status,
      body: (await response.json()) as T,
    }
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

async function readKnownProblemType(response: Response) {
  if (response.status !== 403) {
    return null
  }
  const contentType = response.headers.get('Content-Type')
    ?.split(';', 1)[0]
    .trim()
    .toLowerCase()
  if (contentType !== 'application/problem+json' && contentType !== 'application/json') {
    return null
  }
  try {
    const body = await response.json() as unknown
    return isRecord(body)
      && body.type === CONTAINER_ACTION_ORIGIN_REJECTED_PROBLEM_TYPE
      ? CONTAINER_ACTION_ORIGIN_REJECTED_PROBLEM_TYPE
      : null
  } catch {
    return null
  }
}

function messageForStatus(status: number, problemType: string | null) {
  if (problemType === CONTAINER_ACTION_ORIGIN_REJECTED_PROBLEM_TYPE) {
    return 'HomeOps rejected the container action origin.'
  }
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

export function getContainerLogs(id: string, tail: ContainerLogTail, signal?: AbortSignal) {
  const parameters = new URLSearchParams({ tail: String(tail) })
  return getJson<ContainerLogResponse>(
    `/api/v1/containers/${encodeURIComponent(id)}/logs?${parameters}`,
    signal,
  )
}

export async function getSessionCsrfToken(signal?: AbortSignal) {
  const session = await getJson<unknown>('/api/v1/session', signal)
  if (!isRecord(session)
      || session.csrfHeader !== 'X-XSRF-TOKEN'
      || typeof session.csrfToken !== 'string'
      || session.csrfToken.length < 1
      || session.csrfToken.length > 512) {
    throw new ApiContractError('HomeOps returned an invalid session security contract.')
  }
  return session.csrfToken
}

export async function submitContainerAction(
  containerId: string,
  operation: ContainerControlOperation,
  idempotencyKey: string,
  csrfToken: string,
  signal?: AbortSignal,
) {
  requireContainerIdentifier(containerId)
  requireControlOperation(operation)
  if (!canonicalUuidPattern.test(idempotencyKey)
      || csrfToken.length < 1
      || csrfToken.length > 512) {
    throw new ApiContractError('Container action request contract is invalid.')
  }

  let response: JsonResponse<unknown>
  try {
    response = await sendJson<unknown>(
      `/api/v1/containers/${encodeURIComponent(containerId)}/actions`,
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey,
          'X-XSRF-TOKEN': csrfToken,
        },
        body: JSON.stringify({
          operation,
          confirmation: `${operation}:${containerId}`,
        }),
      },
      signal,
    )
  } catch (error) {
    if (error instanceof ApiError
        || error instanceof ApiConnectionError
        || isAbortError(error)) {
      throw error
    }
    throw new ApiContractError()
  }

  const action = parseContainerActionResponse(response.body)
  if (action.containerId !== containerId || action.operation !== operation) {
    throw new ApiContractError()
  }
  if ((response.status === 202 && action.status !== 'REQUESTED')
      || (response.status === 200 && action.status === 'REQUESTED')
      || (response.status !== 200 && response.status !== 202)) {
    throw new ApiContractError()
  }
  return action
}

export async function getContainerAction(operationId: string, signal?: AbortSignal) {
  if (!canonicalUuidPattern.test(operationId)) {
    throw new ApiContractError('Container action identifier is invalid.')
  }
  const action = parseContainerActionResponse(await getJson<unknown>(
    `/api/v1/container-actions/${encodeURIComponent(operationId)}`,
    signal,
  ))
  if (action.operationId !== operationId) {
    throw new ApiContractError()
  }
  return action
}

export function getActivity(type: ActivityTypeFilter, cursor?: string, signal?: AbortSignal) {
  const parameters = new URLSearchParams({ limit: '25' })
  if (type !== 'ALL') parameters.set('type', type)
  if (cursor) parameters.set('cursor', cursor)
  return getJson<ActivityPage>(`/api/v1/activity?${parameters}`, signal)
}

const containerIdentifierPattern = /^[0-9a-f]{12}$/
const canonicalUuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function requireContainerIdentifier(containerId: string) {
  if (!containerIdentifierPattern.test(containerId)) {
    throw new ApiContractError('Container action target is invalid.')
  }
}

function requireControlOperation(operation: string): asserts operation is ContainerControlOperation {
  if (!(containerControlOperations as readonly string[]).includes(operation)) {
    throw new ApiContractError('Container control operation is invalid.')
  }
}

function parseContainerActionResponse(value: unknown): ContainerActionResponse {
  if (!isRecord(value)
      || !hasOnlyKeys(value, [
        'operationId',
        'containerId',
        'operation',
        'status',
        'reasonCode',
        'requestedAt',
        'completedAt',
      ])
      || typeof value.operationId !== 'string'
      || !canonicalUuidPattern.test(value.operationId)
      || typeof value.containerId !== 'string'
      || !containerIdentifierPattern.test(value.containerId)
      || typeof value.operation !== 'string'
      || !(containerControlOperations as readonly string[]).includes(value.operation)
      || typeof value.status !== 'string'
      || !(containerActionStatuses as readonly string[]).includes(value.status)
      || typeof value.requestedAt !== 'string'
      || !validTimestamp(value.requestedAt)
      || !optionalTimestamp(value.completedAt)
      || !optionalBoundedString(value.reasonCode, 64)) {
    throw new ApiContractError()
  }

  return {
    operationId: value.operationId,
    containerId: value.containerId,
    operation: value.operation as ContainerControlOperation,
    status: value.status as ContainerActionResponse['status'],
    reasonCode: typeof value.reasonCode === 'string' ? value.reasonCode : null,
    requestedAt: value.requestedAt,
    completedAt: typeof value.completedAt === 'string' ? value.completedAt : null,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]) {
  return Object.keys(value).every((key) => allowed.includes(key))
}

function optionalTimestamp(value: unknown) {
  return value === undefined || value === null
    || (typeof value === 'string' && validTimestamp(value))
}

function validTimestamp(value: string) {
  return value.endsWith('Z') && Number.isFinite(Date.parse(value))
}

function optionalBoundedString(value: unknown, maxLength: number) {
  return value === undefined || value === null
    || (typeof value === 'string' && value.length <= maxLength)
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
