import type { MetricHistory, MetricHistoryPeriod, MetricHistoryPoint } from './api/types'

export type HistoryMetric = 'cpu' | 'memory' | 'disk'

export interface MetricHistorySlot {
  timestamp: string
  value: number | null
  peak: number | null
  point: MetricHistoryPoint | null
}

export interface MetricHistorySummary {
  latest: MetricHistorySlot | null
  average: number | null
  peak: number | null
}

export const metricHistoryPeriodConfig: Record<
  MetricHistoryPeriod,
  { label: string; refreshMs: number }
> = {
  '1h': { label: '1h', refreshMs: 60_000 },
  '6h': { label: '6h', refreshMs: 5 * 60_000 },
  '24h': { label: '24h', refreshMs: 15 * 60_000 },
  '7d': { label: '7d', refreshMs: 60 * 60_000 },
}

const MAX_HISTORY_POINTS = 168

export function buildMetricHistorySlots(
  history: MetricHistory,
  metric: HistoryMetric,
): MetricHistorySlot[] {
  const from = Date.parse(history.from)
  const to = Date.parse(history.to)
  const bucketMilliseconds = history.bucketSeconds * 1_000
  if (
    !Number.isFinite(from)
    || !Number.isFinite(to)
    || !Number.isSafeInteger(bucketMilliseconds)
    || bucketMilliseconds <= 0
    || to <= from
    || (to - from) % bucketMilliseconds !== 0
  ) {
    return []
  }

  const slotCount = (to - from) / bucketMilliseconds
  if (!Number.isSafeInteger(slotCount) || slotCount < 1 || slotCount > MAX_HISTORY_POINTS) {
    return []
  }

  const pointByTimestamp = new Map<number, MetricHistoryPoint>()
  for (const point of history.points) {
    const timestamp = Date.parse(point.bucketStart)
    if (
      Number.isFinite(timestamp)
      && timestamp >= from
      && timestamp < to
      && (timestamp - from) % bucketMilliseconds === 0
    ) {
      pointByTimestamp.set(timestamp, point)
    }
  }

  return Array.from({ length: slotCount }, (_, index) => {
    const timestamp = from + index * bucketMilliseconds
    const point = pointByTimestamp.get(timestamp) ?? null
    const values = point === null ? { value: null, peak: null } : metricValues(point, metric)
    return {
      timestamp: new Date(timestamp).toISOString(),
      value: values.value,
      peak: values.peak,
      point,
    }
  })
}

export function summarizeMetricHistory(
  slots: MetricHistorySlot[],
  metric: HistoryMetric,
): MetricHistorySummary {
  const available = slots.filter(
    (slot): slot is MetricHistorySlot & { value: number; point: MetricHistoryPoint } =>
      slot.value !== null && slot.point !== null,
  )
  const latest = available.at(-1) ?? null
  if (available.length === 0) {
    return { latest, average: null, peak: null }
  }
  if (metric === 'disk') {
    return { latest, average: null, peak: null }
  }

  const sampleCount = available.reduce((total, slot) => total + slot.point.sampleCount, 0)
  const average = sampleCount > 0
    ? available.reduce((total, slot) => total + slot.value * slot.point.sampleCount, 0) / sampleCount
    : null
  const peaks = available.flatMap((slot) => slot.peak === null ? [] : [slot.peak])
  return {
    latest,
    average,
    peak: peaks.length > 0 ? Math.max(...peaks) : null,
  }
}

export function formatHistoryAxisTimestamp(value: string, period: MetricHistoryPeriod) {
  const timestamp = new Date(value)
  if (Number.isNaN(timestamp.getTime())) return '—'
  return new Intl.DateTimeFormat(undefined, period === '7d'
    ? { month: 'short', day: 'numeric', hour: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' }).format(timestamp)
}

export function formatHistoryTimestamp(value: string) {
  const timestamp = new Date(value)
  if (Number.isNaN(timestamp.getTime())) return 'Invalid time'
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(timestamp)
}

function metricValues(point: MetricHistoryPoint, metric: HistoryMetric) {
  if (metric === 'cpu') {
    return {
      value: finitePercent(point.cpuUsageAverage),
      peak: finitePercent(point.cpuUsagePeak),
    }
  }
  if (metric === 'memory') {
    return {
      value: usagePercent(point.memoryUsedAverageBytes, point.memoryTotalBytes),
      peak: usagePercent(point.memoryUsedPeakBytes, point.memoryTotalBytes),
    }
  }
  return {
    value: usagePercent(point.diskUsedBytes, point.diskTotalBytes),
    peak: null,
  }
}

function usagePercent(used: number, total: number) {
  if (!Number.isFinite(used) || !Number.isFinite(total) || used < 0 || total <= 0) return null
  return finitePercent((used / total) * 100)
}

function finitePercent(value: number) {
  return Number.isFinite(value) && value >= 0 ? Math.min(100, value) : null
}
