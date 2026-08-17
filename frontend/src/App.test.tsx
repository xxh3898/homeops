import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { App } from './App'

vi.mock('./pages/OverviewPage', () => ({ OverviewPage: () => <p>Overview page</p> }))
vi.mock('./pages/ContainersPage', () => ({ ContainersPage: () => <p>Containers page</p> }))
vi.mock('./pages/ContainerDetailPage', () => ({ ContainerDetailPage: () => <p>Container detail page</p> }))
vi.mock('./pages/ActivityPage', () => ({ ActivityPage: () => <p>Activity page</p> }))
vi.mock('./pages/PlaceholderPage', () => ({ PlaceholderPage: () => <p>Settings page</p> }))
vi.mock('./ui/ConnectionBanner', () => ({ ConnectionBanner: () => null }))
vi.mock('./ui/PwaUpdatePrompt', () => ({ PwaUpdatePrompt: () => null }))

describe('App routing', () => {
  it('keeps Containers navigation active on a container detail route', () => {
    render(
      <MemoryRouter initialEntries={['/containers/0123456789ab']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByText('Container detail page')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Containers' })).toHaveClass('text-teal-200')
  })
})
