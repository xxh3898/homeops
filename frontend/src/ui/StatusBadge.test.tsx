import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('renders status as text instead of relying on color alone', () => {
    render(<StatusBadge status="unhealthy" />)
    expect(screen.getByText('UNHEALTHY')).toBeInTheDocument()
  })
})

