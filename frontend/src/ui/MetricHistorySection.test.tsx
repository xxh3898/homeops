import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MetricHistory, MetricHistoryPeriod } from '../api/types'
import { MetricHistorySection } from './MetricHistorySection'

const mocks = vi.hoisted(() => ({
  getMetricHistory: vi.fn(),
  online: true,
  visible: true,
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getMetricHistory: mocks.getMetricHistory,
}))
vi.mock('../hooks/useOnlineStatus', () => ({ useOnlineStatus: () => mocks.online }))
vi.mock('../hooks/usePageVisible', () => ({ usePageVisible: () => mocks.visible }))

describe('MetricHistorySection', () => {
  beforeEach(() => {
    mocks.online = true
    mocks.visible = true
    mocks.getMetricHistory.mockReset().mockImplementation((period: MetricHistoryPeriod) =>
      Promise.resolve(history(period)))
  })

  it('renders accessible selectors and completed history summary', async () => {
    renderSection()

    expect(await screen.findByRole('img', { name: 'CPU history for the last 6h' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'CPU' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '6h' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Latest completed')).toBeInTheDocument()
    expect(screen.getByText(/This is not the live snapshot/)).toBeInTheDocument()
  })

  it('shows an isolated loading state while the first history request is pending', () => {
    mocks.getMetricHistory.mockReturnValue(new Promise(() => {}))

    const rendered = renderSection()

    expect(screen.getByLabelText('Loading metric history')).toBeInTheDocument()
    rendered.unmount()
  })

  it('uses the selected period in the query key and does not relabel prior data', async () => {
    renderSection()
    await screen.findByRole('img', { name: 'CPU history for the last 6h' })

    fireEvent.click(screen.getByRole('button', { name: '24h' }))

    expect(await screen.findByRole('img', { name: 'CPU history for the last 24h' })).toBeInTheDocument()
    expect(mocks.getMetricHistory).toHaveBeenCalledWith('24h', expect.any(AbortSignal))
  })

  it('shows partial guidance and keeps missing buckets out of the text summary', async () => {
    const partial = history('6h')
    partial.points = [partial.points[0]]
    mocks.getMetricHistory.mockResolvedValue(partial)
    renderSection()

    expect(await screen.findByText('Showing available retained data. Missing buckets are left as gaps.'))
      .toBeInTheDocument()
    expect(screen.getByText('Latest completed')).toBeInTheDocument()
  })

  it('shows only latest completed for Disk without a fake peak', async () => {
    renderSection()
    await screen.findByRole('img', { name: 'CPU history for the last 6h' })

    fireEvent.click(screen.getByRole('button', { name: 'Disk' }))

    const summary = screen.getByLabelText('Metric history summary')
    expect(within(summary).getByText('Latest completed')).toBeInTheDocument()
    expect(within(summary).queryByText('Average')).not.toBeInTheDocument()
    expect(within(summary).queryByText('Peak')).not.toBeInTheDocument()
  })

  it('shows unavailable when persisted totals cannot produce a metric value', async () => {
    const unavailable = history('6h')
    unavailable.points = unavailable.points.map((point) => ({ ...point, memoryTotalBytes: 0 }))
    mocks.getMetricHistory.mockResolvedValue(unavailable)
    renderSection()
    await screen.findByRole('img', { name: 'CPU history for the last 6h' })

    fireEvent.click(screen.getByRole('button', { name: 'Memory' }))

    expect(await screen.findByText('Metric unavailable')).toBeInTheDocument()
  })

  it('keeps cached data and warns when a background refresh fails', async () => {
    mocks.getMetricHistory
      .mockResolvedValueOnce(history('6h'))
      .mockRejectedValueOnce(new Error('history refresh failed'))
    const { queryClient } = renderSection()
    await screen.findByRole('img', { name: 'CPU history for the last 6h' })

    await queryClient.refetchQueries({ queryKey: ['metric-history', '6h'] })

    await waitFor(() => {
      expect(screen.getByText('Metric history could not be refreshed. The last successful result is shown.'))
        .toBeInTheDocument()
    })
    expect(screen.getByRole('img', { name: 'CPU history for the last 6h' })).toBeInTheDocument()
  })

  it('distinguishes initial error and empty states', async () => {
    mocks.getMetricHistory.mockRejectedValueOnce(new Error('history unavailable'))
    const first = renderSection()
    expect(await screen.findByText('Unable to load metric history')).toBeInTheDocument()
    first.unmount()

    const empty = history('6h')
    empty.points = []
    mocks.getMetricHistory.mockReset().mockResolvedValue(empty)
    renderSection()
    expect(await screen.findByText('No metric history yet')).toBeInTheDocument()
  })
})

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <MetricHistorySection />
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

function history(period: MetricHistoryPeriod): MetricHistory {
  const bucketSeconds = period === '1h' ? 60 : period === '6h' ? 300 : period === '24h' ? 900 : 3_600
  const pointCount = period === '1h' ? 60 : period === '6h' ? 72 : period === '24h' ? 96 : 168
  const to = Date.parse('2026-08-17T12:00:00Z')
  const from = to - pointCount * bucketSeconds * 1_000
  return {
    period,
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    bucketSeconds,
    points: Array.from({ length: pointCount }, (_, index) => ({
      bucketStart: new Date(from + index * bucketSeconds * 1_000).toISOString(),
      sampleCount: 12,
      cpuUsageAverage: 10 + index / 10,
      cpuUsagePeak: 20 + index / 10,
      memoryTotalBytes: 16_000,
      memoryUsedAverageBytes: 8_000,
      memoryUsedPeakBytes: 9_000,
      diskTotalBytes: 1_000_000,
      diskUsedBytes: 250_000,
    })),
  }
}
