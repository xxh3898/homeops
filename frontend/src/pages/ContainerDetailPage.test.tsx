import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
import { ApiConnectionError, ApiError } from '../api/client'
import type { ContainerDetail } from '../api/types'
import { ContainerDetailPage } from './ContainerDetailPage'

const FIRST_ID = '0123456789ab'
const SECOND_ID = 'abcdefabcdef'

const mocks = vi.hoisted(() => ({
  getContainerDetail: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getContainerDetail: mocks.getContainerDetail,
}))
vi.mock('../hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}))
vi.mock('../hooks/usePageVisible', () => ({
  usePageVisible: () => true,
}))

describe('ContainerDetailPage', () => {
  beforeEach(() => {
    mocks.getContainerDetail.mockReset()
  })

  it('renders freshness and structured allowlisted container detail', async () => {
    mocks.getContainerDetail.mockResolvedValue(containerDetail())

    renderPage()

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    expect(screen.getByText(/Last collected/)).toBeInTheDocument()
    expect(screen.getByText('RUNNING')).toBeInTheDocument()
    expect(screen.getByText('Reported running at collection time')).toBeInTheDocument()
    expect(screen.getByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('Health check passing')).toBeInTheDocument()
    expect(screen.getByText('13080 → 8080/tcp')).toBeInTheDocument()
    expect(screen.getByText('example/homeops-api:sha')).toHaveClass('break-words')
    expect(screen.getByText('Read-only inventory')).toBeInTheDocument()
    expect(screen.getByText('Container controls are not available in this read-only phase.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Containers' }))
      .toHaveAttribute('href', '/containers')
    expect(screen.getByRole('link', { name: 'Back to Containers' })).toHaveClass('min-h-11')
  })

  it('renders explicit fallbacks for optional container values', async () => {
    mocks.getContainerDetail.mockResolvedValue(containerDetail({
      container: {
        ...containerDetail().container,
        composeProject: null,
        health: 'NONE',
        status: null,
        startedAt: null,
        cpuUsagePercent: null,
        memoryUsageBytes: null,
        memoryLimitBytes: null,
        ports: [],
        managed: true,
      },
    }))

    renderPage()

    expect(await screen.findByText('Standalone')).toBeInTheDocument()
    expect(screen.getByText('No health check')).toBeInTheDocument()
    expect(screen.getAllByText('Not reported')).toHaveLength(2)
    expect(screen.getAllByText('Unavailable')).toHaveLength(2)
    expect(screen.getByText('No reported ports')).toBeInTheDocument()
    expect(screen.getByText('Managed inventory label present')).toBeInTheDocument()
  })

  it('marks the reported container state stale when the Agent snapshot is stale', async () => {
    mocks.getContainerDetail.mockResolvedValue(containerDetail({ stale: true, agentStatus: 'STALE' }))

    renderPage()

    expect(await screen.findByText('This container state comes from a stale Agent snapshot. Do not treat it as current.'))
      .toBeInTheDocument()
    expect(screen.getAllByText('STALE')).toHaveLength(1)
    expect(screen.getByText('RUNNING')).toBeInTheDocument()
  })

  it('rejects an invalid route locally without calling the API', () => {
    renderPage('/containers/not-valid')

    expect(screen.getByText('Invalid container link')).toBeInTheDocument()
    expect(mocks.getContainerDetail).not.toHaveBeenCalled()
  })

  it.each([
    [400, 'Invalid container link'],
    [404, 'Container not reported'],
    [409, 'Container identifier is ambiguous'],
  ])('shows terminal state %s without a retry action', async (status, title) => {
    mocks.getContainerDetail.mockRejectedValue(new ApiError(status, 'terminal'))

    renderPage()

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Retry/i })).not.toBeInTheDocument()
  })

  it.each([
    [400, 'Invalid container link'],
    [404, 'Container no longer reported'],
    [409, 'Container identifier is ambiguous'],
  ])('hides cached detail when background refetch returns terminal status %s', async (status, title) => {
    mocks.getContainerDetail
      .mockResolvedValueOnce(containerDetail())
      .mockRejectedValue(new ApiError(status, 'terminal'))
    const { queryClient } = renderPage()

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    await queryClient.refetchQueries({ queryKey: ['container', FIRST_ID] })

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'homeops-api' })).not.toBeInTheDocument()
    expect(screen.queryByText('example/homeops-api:sha')).not.toBeInTheDocument()
  })

  it('keeps cached detail stale when background inventory request is unavailable', async () => {
    mocks.getContainerDetail
      .mockResolvedValueOnce(containerDetail())
      .mockRejectedValue(new ApiError(503, 'inventory unavailable'))
    const { queryClient } = renderPage()

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    await queryClient.refetchQueries({ queryKey: ['container', FIRST_ID] })

    expect(await screen.findByText(
      'Container inventory is temporarily unavailable. Showing the last successfully reported snapshot.',
    )).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    expect(mocks.getContainerDetail).toHaveBeenCalledTimes(3)
  })

  it('keeps cached detail stale after a bounded connection failure', async () => {
    mocks.getContainerDetail
      .mockResolvedValueOnce(containerDetail())
      .mockRejectedValue(new ApiConnectionError())
    const { queryClient } = renderPage()

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    await queryClient.refetchQueries({ queryKey: ['container', FIRST_ID] })

    expect(await screen.findByText(/Showing the last successfully reported container snapshot/))
      .toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
  })

  it.each([401, 403])('hides cached detail after authorization status %s', async (status) => {
    mocks.getContainerDetail
      .mockResolvedValueOnce(containerDetail())
      .mockRejectedValue(new ApiError(status, 'access denied'))
    const { queryClient } = renderPage()

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    await queryClient.refetchQueries({ queryKey: ['container', FIRST_ID] })

    expect(await screen.findByText('Unable to load container detail')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'homeops-api' })).not.toBeInTheDocument()
  })

  it('uses the route identifier as the query key and does not retain the previous container', async () => {
    mocks.getContainerDetail.mockImplementation((id: string) => Promise.resolve(
      id === FIRST_ID
        ? containerDetail()
        : containerDetail({ container: { ...containerDetail().container, id, name: 'homeops-web' } }),
    ))

    renderPage(`/containers/${FIRST_ID}`, true)

    expect(await screen.findByRole('heading', { name: 'homeops-api' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Open second container' }))

    expect(await screen.findByRole('heading', { name: 'homeops-web' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'homeops-api' })).not.toBeInTheDocument()
    expect(mocks.getContainerDetail).toHaveBeenLastCalledWith(SECOND_ID, expect.any(AbortSignal))
  })

  it('renders a retryable initial connection error without stale content', async () => {
    mocks.getContainerDetail.mockRejectedValue(new ApiConnectionError())

    renderPage()

    expect(await screen.findByText('Unable to load container detail')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Retry/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'homeops-api' })).not.toBeInTheDocument()
  })
})

function renderPage(path = `/containers/${FIRST_ID}`, includeSwitch = false) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retryDelay: 0 },
    },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        {includeSwitch && <Link to={`/containers/${SECOND_ID}`}>Open second container</Link>}
        <Routes>
          <Route path="/containers/:id" element={<ContainerDetailPage />} />
          <Route path="/containers" element={<p>Container list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

function containerDetail(overrides: Partial<ContainerDetail> = {}): ContainerDetail {
  const base: ContainerDetail = {
    agentStatus: 'CONNECTED',
    lastUpdatedAt: '2026-08-04T12:00:00Z',
    stale: false,
    container: {
      id: FIRST_ID,
      name: 'homeops-api',
      composeProject: 'homeops',
      image: 'example/homeops-api:sha',
      state: 'RUNNING',
      health: 'HEALTHY',
      status: 'Up 10 minutes (healthy)',
      startedAt: '2026-08-04T11:50:00Z',
      restartCount: 0,
      cpuUsagePercent: 1.25,
      memoryUsageBytes: 1024,
      memoryLimitBytes: 2048,
      ports: [{ privatePort: 8080, publicPort: 13080, type: 'TCP' }],
      managed: false,
    },
  }
  return { ...base, ...overrides }
}
