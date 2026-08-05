import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { ContainersPage } from './ContainersPage'

const mocks = vi.hoisted(() => ({
  getContainers: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getContainers: mocks.getContainers,
}))
vi.mock('../hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}))
vi.mock('../hooks/usePageVisible', () => ({
  usePageVisible: () => true,
}))

describe('ContainersPage', () => {
  beforeEach(() => {
    mocks.getContainers.mockReset()
  })

  it('shows a blocking error when the initial inventory request fails', async () => {
    mocks.getContainers.mockRejectedValueOnce(new Error('inventory unavailable'))

    renderPage()

    expect(await screen.findByText('Unable to load containers')).toBeInTheDocument()
    expect(screen.getByText('inventory unavailable')).toBeInTheDocument()
  })

  it('keeps cached inventory visible when a background refetch fails', async () => {
    mocks.getContainers
      .mockResolvedValueOnce(containerInventory())
      .mockRejectedValueOnce(new Error('inventory refetch failed'))
    const { queryClient } = renderPage()

    expect(await screen.findByText('homeops-api')).toBeInTheDocument()

    await queryClient.refetchQueries({ queryKey: ['containers'] })

    await waitFor(() => {
      expect(screen.getByText('This container snapshot is stale. Do not treat displayed states as current.'))
        .toBeInTheDocument()
    })
    expect(screen.getByText('homeops-api')).toBeInTheDocument()
    expect(screen.getByText(/Last collected/)).toBeInTheDocument()
    expect(screen.getAllByText('STALE')).toHaveLength(2)
    expect(screen.queryByText('Unable to load containers')).not.toBeInTheDocument()
  })

  it.each([401, 403])('blocks cached inventory after a background refetch returns status %s', async (status) => {
    const error = new ApiError(status, 'Tailscale identity is not authorized for HomeOps.')
    mocks.getContainers.mockResolvedValueOnce(containerInventory()).mockRejectedValueOnce(error)
    const { queryClient } = renderPage()

    expect(await screen.findByText('homeops-api')).toBeInTheDocument()

    await queryClient.refetchQueries({ queryKey: ['containers'] })

    expect(await screen.findByText('Unable to load containers')).toBeInTheDocument()
    expect(screen.getByText(error.message)).toBeInTheDocument()
    expect(screen.queryByText('homeops-api')).not.toBeInTheDocument()
    expect(screen.queryByText('This container snapshot is stale. Do not treat displayed states as current.'))
      .not.toBeInTheDocument()
  })

  it('marks inventory and container state stale when Agent snapshot is stale', async () => {
    mocks.getContainers.mockResolvedValue(containerInventory({ stale: true, agentStatus: 'STALE' }))

    renderPage()

    expect(await screen.findByText('This container snapshot is stale. Do not treat displayed states as current.'))
      .toBeInTheDocument()
    expect(screen.getByText(/Last collected/)).toBeInTheDocument()
    expect(screen.getAllByText('STALE')).toHaveLength(2)
    expect(screen.queryByText('HEALTHY')).not.toBeInTheDocument()
  })
})

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ContainersPage />
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

function containerInventory(overrides: { stale?: boolean; agentStatus?: 'CONNECTED' | 'STALE' | 'OFFLINE' } = {}) {
  return {
    agentStatus: overrides.agentStatus ?? 'CONNECTED',
    lastUpdatedAt: '2026-08-04T12:00:00Z',
    stale: overrides.stale ?? false,
    containers: [
      {
        id: 'abc123def456',
        name: 'homeops-api',
        composeProject: 'homeops',
        image: 'example/homeops-api:sha',
        state: 'RUNNING',
        health: 'HEALTHY',
        status: 'Up 10 minutes',
        startedAt: '2026-08-04T11:50:00Z',
        restartCount: 0,
        cpuUsagePercent: 1.25,
        memoryUsageBytes: 1024,
        memoryLimitBytes: 2048,
        ports: [],
        managed: false,
      },
    ],
  }
}
