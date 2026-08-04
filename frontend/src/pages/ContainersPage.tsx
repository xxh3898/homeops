import { useQuery } from '@tanstack/react-query'
import { Box, RefreshCw } from 'lucide-react'
import { getContainers } from '../api/client'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { StatusBadge } from '../ui/StatusBadge'
import { formatBytes, formatContainerCpu, formatTimestamp } from '../utils/format'

export function ContainersPage() {
  const visible = usePageVisible()
  const query = useQuery({
    queryKey: ['containers'],
    queryFn: getContainers,
    refetchInterval: visible ? 5_000 : false,
  })

  if (query.isPending) {
    return <div className="h-40 animate-pulse rounded-2xl bg-white/5" aria-label="Loading containers" />
  }
  if (query.isError) {
    return (
      <Card className="border-rose-400/30">
        <p className="font-semibold text-rose-200">Unable to load containers</p>
        <p className="mt-2 text-sm text-slate-400">{query.error.message}</p>
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
  if (query.data.length === 0) {
    return (
      <Card>
        <Box aria-hidden="true" className="text-slate-500" />
        <h2 className="mt-3 font-semibold">No container snapshot</h2>
        <p className="mt-2 text-sm text-slate-400">The Agent has not reported any containers yet.</p>
      </Card>
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex items-end justify-between px-1">
        <div>
          <h2 className="text-lg font-semibold">Containers</h2>
          <p className="text-xs text-slate-500">Read-only inventory · {query.data.length} total</p>
        </div>
      </div>
      {query.data.map((container) => (
        <Card key={container.id}>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-semibold">{container.name}</p>
              <p className="mt-1 truncate text-xs text-slate-500">{container.image}</p>
            </div>
            <StatusBadge status={container.health === 'NONE' ? container.state : container.health} />
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
