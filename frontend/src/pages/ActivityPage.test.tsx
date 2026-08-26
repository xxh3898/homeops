import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { ActivityPage } from './ActivityPage'

const mocks = vi.hoisted(() => ({ getActivity: vi.fn(), online: { value: true } }))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getActivity: mocks.getActivity,
}))
vi.mock('../hooks/useOnlineStatus', () => ({ useOnlineStatus: () => mocks.online.value }))
vi.mock('../hooks/usePageVisible', () => ({ usePageVisible: () => true }))

describe('ActivityPage', () => {
  beforeEach(() => {
    mocks.getActivity.mockReset()
    mocks.online.value = true
  })

  it('shows all operational event kinds and loads the next page', async () => {
    mocks.getActivity
      .mockResolvedValueOnce(page('next', [
        event('DEPLOYMENT', 'Cubing Hub deployment'),
        event('INCIDENT', 'HomeOps is unavailable', 'OPEN'),
        event('AGENT', 'Agent connected', 'CONNECTED'),
      ]))
      .mockResolvedValueOnce(page(null, [
        event('BACKUP', 'Guess Pokémon backup'),
        event('CONTAINER_ACTION', 'Container restart', 'APPLIED', '0123456789ab'),
      ]))
    renderPage()

    expect(await screen.findByText('Cubing Hub deployment')).toBeInTheDocument()
    expect(screen.getByText('HomeOps is unavailable')).toBeInTheDocument()
    expect(screen.getByText('Agent connected')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load older activity' }))

    expect(await screen.findByText('Guess Pokémon backup')).toBeInTheDocument()
    expect(screen.getByText('Container restart')).toBeInTheDocument()
    expect(mocks.getActivity).toHaveBeenLastCalledWith('ALL', 'next', expect.any(AbortSignal))
  })

  it('uses an accessible mobile-safe single event type filter', async () => {
    mocks.getActivity.mockResolvedValueOnce(page(null, []))
    renderPage()

    const filter = await screen.findByRole('combobox', { name: 'Event type' })
    expect(filter).toHaveValue('ALL')
    expect(filter).toHaveClass('min-h-11', 'w-full')
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'All activity',
      'Deployments',
      'Backups',
      'Incidents',
      'Agent',
      'Container actions',
    ])
    expect(mocks.getActivity).toHaveBeenCalledWith('ALL', undefined, expect.any(AbortSignal))
  })

  it('starts a new first page and does not mix old results when the filter changes', async () => {
    mocks.getActivity
      .mockResolvedValueOnce(page('all-next', [event('AGENT', 'Old all-scope event')]))
      .mockResolvedValueOnce(page('deployment-next', [event('DEPLOYMENT', 'Filtered deployment')]))
      .mockResolvedValueOnce(page(null, [event('DEPLOYMENT', 'Older filtered deployment', 'RUNNING')]))
    renderPage()

    expect(await screen.findByText('Old all-scope event')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: 'Event type' }), {
      target: { value: 'DEPLOYMENT' },
    })

    expect(await screen.findByText('Filtered deployment')).toBeInTheDocument()
    expect(screen.queryByText('Old all-scope event')).not.toBeInTheDocument()
    expect(mocks.getActivity).toHaveBeenNthCalledWith(2, 'DEPLOYMENT', undefined, expect.any(AbortSignal))

    fireEvent.click(screen.getByRole('button', { name: 'Load older activity' }))
    expect(await screen.findByText('Older filtered deployment')).toBeInTheDocument()
    expect(mocks.getActivity).toHaveBeenLastCalledWith(
      'DEPLOYMENT', 'deployment-next', expect.any(AbortSignal),
    )
  })

  it('discards an invalid continuation chain and refreshes the selected filter from its first page', async () => {
    mocks.getActivity
      .mockResolvedValueOnce(page(null, []))
      .mockResolvedValueOnce(page('expired-cursor', [event('DEPLOYMENT', 'Old deployment chain')]))
      .mockRejectedValueOnce(new ApiError(400, 'invalid cursor'))
      .mockResolvedValueOnce(page(null, [event('DEPLOYMENT', 'Fresh deployment chain')]))
    renderPage()

    await screen.findByText('No activity recorded yet')
    fireEvent.change(screen.getByRole('combobox', { name: 'Event type' }), {
      target: { value: 'DEPLOYMENT' },
    })
    expect(await screen.findByText('Old deployment chain')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Load older activity' }))

    expect(await screen.findByText('Activity timeline changed')).toBeInTheDocument()
    expect(screen.queryByText('Old deployment chain')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load older activity' })).not.toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Event type' })).toHaveValue('DEPLOYMENT')
    const refresh = screen.getByRole('button', { name: 'Refresh activity timeline' })
    expect(refresh).toHaveClass('min-h-11', 'w-full')
    expect(mocks.getActivity).toHaveBeenCalledTimes(3)

    fireEvent.click(refresh)

    expect(await screen.findByText('Fresh deployment chain')).toBeInTheDocument()
    expect(screen.queryByText('Old deployment chain')).not.toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Event type' })).toHaveValue('DEPLOYMENT')
    expect(mocks.getActivity).toHaveBeenNthCalledWith(
      4, 'DEPLOYMENT', undefined, expect.any(AbortSignal),
    )
  })

  it('renders a mobile-safe container action card with bounded public context', async () => {
    mocks.getActivity.mockResolvedValueOnce(page(null, [
      event('CONTAINER_ACTION', 'Container restart', 'OUTCOME_UNKNOWN', '0123456789ab', 'CRITICAL'),
    ]))
    const { container } = renderPage()

    expect(await screen.findByText('Container restart')).toHaveClass('break-words')
    expect(screen.getByText('OUTCOME_UNKNOWN')).toHaveClass('break-words')
    expect(screen.getByText('0123456789ab')).toBeInTheDocument()
    expect(screen.getByText('0123456789ab').parentElement).toHaveClass('min-w-0')
    expect(container.querySelector('svg.lucide-boxes')).toBeInTheDocument()
  })

  it('renders open and resolved entries for the same incident without duplicate keys', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    mocks.getActivity.mockResolvedValueOnce(page(null, [
      event('INCIDENT', 'HomeOps is unavailable', 'OPEN'),
      event('INCIDENT', 'HomeOps is unavailable', 'RESOLVED'),
    ]))
    renderPage()

    expect(await screen.findAllByText('HomeOps is unavailable')).toHaveLength(2)
    expect(consoleError).not.toHaveBeenCalledWith(expect.stringContaining('same key'))
    consoleError.mockRestore()
  })

  it('shows an empty state when no events exist', async () => {
    mocks.getActivity.mockResolvedValueOnce(page(null, []))
    renderPage()
    expect(await screen.findByText('No activity recorded yet')).toBeInTheDocument()
    expect(screen.getByText(/container actions will appear here/)).toBeInTheDocument()
  })

  it('shows a filter-specific empty state', async () => {
    mocks.getActivity
      .mockResolvedValueOnce(page(null, []))
      .mockResolvedValueOnce(page(null, []))
    renderPage()

    await screen.findByText('No activity recorded yet')
    fireEvent.change(screen.getByRole('combobox', { name: 'Event type' }), {
      target: { value: 'INCIDENT' },
    })

    expect(await screen.findByText('No incident activity recorded yet')).toBeInTheDocument()
    expect(screen.getByText('Incident openings and recoveries will appear here.')).toBeInTheDocument()
  })

  it('keeps cached activity visible and marks it stale after refresh failure', async () => {
    mocks.getActivity.mockResolvedValueOnce(page(null, [event('AGENT', 'Agent connected')]))
      .mockRejectedValue(new Error('refresh failed'))
    const { queryClient } = renderPage()
    expect(await screen.findByText('Agent connected')).toBeInTheDocument()

    await queryClient.refetchQueries({ queryKey: ['activity', 'ALL'] })

    await waitFor(() => expect(screen.getByText(/timeline below may be out of date/)).toBeInTheDocument())
    expect(screen.getByText('Agent connected')).toBeInTheDocument()
  })

  it('keeps cached read-only activity visible while offline', async () => {
    mocks.online.value = false
    mocks.getActivity.mockResolvedValueOnce(page(null, [event('BACKUP', 'Offline cached backup')]))
    renderPage()

    expect(await screen.findByText('Offline cached backup')).toBeInTheDocument()
    expect(screen.getByText(/timeline below may be out of date/)).toBeInTheDocument()
  })

  it('shows a blocking error when the initial request fails', async () => {
    mocks.getActivity.mockRejectedValue(new Error('unavailable'))
    renderPage()
    expect(await screen.findByText('Unable to load activity')).toBeInTheDocument()
  })
})

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const rendered = render(<QueryClientProvider client={queryClient}><ActivityPage /></QueryClientProvider>)
  return { ...rendered, queryClient }
}

function page(nextCursor: string | null, items: ReturnType<typeof event>[]) {
  return { items, nextCursor, generatedAt: '2026-08-06T12:00:00Z' }
}

function event(
  type: 'DEPLOYMENT' | 'BACKUP' | 'INCIDENT' | 'AGENT' | 'CONTAINER_ACTION',
  title: string,
  status = 'SUCCESS',
  context = 'homeops',
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'RECOVERY' = 'INFO',
) {
  return { id: `${type}-1`, type, title, status, severity,
    occurredAt: '2026-08-06T12:00:00Z', context }
}
