import { describe, expect, it } from 'vitest'
import { formatBytes, formatContainerCpu, formatUptime, percentage } from './format'

describe('format utilities', () => {
  it('formats binary byte values', () => {
    expect(formatBytes(1_073_741_824)).toBe('1.0 GiB')
  })

  it('formats host uptime without seconds noise', () => {
    expect(formatUptime(176_400)).toBe('2d 1h')
  })

  it('avoids division by zero for missing totals', () => {
    expect(percentage(10, 0)).toBe(0)
  })

  it('keeps Docker multi-core CPU values above one hundred percent', () => {
    expect(formatContainerCpu(125.25)).toBe('125.3%')
  })
})
