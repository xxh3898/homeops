import { useQuery } from '@tanstack/react-query'
import { Cpu, Database, HardDrive, MemoryStick, RefreshCw, Timer } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { getSystemSummary } from '../api/client'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { StatusBadge } from '../ui/StatusBadge'
import { formatBytes, formatPercent, formatTimestamp, formatUptime, percentage } from '../utils/format'

export function OverviewPage() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const query = useQuery({
    queryKey: ['system-summary'],
    queryFn: getSystemSummary,
    refetchInterval: visible ? 5_000 : false,
  })

  if (query.isPending) {
    return <OverviewSkeleton />
  }
  if (query.isError) {
    return (
      <Card className="border-rose-400/30">
        <h2 className="font-semibold text-rose-200">Unable to load HomeOps</h2>
        <p className="mt-2 text-sm text-slate-400">{query.error.message}</p>
        <button
          type="button"
          className="mt-4 inline-flex min-h-11 items-center gap-2 rounded-xl bg-white/10 px-4 text-sm font-semibold"
          onClick={() => void query.refetch()}
        >
          <RefreshCw aria-hidden="true" size={17} />
          Retry
        </button>
      </Card>
    )
  }

  const summary = query.data
  const effectivelyStale = summary.stale || !online || query.isRefetchError
  const memoryPercent = summary.host
    ? percentage(summary.host.memoryUsedBytes, summary.host.memoryTotalBytes)
    : 0
  const diskPercent = summary.host
    ? percentage(summary.host.diskUsedBytes, summary.host.diskTotalBytes)
    : 0
  const chartData = summary.host
    ? [
        { name: 'CPU', value: summary.host.cpuUsagePercent },
        { name: 'Memory', value: memoryPercent },
        { name: 'Disk', value: diskPercent },
      ]
    : []

  return (
    <div className="space-y-4">
      <Card className={effectivelyStale ? 'border-amber-400/30' : 'border-emerald-400/20'}>
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-sm text-slate-400">Mac mini</p>
            <h2 className="mt-1 text-xl font-semibold">System overview</h2>
          </div>
          <StatusBadge status={effectivelyStale ? 'STALE' : summary.agentStatus} />
        </div>
        <p className="mt-4 text-xs text-slate-500">
          Last collected {formatTimestamp(summary.lastUpdatedAt)}
        </p>
        {effectivelyStale && (
          <p className="mt-3 rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
            This snapshot is stale. Do not treat the displayed state as current.
          </p>
        )}
      </Card>

      {summary.host ? (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <MetricCard icon={Cpu} label="CPU" value={formatPercent(summary.host.cpuUsagePercent)} />
            <MetricCard
              icon={MemoryStick}
              label="Memory"
              value={formatPercent(memoryPercent)}
              detail={`${formatBytes(summary.host.memoryUsedBytes)} / ${formatBytes(summary.host.memoryTotalBytes)}`}
            />
            <MetricCard
              icon={HardDrive}
              label="Disk"
              value={formatPercent(diskPercent)}
              detail={`${formatBytes(summary.host.diskUsedBytes)} / ${formatBytes(summary.host.diskTotalBytes)}`}
            />
            <MetricCard icon={Timer} label="Uptime" value={formatUptime(summary.host.uptimeSeconds)} />
          </div>

          <Card>
            <h3 className="text-sm font-semibold text-slate-300">Current utilization</h3>
            <div className="mt-3 h-48" aria-label="Current utilization chart">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} margin={{ top: 8, right: 0, left: -24, bottom: 0 }}>
                  <CartesianGrid stroke="#1e293b" vertical={false} />
                  <XAxis dataKey="name" stroke="#94a3b8" fontSize={12} />
                  <YAxis domain={[0, 100]} stroke="#64748b" fontSize={11} />
                  <Tooltip
                    cursor={{ fill: '#0f172a' }}
                    contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 12 }}
                    formatter={(value) => [formatPercent(Number(value)), 'Usage']}
                  />
                  <Bar dataKey="value" fill="#5eead4" radius={[8, 8, 2, 2]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </>
      ) : (
        <Card>
          <p className="text-sm text-slate-400">Waiting for the native Agent's first snapshot.</p>
        </Card>
      )}

      <Card>
        <div className="flex items-center gap-2">
          <Database aria-hidden="true" size={18} className="text-teal-300" />
          <h3 className="font-semibold">Docker</h3>
        </div>
        <div className="mt-4 grid grid-cols-4 gap-2 text-center">
          <Count label="Total" value={summary.docker.total} />
          <Count label="Running" value={summary.docker.running} tone="good" />
          <Count label="Not running" value={summary.docker.notRunning} />
          <Count label="Unhealthy" value={summary.docker.unhealthy} tone="danger" />
        </div>
      </Card>
    </div>
  )
}

interface MetricCardProps {
  icon: typeof Cpu
  label: string
  value: string
  detail?: string
}

function MetricCard({ icon: Icon, label, value, detail }: MetricCardProps) {
  return (
    <Card className="min-h-32">
      <Icon aria-hidden="true" size={18} className="text-teal-300" />
      <p className="mt-3 text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-xl font-semibold">{value}</p>
      {detail && <p className="mt-1 truncate text-[11px] text-slate-500">{detail}</p>}
    </Card>
  )
}

function Count({ label, value, tone }: { label: string; value: number; tone?: 'good' | 'danger' }) {
  const toneClass = tone === 'good' ? 'text-emerald-300' : tone === 'danger' ? 'text-rose-300' : 'text-slate-100'
  return (
    <div className="rounded-xl bg-white/5 px-1 py-3">
      <p className={`text-xl font-semibold ${toneClass}`}>{value}</p>
      <p className="mt-1 text-[10px] text-slate-500">{label}</p>
    </div>
  )
}

function OverviewSkeleton() {
  return (
    <div className="space-y-4" aria-label="Loading system overview">
      <div className="h-36 animate-pulse rounded-2xl bg-white/5" />
      <div className="grid grid-cols-2 gap-3">
        {Array.from({ length: 4 }).map((_, index) => (
          <div key={index} className="h-32 animate-pulse rounded-2xl bg-white/5" />
        ))}
      </div>
    </div>
  )
}
