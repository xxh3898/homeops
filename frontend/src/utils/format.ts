export function formatBytes(value: number) {
  if (!Number.isFinite(value) || value < 0) {
    return '—'
  }
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let result = value
  let unit = 0
  while (result >= 1024 && unit < units.length - 1) {
    result /= 1024
    unit += 1
  }
  const digits = result >= 100 || unit === 0 ? 0 : 1
  return `${result.toFixed(digits)} ${units[unit]}`
}

export function formatPercent(value: number) {
  if (!Number.isFinite(value)) {
    return '—'
  }
  return `${Math.max(0, Math.min(100, value)).toFixed(1)}%`
}

export function formatContainerCpu(value: number | null) {
  if (value === null || !Number.isFinite(value) || value < 0) {
    return '—'
  }
  return `${value.toFixed(1)}%`
}

export function percentage(used: number, total: number) {
  return total > 0 ? (used / total) * 100 : 0
}

export function formatUptime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '—'
  }
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  if (days > 0) {
    return `${days}d ${hours}h`
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  return `${minutes}m`
}

export function formatTimestamp(value: string | null) {
  if (!value) {
    return 'Never'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return 'Invalid time'
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(parsed)
}
