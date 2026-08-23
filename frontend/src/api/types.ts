export interface SystemSummary {
  agentStatus: 'CONNECTED' | 'STALE' | 'OFFLINE'
  lastUpdatedAt: string | null
  stale: boolean
  host: HostMetric | null
  docker: DockerSummary
}

export interface HostMetric {
  cpuUsagePercent: number
  memoryTotalBytes: number
  memoryUsedBytes: number
  diskTotalBytes: number
  diskUsedBytes: number
  uptimeSeconds: number
}

export const metricHistoryPeriods = ['1h', '6h', '24h', '7d'] as const

export type MetricHistoryPeriod = (typeof metricHistoryPeriods)[number]

export interface MetricHistory {
  period: MetricHistoryPeriod
  from: string
  to: string
  bucketSeconds: number
  points: MetricHistoryPoint[]
}

export interface MetricHistoryPoint {
  bucketStart: string
  sampleCount: number
  cpuUsageAverage: number
  cpuUsagePeak: number
  memoryTotalBytes: number
  memoryUsedAverageBytes: number
  memoryUsedPeakBytes: number
  diskTotalBytes: number
  diskUsedBytes: number
}

export interface DockerSummary {
  total: number
  running: number
  notRunning: number
  unhealthy: number
}

export interface ContainerView {
  id: string
  name: string
  composeProject: string | null
  image: string
  state: string
  health: string
  status: string | null
  startedAt: string | null
  restartCount: number
  cpuUsagePercent: number | null
  memoryUsageBytes: number | null
  memoryLimitBytes: number | null
  ports: Array<{
    privatePort: number
    publicPort: number | null
    type: string
  }>
  managed: boolean
  logsAllowed: boolean
}

export interface ContainerInventory {
  agentStatus: 'CONNECTED' | 'STALE' | 'OFFLINE'
  lastUpdatedAt: string | null
  stale: boolean
  containers: ContainerView[]
}

export interface ContainerDetail {
  agentStatus: 'CONNECTED' | 'STALE'
  lastUpdatedAt: string
  stale: boolean
  supportsContainerLogs: boolean
  container: ContainerView
}

export const containerLogTails = [50, 100, 200] as const

export type ContainerLogTail = (typeof containerLogTails)[number]

export interface ContainerLogResponse {
  containerId: string
  requestedTail: ContainerLogTail
  collectedAt: string
  truncated: boolean
  redactionApplied: boolean
  lines: ContainerLogLine[]
}

export interface ContainerLogLine {
  timestamp: string | null
  stream: 'STDOUT' | 'STDERR' | 'COMBINED'
  message: string
}

export const containerControlOperations = ['START', 'STOP', 'RESTART'] as const

export type ContainerControlOperation = (typeof containerControlOperations)[number]

export const containerActionStatuses = [
  'REQUESTED',
  'APPLIED',
  'NOOP',
  'DENIED',
  'FAILED',
  'OUTCOME_UNKNOWN',
  'EXPIRED',
] as const

export type ContainerActionStatus = (typeof containerActionStatuses)[number]

export interface ContainerActionResponse {
  operationId: string
  containerId: string
  operation: ContainerControlOperation
  status: ContainerActionStatus
  reasonCode: string | null
  requestedAt: string
  completedAt: string | null
}

export interface ActivityEvent {
  id: string
  type: 'DEPLOYMENT' | 'BACKUP' | 'INCIDENT' | 'AGENT' | 'CONTAINER_ACTION'
  title: string
  status: string
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'RECOVERY'
  occurredAt: string
  context: string
}

export interface ActivityPage {
  items: ActivityEvent[]
  nextCursor: string | null
  generatedAt: string
}
