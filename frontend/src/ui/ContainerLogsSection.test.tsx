import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiConnectionError, ApiError } from '../api/client'
import type { ContainerLogResponse } from '../api/types'
import { ContainerLogsSection } from './ContainerLogsSection'

const CONTAINER_ID = '0123456789ab'
const SECOND_ID = 'abcdefabcdef'

const mocks = vi.hoisted(() => ({
  getContainerLogs: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getContainerLogs: mocks.getContainerLogs,
}))

describe('ContainerLogsSection', () => {
  beforeEach(() => {
    mocks.getContainerLogs.mockReset()
  })

  it('fails closed with distinct capability, freshness, and opt-in messages', () => {
    const view = renderSection({ supportsContainerLogs: false })

    expect(screen.getByText(/connected Agent does not support/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load logs' })).not.toBeInTheDocument()

    view.rerenderProps({ supportsContainerLogs: true, stale: true })
    expect(screen.getByText(/snapshot freshness cannot be confirmed/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load logs' })).not.toBeInTheDocument()

    view.rerenderProps({ stale: false, logsAllowed: false })
    expect(screen.getByText('Logs are not enabled for this container.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load logs' })).not.toBeInTheDocument()
  })

  it('loads only after explicit action and does not refetch on focus or stable detail rerender', async () => {
    mocks.getContainerLogs.mockResolvedValue(logResponse())
    const view = renderSection()

    expect(mocks.getContainerLogs).not.toHaveBeenCalled()
    window.dispatchEvent(new Event('focus'))
    view.rerenderProps({})
    expect(mocks.getContainerLogs).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    expect(await screen.findByText('safe line')).toBeInTheDocument()
    expect(mocks.getContainerLogs).toHaveBeenCalledOnce()
    expect(mocks.getContainerLogs).toHaveBeenCalledWith(
      CONTAINER_ID,
      100,
      expect.any(AbortSignal),
    )
    view.rerenderProps({})
    window.dispatchEvent(new Event('focus'))
    expect(mocks.getContainerLogs).toHaveBeenCalledOnce()
    expect(view.queryClient.getQueryCache().findAll()).toHaveLength(0)
  })

  it('uses bounded tail selectors and clears prior payload before a new selection', async () => {
    mocks.getContainerLogs.mockImplementation((_id: string, tail: number) =>
      Promise.resolve(logResponse({
        requestedTail: tail as 50 | 100 | 200,
        lines: [{ timestamp: null, stream: 'STDOUT', message: `tail-${tail}` }],
      })))
    renderSection()

    expect(screen.getByRole('button', { name: '100' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '50' }))
    expect(screen.queryByText('safe line')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByText('tail-50')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '200' }))
    expect(screen.queryByText('tail-50')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByText('tail-200')).toBeInTheDocument()
    expect(mocks.getContainerLogs).toHaveBeenNthCalledWith(
      1, CONTAINER_ID, 50, expect.any(AbortSignal),
    )
    expect(mocks.getContainerLogs).toHaveBeenNthCalledWith(
      2, CONTAINER_ID, 200, expect.any(AbortSignal),
    )
  })

  it('uses Refresh only after success and sends exactly one request per click', async () => {
    mocks.getContainerLogs.mockResolvedValue(logResponse())
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByRole('button', { name: 'Refresh logs' })).toBeInTheDocument()
    expect(mocks.getContainerLogs).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh logs' }))
    await waitFor(() => expect(mocks.getContainerLogs).toHaveBeenCalledTimes(2))
  })

  it('prevents duplicate load clicks while a request is in flight', async () => {
    const pending = deferred<ContainerLogResponse>()
    mocks.getContainerLogs.mockReturnValue(pending.promise)
    renderSection()

    const load = screen.getByRole('button', { name: 'Load logs' })
    fireEvent.click(load)
    expect(await screen.findByRole('button', { name: 'Loading logs…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Loading logs…' }))

    expect(mocks.getContainerLogs).toHaveBeenCalledOnce()
    pending.resolve(logResponse())
    expect(await screen.findByText('safe line')).toBeInTheDocument()
  })

  it('renders empty, stream labels, redaction, truncation, and residual risk', async () => {
    mocks.getContainerLogs
      .mockResolvedValueOnce(logResponse({ lines: [] }))
      .mockResolvedValueOnce(logResponse({
        redactionApplied: true,
        truncated: true,
        lines: [
          { timestamp: '2026-08-18T00:00:00Z', stream: 'STDOUT', message: 'out' },
          { timestamp: null, stream: 'STDERR', message: 'err' },
          { timestamp: null, stream: 'COMBINED', message: 'combined' },
        ],
      }))
    renderSection()

    expect(screen.getByText(/logs may still contain sensitive data/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByText('No recent log lines.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh logs' }))
    expect(await screen.findByText('Sensitive-looking values were redacted.')).toBeInTheDocument()
    expect(screen.getByText('Output was truncated to stay within safety limits.')).toBeInTheDocument()
    expect(screen.getByText('STDOUT')).toBeInTheDocument()
    expect(screen.getByText('STDERR')).toBeInTheDocument()
    expect(screen.getByText('COMBINED')).toBeInTheDocument()
    expect(screen.getAllByText('Timestamp not reported')).toHaveLength(2)
  })

  it.each([
    [400, 'The log request was invalid.'],
    [404, 'This container is no longer reported.'],
    [409, 'The container identifier conflicts'],
    [422, 'Logs are not enabled'],
    [429, 'Container log requests are busy'],
    [503, 'Container log retrieval is temporarily unavailable.'],
    [504, 'The container log request timed out.'],
  ])('shows an actionable bounded error for status %s', async (status, message) => {
    mocks.getContainerLogs.mockRejectedValue(new ApiError(status, 'safe API error'))
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    expect(await screen.findByText(new RegExp(message))).toBeInTheDocument()
  })

  it('shows the existing safe connection error without retrying', async () => {
    mocks.getContainerLogs.mockRejectedValue(new ApiConnectionError())
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument()
    expect(mocks.getContainerLogs).toHaveBeenCalledOnce()
  })

  it('renders malicious markup as wrapped plain text', async () => {
    const message = '<script>globalThis.compromised = true</script>'
    mocks.getContainerLogs.mockResolvedValue(logResponse({
      lines: [{ timestamp: null, stream: 'STDOUT', message }],
    }))
    const view = renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    const renderedMessage = await screen.findByText(message)
    expect(renderedMessage).toHaveClass('whitespace-pre-wrap')
    expect(renderedMessage).toHaveClass('[overflow-wrap:anywhere]')
    expect(view.container.querySelector('script')).toBeNull()
  })

  it('clears successful payload immediately when eligibility is revoked', async () => {
    mocks.getContainerLogs.mockResolvedValue(logResponse())
    const view = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByText('safe line')).toBeInTheDocument()

    view.rerenderProps({ logsAllowed: false })

    expect(screen.queryByText('safe line')).not.toBeInTheDocument()
    expect(screen.getByText('Logs are not enabled for this container.')).toBeInTheDocument()
  })

  it.each([
    { supportsContainerLogs: false },
    { stale: true },
  ])('clears successful payload on capability or freshness loss', async (override) => {
    mocks.getContainerLogs.mockResolvedValue(logResponse())
    const view = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))
    expect(await screen.findByText('safe line')).toBeInTheDocument()

    view.rerenderProps(override)

    expect(screen.queryByText('safe line')).not.toBeInTheDocument()
  })

  it('ignores a late success after logsAllowed is revoked during the request', async () => {
    const pending = deferred<ContainerLogResponse>()
    mocks.getContainerLogs.mockReturnValue(pending.promise)
    const view = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    view.rerenderProps({ logsAllowed: false })
    pending.resolve(logResponse({ lines: [{ timestamp: null, stream: 'STDOUT', message: 'late' }] }))

    await waitFor(() => expect(screen.queryByText('late')).not.toBeInTheDocument())
    expect(screen.getByText('Logs are not enabled for this container.')).toBeInTheDocument()
  })

  it('ignores an old container result after the route identifier changes', async () => {
    const pending = deferred<ContainerLogResponse>()
    mocks.getContainerLogs.mockReturnValue(pending.promise)
    const view = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    view.rerenderProps({ containerId: SECOND_ID })
    pending.resolve(logResponse({ lines: [{ timestamp: null, stream: 'STDOUT', message: 'old container' }] }))

    await waitFor(() => expect(screen.queryByText('old container')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '100' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('aborts and ignores a tail result after selection changes', async () => {
    const pending = deferred<ContainerLogResponse>()
    let requestSignal: AbortSignal | undefined
    mocks.getContainerLogs.mockImplementation((_id, _tail, signal) => {
      requestSignal = signal
      return pending.promise
    })
    renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    fireEvent.click(screen.getByRole('button', { name: '50' }))
    pending.resolve(logResponse({ lines: [{ timestamp: null, stream: 'STDOUT', message: 'tail 100 late' }] }))

    expect(requestSignal?.aborted).toBe(true)
    await waitFor(() => expect(screen.queryByText('tail 100 late')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '50' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('aborts active work and removes cache on unmount', async () => {
    const pending = deferred<ContainerLogResponse>()
    let requestSignal: AbortSignal | undefined
    mocks.getContainerLogs.mockImplementation((_id, _tail, signal) => {
      requestSignal = signal
      return pending.promise
    })
    const view = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    view.unmount()

    expect(requestSignal?.aborted).toBe(true)
    expect(view.queryClient.getQueryCache().findAll()).toHaveLength(0)
  })

  it('preserves global authorization handling for imperative log fetches', async () => {
    const onAuthorizationError = vi.fn()
    const queryClient = new QueryClient({
      queryCache: new QueryCache({
        onError: (error) => {
          if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
            onAuthorizationError(error)
          }
        },
      }),
    })
    mocks.getContainerLogs.mockRejectedValue(new ApiError(401, 'access denied'))
    renderSection({}, queryClient)

    fireEvent.click(screen.getByRole('button', { name: 'Load logs' }))

    await waitFor(() => expect(onAuthorizationError).toHaveBeenCalledOnce())
  })
})

type Props = Parameters<typeof ContainerLogsSection>[0]

function renderSection(overrides: Partial<Props> = {}, providedClient?: QueryClient) {
  const queryClient = providedClient ?? new QueryClient({
    defaultOptions: { queries: { retryDelay: 0 } },
  })
  let props: Props = {
    containerId: CONTAINER_ID,
    supportsContainerLogs: true,
    logsAllowed: true,
    stale: false,
    ...overrides,
  }
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ContainerLogsSection {...props} />
    </QueryClientProvider>,
  )
  return {
    ...rendered,
    queryClient,
    rerenderProps(next: Partial<Props>) {
      props = { ...props, ...next }
      rendered.rerender(
        <QueryClientProvider client={queryClient}>
          <ContainerLogsSection {...props} />
        </QueryClientProvider>,
      )
    },
  }
}

function logResponse(overrides: Partial<ContainerLogResponse> = {}): ContainerLogResponse {
  return {
    containerId: CONTAINER_ID,
    requestedTail: 100,
    collectedAt: '2026-08-18T00:00:00Z',
    truncated: false,
    redactionApplied: false,
    lines: [{ timestamp: '2026-08-18T00:00:00Z', stream: 'STDOUT', message: 'safe line' }],
    ...overrides,
  }
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
