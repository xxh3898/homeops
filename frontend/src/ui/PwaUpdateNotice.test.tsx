import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PwaUpdateNotice } from './PwaUpdateNotice'

describe('PwaUpdateNotice', () => {
  it('renders nothing when no service worker state needs attention', () => {
    const { container } = render(
      <PwaUpdateNotice
        needRefresh={false}
        offlineReady={false}
        onDismiss={vi.fn()}
        onUpdate={vi.fn()}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })

  it('announces offline readiness and supports dismissing the notice', () => {
    const onDismiss = vi.fn()
    render(
      <PwaUpdateNotice
        needRefresh={false}
        offlineReady
        onDismiss={onDismiss}
        onUpdate={vi.fn()}
      />,
    )

    expect(screen.getByText('HomeOps app shell is ready for offline launch.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Update' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(onDismiss).toHaveBeenCalledOnce()
  })

  it('offers an explicit update action when a new app shell is ready', () => {
    const onUpdate = vi.fn()
    render(
      <PwaUpdateNotice
        needRefresh
        offlineReady={false}
        onDismiss={vi.fn()}
        onUpdate={onUpdate}
      />,
    )

    expect(screen.getByText('A new HomeOps version is ready.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))
    expect(onUpdate).toHaveBeenCalledOnce()
  })
})
