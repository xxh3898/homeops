import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
      .mockResolvedValueOnce(page('next', [event('DEPLOYMENT', 'Cubing Hub deployment')]))
      .mockResolvedValueOnce(page(null, [event('BACKUP', 'Guess Pokémon backup')]))
    renderPage()

    expect(await screen.findByText('Cubing Hub deployment')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load older activity' }))

    expect(await screen.findByText('Guess Pokémon backup')).toBeInTheDocument()
    expect(mocks.getActivity).toHaveBeenLastCalledWith('next', expect.any(AbortSignal))
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
  })

  it('keeps cached activity visible and marks it stale after refresh failure', async () => {
    mocks.getActivity.mockResolvedValueOnce(page(null, [event('AGENT', 'Agent connected')]))
      .mockRejectedValueOnce(new Error('refresh failed'))
    const { queryClient } = renderPage()
    expect(await screen.findByText('Agent connected')).toBeInTheDocument()

    await queryClient.refetchQueries({ queryKey: ['activity'] })

    await waitFor(() => expect(screen.getByText(/timeline below may be out of date/)).toBeInTheDocument())
    expect(screen.getByText('Agent connected')).toBeInTheDocument()
  })

  it('shows a blocking error when the initial request fails', async () => {
    mocks.getActivity.mockRejectedValueOnce(new Error('unavailable'))
    renderPage()
    expect(await screen.findByText('Unable to load activity')).toBeInTheDocument()
  })
})

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rendered = render(<QueryClientProvider client={queryClient}><ActivityPage /></QueryClientProvider>)
  return { ...rendered, queryClient }
}

function page(nextCursor: string | null, items: ReturnType<typeof event>[]) {
  return { items, nextCursor, generatedAt: '2026-08-06T12:00:00Z' }
}

function event(type: 'DEPLOYMENT' | 'BACKUP' | 'INCIDENT' | 'AGENT', title: string, status = 'SUCCESS') {
  return { id: `${type}-1`, type, title, status, severity: 'INFO' as const,
    occurredAt: '2026-08-06T12:00:00Z', context: 'homeops' }
}
