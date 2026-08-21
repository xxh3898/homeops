import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiConnectionError, ApiError } from '../api/client'
import type { ContainerActionResponse, ContainerView } from '../api/types'
import {
  CONTAINER_ACTION_POLL_INTERVAL_MS,
  CONTAINER_ACTION_POLL_MAX_MS,
  ContainerControlSection,
} from './ContainerControlSection'

const CONTAINER_ID = '0123456789ab'
const SECOND_ID = 'abcdefabcdef'
const OPERATION_ID = '10000000-0000-4000-8000-000000000001'
const IDEMPOTENCY_KEY = '20000000-0000-4000-8000-000000000002'
const SNAPSHOT_S0 = '2026-08-21T00:00:00Z'
const SNAPSHOT_S1 = '2026-08-21T00:00:05Z'
const SNAPSHOT_S2 = '2026-08-21T00:00:10Z'

const mocks = vi.hoisted(() => ({
  getSessionCsrfToken: vi.fn(),
  submitContainerAction: vi.fn(),
  getContainerAction: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getSessionCsrfToken: mocks.getSessionCsrfToken,
  submitContainerAction: mocks.submitContainerAction,
  getContainerAction: mocks.getContainerAction,
}))

describe('ContainerControlSection', () => {
  beforeEach(() => {
    mocks.getSessionCsrfToken.mockReset().mockResolvedValue('bounded-csrf-token')
    mocks.submitContainerAction.mockReset()
    mocks.getContainerAction.mockReset()
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(IDEMPOTENCY_KEY)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not fetch or mutate on mount and exposes only the conservative state matrix', () => {
    const view = renderSection()

    expect(actionButton('Start')).toBeDisabled()
    expect(actionButton('Stop')).toBeEnabled()
    expect(actionButton('Restart')).toBeEnabled()
    expect(screen.getByText(/latest snapshot allows Stop or Restart/)).toBeInTheDocument()
    expect(mocks.getSessionCsrfToken).not.toHaveBeenCalled()
    expect(mocks.submitContainerAction).not.toHaveBeenCalled()
    expect(mocks.getContainerAction).not.toHaveBeenCalled()

    view.rerenderProps({ container: container({ state: 'EXITED' }) })
    expect(actionButton('Start')).toBeEnabled()
    expect(actionButton('Stop')).toBeDisabled()
    expect(actionButton('Restart')).toBeDisabled()

    view.rerenderProps({ container: container({ state: 'RESTARTING' }) })
    expect(actionButton('Start')).toBeDisabled()
    expect(actionButton('Stop')).toBeDisabled()
    expect(actionButton('Restart')).toBeDisabled()
    expect(screen.getByText(/No control candidate is available.*RESTARTING/)).toBeInTheDocument()
  })

  it.each([
    [{ stale: true }, 'fresh container snapshot'],
    [{ online: false }, 'browser is offline'],
    [{ container: container({ managed: false }) }, 'exact managed inventory label'],
    [{ container: container({ composeProject: null }) }, 'reported non-HomeOps Compose project'],
    [{ container: container({ composeProject: 'unknown' }) }, 'reported non-HomeOps Compose project'],
    [{ container: container({ composeProject: 'homeops' }) }, 'reported non-HomeOps Compose project'],
  ])('fails closed for candidate condition %#', (overrides, message) => {
    renderSection(overrides)

    expect(actionButton('Start')).toBeDisabled()
    expect(actionButton('Stop')).toBeDisabled()
    expect(actionButton('Restart')).toBeDisabled()
    expect(screen.getByText(new RegExp(message))).toBeInTheDocument()
  })

  it('requires a semantic confirmation and cancel or Escape performs zero mutation', async () => {
    renderSection()
    const stop = actionButton('Stop')

    fireEvent.click(stop)
    const dialog = screen.getByRole('dialog', { name: 'Stop this running container? Active service traffic may be interrupted.' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveTextContent('safe-service')
    expect(dialog).toHaveTextContent(CONTAINER_ID)
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(stop).toHaveFocus())
    expect(mocks.getSessionCsrfToken).not.toHaveBeenCalled()
    expect(mocks.submitContainerAction).not.toHaveBeenCalled()

    fireEvent.click(actionButton('Restart'))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.submitContainerAction).not.toHaveBeenCalled()
  })

  it('closes an unconfirmed action on container change without submitting', () => {
    const view = renderSection()
    fireEvent.click(actionButton('Stop'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    view.rerenderProps({ container: container({ id: SECOND_ID, name: 'other-service' }) })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.getSessionCsrfToken).not.toHaveBeenCalled()
    expect(mocks.submitContainerAction).not.toHaveBeenCalled()
  })

  it('creates one canonical key and prevents a double confirm POST', async () => {
    const pending = deferred<ContainerActionResponse>()
    mocks.submitContainerAction.mockReturnValue(pending.promise)
    renderSection()

    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))

    const submitting = await screen.findByRole('button', { name: 'Submitting…' })
    expect(submitting).toBeDisabled()
    fireEvent.click(submitting)
    await waitFor(() => expect(mocks.submitContainerAction).toHaveBeenCalledOnce())
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledOnce()
    expect(mocks.submitContainerAction).toHaveBeenCalledWith(
      CONTAINER_ID,
      'STOP',
      IDEMPOTENCY_KEY,
      'bounded-csrf-token',
      expect.any(AbortSignal),
    )

    pending.resolve(actionResponse({ status: 'APPLIED', reasonCode: 'APPLIED', completedAt: '2026-08-21T00:00:01Z' }))
    expect(await screen.findByRole('region', { name: 'Last container action' })).toHaveTextContent('APPLIED')
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it.each([
    new ApiConnectionError(),
    new ApiError(503, 'safe unavailable'),
  ])('retains one in-memory key and retries ambiguous %s only after explicit action', async (ambiguousError) => {
    mocks.submitContainerAction
      .mockRejectedValueOnce(ambiguousError)
      .mockResolvedValueOnce(actionResponse({
        status: 'OUTCOME_UNKNOWN',
        reasonCode: 'DOCKER_OUTCOME_UNKNOWN',
        completedAt: '2026-08-21T00:00:02Z',
      }))
    renderSection()

    await confirm('Stop')
    expect(await screen.findByText('Submission status unknown')).toBeInTheDocument()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: 'Retry same request' }))

    expect(await screen.findByText(/operation outcome is uncertain/i)).toBeInTheDocument()
    expect(mocks.getSessionCsrfToken).toHaveBeenCalledTimes(2)
    expect(mocks.submitContainerAction).toHaveBeenCalledTimes(2)
    expect(mocks.submitContainerAction.mock.calls[0]?.[2]).toBe(IDEMPOTENCY_KEY)
    expect(mocks.submitContainerAction.mock.calls[1]?.[2]).toBe(IDEMPOTENCY_KEY)
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledOnce()
  })

  it.each([
    [400, 'fixed container action request was rejected'],
    [401, 'security session was rejected'],
    [403, 'security session was rejected'],
    [409, 'Another control operation is active'],
    [422, 'target is no longer eligible'],
    [429, 'Too many new container actions'],
  ])('shows a bounded definite error for HTTP %s without mutation retry', async (status, message) => {
    mocks.submitContainerAction.mockRejectedValue(new ApiError(status, 'private server detail'))
    renderSection()

    await confirm('Stop')

    expect(await screen.findByText(new RegExp(message))).toBeInTheDocument()
    expect(screen.queryByText('private server detail')).not.toBeInTheDocument()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('polls only the returned operation after 202 and stops at terminal', async () => {
    vi.useFakeTimers()
    mocks.submitContainerAction.mockResolvedValue(actionResponse())
    mocks.getContainerAction.mockResolvedValue(actionResponse({
      status: 'APPLIED',
      reasonCode: 'APPLIED',
      completedAt: '2026-08-21T00:00:02Z',
    }))
    const view = renderSection()
    const { queryClient } = view
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))
    await flushMicrotasks()
    expect(screen.getByRole('region', { name: 'Current container action' })).toHaveTextContent('REQUESTED')
    expect(mocks.getContainerAction).not.toHaveBeenCalled()

    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_INTERVAL_MS))
    await flushMicrotasks()

    expect(screen.getByRole('region', { name: 'Last container action' })).toHaveTextContent('APPLIED')
    expect(mocks.getContainerAction).toHaveBeenCalledOnce()
    expect(mocks.getContainerAction).toHaveBeenCalledWith(OPERATION_ID, expect.any(AbortSignal))
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['container', CONTAINER_ID], exact: true })
    expect(actionButton('Stop')).toBeDisabled()
    expect(screen.getByText(/Waiting for a newer Agent snapshot/)).toBeInTheDocument()

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S1 })
    expect(actionButton('Stop')).toBeEnabled()
  })

  it('requires a snapshot newer than the polling terminal barrier when a detail refresh arrived first', async () => {
    vi.useFakeTimers()
    mocks.submitContainerAction.mockResolvedValue(actionResponse())
    mocks.getContainerAction.mockResolvedValue(actionResponse({
      status: 'APPLIED',
      reasonCode: 'APPLIED',
      completedAt: '2026-08-21T00:00:06Z',
    }))
    const view = renderSection({ snapshotUpdatedAt: SNAPSHOT_S0 })

    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))
    await flushMicrotasks()
    expect(screen.getByRole('region', { name: 'Current container action' })).toHaveTextContent('REQUESTED')

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S1 })
    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_INTERVAL_MS))
    await flushMicrotasks()

    expect(screen.getByRole('region', { name: 'Last container action' })).toHaveTextContent('APPLIED')
    expect(actionButton('Stop')).toBeDisabled()
    expect(screen.getByText(/Waiting for a newer Agent snapshot/)).toBeInTheDocument()

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S1 })
    expect(actionButton('Stop')).toBeDisabled()

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S2 })
    expect(actionButton('Stop')).toBeEnabled()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('keeps OUTCOME_UNKNOWN locked past a pre-terminal snapshot without automatic mutation retry', async () => {
    vi.useFakeTimers()
    mocks.submitContainerAction.mockResolvedValue(actionResponse())
    mocks.getContainerAction.mockResolvedValue(actionResponse({
      status: 'OUTCOME_UNKNOWN',
      reasonCode: 'DOCKER_OUTCOME_UNKNOWN',
      completedAt: '2026-08-21T00:00:06Z',
    }))
    const view = renderSection({ snapshotUpdatedAt: SNAPSHOT_S0 })

    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))
    await flushMicrotasks()
    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S1 })

    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_INTERVAL_MS))
    await flushMicrotasks()

    expect(screen.getByText(/operation outcome is uncertain/i)).toBeInTheDocument()
    expect(actionButton('Stop')).toBeDisabled()
    expect(screen.getByText(/Waiting for a newer Agent snapshot/)).toBeInTheDocument()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S2 })
    expect(actionButton('Stop')).toBeEnabled()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('requires a snapshot newer than the POST 200 terminal barrier when a detail refresh arrived first', async () => {
    const pending = deferred<ContainerActionResponse>()
    mocks.submitContainerAction.mockReturnValue(pending.promise)
    const view = renderSection({ snapshotUpdatedAt: SNAPSHOT_S0 })

    await openAndConfirm('Stop')
    await waitFor(() => expect(mocks.submitContainerAction).toHaveBeenCalledOnce())
    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S1 })

    await act(async () => {
      pending.resolve(actionResponse({
        status: 'APPLIED',
        reasonCode: 'APPLIED',
        completedAt: '2026-08-21T00:00:06Z',
      }))
      await pending.promise
    })

    expect(await screen.findByRole('region', { name: 'Last container action' })).toHaveTextContent('APPLIED')
    expect(actionButton('Stop')).toBeDisabled()
    expect(screen.getByText(/Waiting for a newer Agent snapshot/)).toBeInTheDocument()
    expect(mocks.getContainerAction).not.toHaveBeenCalled()

    view.rerenderProps({ snapshotUpdatedAt: SNAPSHOT_S2 })
    expect(actionButton('Stop')).toBeEnabled()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('does not poll when the initial response is already terminal', async () => {
    mocks.submitContainerAction.mockResolvedValue(actionResponse({
      status: 'NOOP',
      reasonCode: 'ALREADY_STOPPED',
      completedAt: '2026-08-21T00:00:01Z',
    }))
    renderSection()

    await confirm('Stop')

    expect(await screen.findByText(/already stopped/)).toBeInTheDocument()
    expect(mocks.getContainerAction).not.toHaveBeenCalled()
  })

  it('pauses hidden polling, stops at the bound, and offers manual GET refresh only', async () => {
    vi.useFakeTimers()
    mocks.submitContainerAction.mockResolvedValue(actionResponse())
    mocks.getContainerAction.mockResolvedValue(actionResponse({
      status: 'EXPIRED',
      reasonCode: 'WORK_EXPIRED',
      completedAt: '2026-08-21T00:01:00Z',
    }))
    const view = renderSection({ visible: false })

    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))
    await flushMicrotasks()
    expect(screen.getByText('Status polling is paused while this page is hidden.')).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_MAX_MS))
    await flushMicrotasks()
    expect(mocks.getContainerAction).not.toHaveBeenCalled()

    view.rerenderProps({ visible: true })
    const refresh = screen.getByRole('button', { name: 'Refresh status' })
    fireEvent.click(refresh)
    await flushMicrotasks()

    expect(screen.getByText(/bounded work expired/)).toBeInTheDocument()
    expect(mocks.getContainerAction).toHaveBeenCalledOnce()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('cancels automatic GET polling while offline and resumes without another POST', async () => {
    vi.useFakeTimers()
    mocks.submitContainerAction.mockResolvedValue(actionResponse())
    mocks.getContainerAction.mockResolvedValue(actionResponse({
      status: 'APPLIED',
      reasonCode: 'APPLIED',
      completedAt: '2026-08-21T00:00:02Z',
    }))
    const view = renderSection()
    fireEvent.click(actionButton('Stop'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm stop' }))
    await flushMicrotasks()

    view.rerenderProps({ online: false })
    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_INTERVAL_MS * 2))
    expect(screen.getByText('Status polling is paused while offline.')).toBeInTheDocument()
    expect(mocks.getContainerAction).not.toHaveBeenCalled()

    view.rerenderProps({ online: true })
    await act(() => vi.advanceTimersByTimeAsync(CONTAINER_ACTION_POLL_INTERVAL_MS))
    await flushMicrotasks()

    expect(screen.getByRole('region', { name: 'Last container action' })).toHaveTextContent('APPLIED')
    expect(mocks.getContainerAction).toHaveBeenCalledOnce()
    expect(mocks.submitContainerAction).toHaveBeenCalledOnce()
  })

  it('ignores a late submission result after the container route changes', async () => {
    const pending = deferred<ContainerActionResponse>()
    let postSignal: AbortSignal | undefined
    mocks.submitContainerAction.mockImplementation((_id, _operation, _key, _csrf, signal) => {
      postSignal = signal
      return pending.promise
    })
    const view = renderSection()

    await openAndConfirm('Stop')
    await waitFor(() => expect(mocks.submitContainerAction).toHaveBeenCalledOnce())
    view.rerenderProps({ container: container({ id: SECOND_ID, name: 'other-service' }) })
    pending.resolve(actionResponse({
      containerId: CONTAINER_ID,
      status: 'APPLIED',
      reasonCode: 'APPLIED',
      completedAt: '2026-08-21T00:00:01Z',
    }))

    expect(postSignal?.aborted).toBe(true)
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Last container action' })).not.toBeInTheDocument())
    expect(screen.getByText(/latest snapshot allows Stop or Restart/)).toBeInTheDocument()
  })

  it.each([
    ['APPLIED', 'visible container state changes only after a fresh Agent snapshot'],
    ['NOOP', 'already in the requested state'],
    ['DENIED', 'current Backend or Agent control policy denied'],
    ['FAILED', 'definite execution failure'],
    ['EXPIRED', 'expired before it could be executed safely'],
    ['OUTCOME_UNKNOWN', 'Do not retry automatically'],
  ] as const)('renders fixed terminal copy for %s', async (status, message) => {
    mocks.submitContainerAction.mockResolvedValue(actionResponse({
      status,
      reasonCode: status === 'DENIED' ? 'UNKNOWN_PRIVATE_REASON' : null,
      completedAt: '2026-08-21T00:00:01Z',
    }))
    renderSection()

    await confirm('Stop')

    expect(await screen.findByText(new RegExp(message, 'i'))).toBeInTheDocument()
    expect(screen.queryByText('UNKNOWN_PRIVATE_REASON')).not.toBeInTheDocument()
  })

  it('keeps the mobile dialog and action controls within the responsive touch contract', () => {
    renderSection()

    expect(actionButton('Stop')).toHaveClass('min-h-11')
    fireEvent.click(actionButton('Stop'))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveClass('w-full')
    expect(dialog).toHaveClass('min-w-0')
    expect(dialog).toHaveClass('max-w-md')
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveClass('min-h-11')
    expect(screen.getByRole('button', { name: 'Confirm stop' })).toHaveClass('min-h-11')
    expect(dialog).toHaveTextContent(CONTAINER_ID)
  })
})

type Props = Parameters<typeof ContainerControlSection>[0]

function renderSection(overrides: Partial<Props> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, retryDelay: 0 },
      mutations: { retry: false },
    },
  })
  let props: Props = {
    container: container(),
    snapshotUpdatedAt: SNAPSHOT_S0,
    stale: false,
    online: true,
    visible: true,
    ...overrides,
  }
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ContainerControlSection {...props} />
    </QueryClientProvider>,
  )
  return {
    ...rendered,
    queryClient,
    rerenderProps(next: Partial<Props>) {
      props = { ...props, ...next }
      rendered.rerender(
        <QueryClientProvider client={queryClient}>
          <ContainerControlSection {...props} />
        </QueryClientProvider>,
      )
    },
  }
}

function container(overrides: Partial<ContainerView> = {}): ContainerView {
  return {
    id: CONTAINER_ID,
    name: 'safe-service',
    composeProject: 'safe-project',
    image: 'example/safe:sha',
    state: 'RUNNING',
    health: 'HEALTHY',
    status: 'Up',
    startedAt: '2026-08-21T00:00:00Z',
    restartCount: 0,
    cpuUsagePercent: 1,
    memoryUsageBytes: 1,
    memoryLimitBytes: 2,
    ports: [],
    managed: true,
    logsAllowed: false,
    ...overrides,
  }
}

function actionResponse(overrides: Partial<ContainerActionResponse> = {}): ContainerActionResponse {
  return {
    operationId: OPERATION_ID,
    containerId: CONTAINER_ID,
    operation: 'STOP',
    status: 'REQUESTED',
    reasonCode: null,
    requestedAt: '2026-08-21T00:00:00Z',
    completedAt: null,
    ...overrides,
  }
}

function actionButton(label: 'Start' | 'Stop' | 'Restart') {
  return screen.getByRole('button', { name: label })
}

async function confirm(label: 'Start' | 'Stop' | 'Restart') {
  await openAndConfirm(label)
  await waitFor(() => expect(mocks.submitContainerAction).toHaveBeenCalled())
}

async function openAndConfirm(label: 'Start' | 'Stop' | 'Restart') {
  fireEvent.click(actionButton(label))
  fireEvent.click(screen.getByRole('button', { name: `Confirm ${label.toLowerCase()}` }))
  await waitFor(() => expect(mocks.getSessionCsrfToken).toHaveBeenCalled())
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function flushMicrotasks() {
  await act(async () => {
    for (let index = 0; index < 8; index += 1) {
      await Promise.resolve()
    }
  })
}
