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
}

export interface ContainerInventory {
  agentStatus: 'CONNECTED' | 'STALE' | 'OFFLINE'
  lastUpdatedAt: string | null
  stale: boolean
  containers: ContainerView[]
}
