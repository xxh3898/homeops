import { act, render, renderHook, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectionBanner } from '../ui/ConnectionBanner'
import { useOnlineStatus } from './useOnlineStatus'
import { usePageVisible } from './usePageVisible'

describe('PWA lifecycle hooks', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('tracks browser online and offline lifecycle events', () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true)
    const { result } = renderHook(() => useOnlineStatus())

    expect(result.current).toBe(true)

    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(result.current).toBe(false)

    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(result.current).toBe(true)
  })

  it('tracks page visibility changes so polling can pause in the background', () => {
    let visibilityState: DocumentVisibilityState = 'visible'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibilityState)
    const { result } = renderHook(() => usePageVisible())

    expect(result.current).toBe(true)

    visibilityState = 'hidden'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(result.current).toBe(false)

    visibilityState = 'visible'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(result.current).toBe(true)
  })

  it('shows a stale-state warning without claiming unsupported controls exist', () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true)
    render(<ConnectionBanner />)

    expect(screen.queryByText('Offline. Status may be stale.')).not.toBeInTheDocument()

    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(screen.getByText('Offline. Status may be stale.')).toBeInTheDocument()
    expect(screen.queryByText(/control is disabled/i)).not.toBeInTheDocument()
  })
})
