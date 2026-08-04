import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ContainersPage } from './ContainersPage'

const mocks = vi.hoisted(() => ({
  getContainers: vi.fn(),
}))

vi.mock('../api/client', () => ({
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

  it('marks inventory and container state stale when Agent snapshot is stale', async () => {
    mocks.getContainers.mockResolvedValue({
      agentStatus: 'STALE',
      lastUpdatedAt: '2026-08-04T12:00:00Z',
      stale: true,
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
    })

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
  return render(
    <QueryClientProvider client={queryClient}>
      <ContainersPage />
    </QueryClientProvider>,
  )
}
