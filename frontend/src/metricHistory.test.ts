import { describe, expect, it } from 'vitest'
import type { MetricHistory } from './api/types'
import {
  buildMetricHistorySlots,
  formatHistoryTimestamp,
  summarizeMetricHistory,
} from './metricHistory'

describe('metric history chart mapping', () => {
  it('creates null chart slots for missing backend buckets without zero filling', () => {
    const history = metricHistory([
      point('2026-08-17T12:00:00Z', 2, 10, 30),
      point('2026-08-17T12:10:00Z', 1, 40, 50),
    ])

    const slots = buildMetricHistorySlots(history, 'cpu')

    expect(slots).toHaveLength(3)
    expect(slots.map((slot) => slot.value)).toEqual([10, null, 40])
    expect(slots[1].point).toBeNull()
  })

  it('excludes missing slots and weights CPU summary by sample count', () => {
    const slots = buildMetricHistorySlots(metricHistory([
      point('2026-08-17T12:00:00Z', 2, 10, 30),
      point('2026-08-17T12:10:00Z', 1, 40, 50),
    ]), 'cpu')

    const summary = summarizeMetricHistory(slots, 'cpu')

    expect(summary.latest?.value).toBe(40)
    expect(summary.average).toBe(20)
    expect(summary.peak).toBe(50)
  })

  it('uses memory used values and returns unavailable when total is invalid', () => {
    const valid = point('2026-08-17T12:00:00Z', 1, 10, 20)
    valid.memoryTotalBytes = 1_000
    valid.memoryUsedAverageBytes = 400
    valid.memoryUsedPeakBytes = 600
    const invalid = point('2026-08-17T12:10:00Z', 1, 10, 20)
    invalid.memoryTotalBytes = 0

    const slots = buildMetricHistorySlots(metricHistory([valid, invalid]), 'memory')

    expect(slots.map((slot) => slot.value)).toEqual([40, null, null])
    expect(slots[0].peak).toBe(60)
  })

  it('does not manufacture a disk average or peak', () => {
    const diskPoint = point('2026-08-17T12:10:00Z', 12, 10, 90)
    diskPoint.diskTotalBytes = 1_000
    diskPoint.diskUsedBytes = 250
    const slots = buildMetricHistorySlots(metricHistory([diskPoint]), 'disk')

    const summary = summarizeMetricHistory(slots, 'disk')

    expect(summary.latest?.value).toBe(25)
    expect(summary.average).toBeNull()
    expect(summary.peak).toBeNull()
    expect(slots.at(-1)?.peak).toBeNull()
  })

  it('rejects response metadata that would exceed the bounded chart contract', () => {
    const history = metricHistory([])
    history.to = '2026-08-18T03:00:00Z'

    expect(buildMetricHistorySlots(history, 'cpu')).toEqual([])
  })

  it('formats UTC response timestamps in the browser local timezone', () => {
    const expected = new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date('2026-08-17T12:00:00Z'))

    expect(formatHistoryTimestamp('2026-08-17T12:00:00Z')).toBe(expected)
  })
})

function metricHistory(points: MetricHistory['points']): MetricHistory {
  return {
    period: '6h',
    from: '2026-08-17T12:00:00Z',
    to: '2026-08-17T12:15:00Z',
    bucketSeconds: 300,
    points,
  }
}

function point(bucketStart: string, sampleCount: number, average: number, peak: number) {
  return {
    bucketStart,
    sampleCount,
    cpuUsageAverage: average,
    cpuUsagePeak: peak,
    memoryTotalBytes: 1_000,
    memoryUsedAverageBytes: 500,
    memoryUsedPeakBytes: 600,
    diskTotalBytes: 1_000,
    diskUsedBytes: 500,
  }
}
