import { useQuery } from '@tanstack/react-query'
import { Box, RefreshCw } from 'lucide-react'
import { getContainers, isAuthorizationError } from '../api/client'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { StatusBadge } from '../ui/StatusBadge'
import { formatBytes, formatContainerCpu, formatTimestamp } from '../utils/format'

export function ContainersPage() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const query = useQuery({
    queryKey: ['containers'],
    queryFn: getContainers,
    refetchInterval: visible ? 5_000 : false,
  })

  if (query.isPending) {
    return <div className="h-40 animate-pulse rounded-2xl bg-white/5" aria-label="Loading containers" />
  }
  if (isAuthorizationError(query.error) || query.data === undefined) {
    return (
      <Card className="border-rose-400/30">
        <p className="font-semibold text-rose-200">Unable to load containers</p>
        <p className="mt-2 text-sm text-slate-400">
          {query.error?.message ?? 'No cached container snapshot is available.'}
        </p>
        <button
          type="button"
          className="mt-4 inline-flex min-h-11 items-center gap-2 rounded-xl bg-white/10 px-4 text-sm font-semibold"
          onClick={() => void query.refetch()}
        >
          <RefreshCw aria-hidden="true" size={17} /> Retry
        </button>
      </Card>
    )
  }
  const inventory = query.data
  const effectivelyStale = inventory.stale || !online || query.isRefetchError

  return (
    <div className="space-y-3">
      <Card className={effectivelyStale ? 'border-amber-400/30' : 'border-emerald-400/20'}>
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold">Containers</h2>
            <p className="mt-1 text-xs text-slate-500">
              Read-only inventory · {inventory.containers.length} total
            </p>
          </div>
          <StatusBadge status={effectivelyStale ? 'STALE' : inventory.agentStatus} />
        </div>
        <p className="mt-4 text-xs text-slate-500">
          Last collected {formatTimestamp(inventory.lastUpdatedAt)}
        </p>
        {effectivelyStale && (
          <p className="mt-3 rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
            This container snapshot is stale. Do not treat displayed states as current.
          </p>
        )}
      </Card>
      {inventory.containers.length === 0 && (
        <Card>
          <Box aria-hidden="true" className="text-slate-500" />
          <h3 className="mt-3 font-semibold">No container snapshot</h3>
          <p className="mt-2 text-sm text-slate-400">The Agent has not reported any containers yet.</p>
        </Card>
      )}
      {inventory.containers.map((container) => (
        <Card key={container.id}>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-semibold">{container.name}</p>
              <p className="mt-1 truncate text-xs text-slate-500">{container.image}</p>
            </div>
            <StatusBadge
              status={effectivelyStale ? 'STALE' : container.health === 'NONE' ? container.state : container.health}
            />
          </div>
          <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-xs">
            <Detail label="Project" value={container.composeProject ?? 'Standalone'} />
            <Detail label="Container ID" value={container.id} mono />
            <Detail label="State" value={container.state} />
            <Detail label="Restarts" value={String(container.restartCount)} />
            <Detail label="CPU" value={formatContainerCpu(container.cpuUsagePercent)} />
            <Detail
              label="Memory"
              value={formatContainerMemory(container.memoryUsageBytes, container.memoryLimitBytes)}
            />
            <Detail label="Ports" value={formatPorts(container.ports)} />
            <Detail label="Started" value={formatTimestamp(container.startedAt)} />
            <Detail label="Control" value={container.managed ? 'Eligible later' : 'Read only'} />
          </dl>
          {container.status && <p className="mt-4 rounded-lg bg-black/20 px-3 py-2 text-xs text-slate-400">{container.status}</p>}
        </Card>
      ))}
    </div>
  )
}

function formatPorts(ports: Array<{ privatePort: number; publicPort: number | null; type: string }>) {
  if (ports.length === 0) {
    return 'None'
  }
  return ports
    .map((port) => `${port.publicPort ?? '—'}→${port.privatePort}/${port.type.toLowerCase()}`)
    .join(', ')
}

function formatContainerMemory(usage: number | null, limit: number | null) {
  if (usage === null) {
    return '—'
  }
  return limit === null
    ? formatBytes(usage)
    : `${formatBytes(usage)} / ${formatBytes(limit)}`
}

function Detail({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-slate-600">{label}</dt>
      <dd className={`mt-1 truncate text-slate-300 ${mono ? 'font-mono' : ''}`}>{value}</dd>
    </div>
  )
}
