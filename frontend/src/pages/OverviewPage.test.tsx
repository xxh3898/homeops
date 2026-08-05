import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { OverviewPage } from './OverviewPage'

const mocks = vi.hoisted(() => ({
  getSystemSummary: vi.fn(),
}))

vi.mock('../api/client', () => ({
  getSystemSummary: mocks.getSystemSummary,
}))
vi.mock('../hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}))
vi.mock('../hooks/usePageVisible', () => ({
  usePageVisible: () => true,
}))

describe('OverviewPage', () => {
  beforeEach(() => {
    mocks.getSystemSummary.mockReset()
  })

  it('shows a blocking error when the initial summary request fails', async () => {
    mocks.getSystemSummary.mockRejectedValueOnce(new Error('summary unavailable'))

    renderPage()

    expect(await screen.findByText('Unable to load HomeOps')).toBeInTheDocument()
    expect(screen.getByText('summary unavailable')).toBeInTheDocument()
  })

  it('keeps cached summary visible when a background refetch fails', async () => {
    mocks.getSystemSummary
      .mockResolvedValueOnce(systemSummary())
      .mockRejectedValueOnce(new Error('summary refetch failed'))
    const { queryClient } = renderPage()

    expect(await screen.findByText('System overview')).toBeInTheDocument()
    expect(screen.getByText('12.5%')).toBeInTheDocument()

    await queryClient.refetchQueries({ queryKey: ['system-summary'] })

    await waitFor(() => {
      expect(screen.getByText('This snapshot is stale. Do not treat the displayed state as current.'))
        .toBeInTheDocument()
    })
    expect(screen.getByText('12.5%')).toBeInTheDocument()
    expect(screen.getByText(/Last collected/)).toBeInTheDocument()
    expect(screen.getByText('STALE')).toBeInTheDocument()
    expect(screen.queryByText('Unable to load HomeOps')).not.toBeInTheDocument()
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
      <OverviewPage />
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

function systemSummary() {
  return {
    agentStatus: 'CONNECTED' as const,
    lastUpdatedAt: '2026-08-04T12:00:00Z',
    stale: false,
    host: {
      cpuUsagePercent: 12.5,
      memoryTotalBytes: 16_000,
      memoryUsedBytes: 8_000,
      diskTotalBytes: 1_000_000,
      diskUsedBytes: 250_000,
      uptimeSeconds: 3_600,
    },
    docker: {
      total: 4,
      running: 4,
      notRunning: 0,
      unhealthy: 0,
    },
  }
}
