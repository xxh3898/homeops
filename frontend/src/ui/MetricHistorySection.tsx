import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { getMetricHistory, isConnectionError } from '../api/client'
import { metricHistoryPeriods, type MetricHistory, type MetricHistoryPeriod } from '../api/types'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import {
  buildMetricHistorySlots,
  formatHistoryAxisTimestamp,
  formatHistoryTimestamp,
  type HistoryMetric,
  metricHistoryPeriodConfig,
  summarizeMetricHistory,
} from '../metricHistory'
import { formatBytes, formatPercent } from '../utils/format'
import { Card } from './Card'

const metrics: Array<{ value: HistoryMetric; label: string }> = [
  { value: 'cpu', label: 'CPU' },
  { value: 'memory', label: 'Memory' },
  { value: 'disk', label: 'Disk' },
]

export function MetricHistorySection() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const [metric, setMetric] = useState<HistoryMetric>('cpu')
  const [period, setPeriod] = useState<MetricHistoryPeriod>('6h')
  const query = useQuery({
    queryKey: ['metric-history', period],
    queryFn: ({ signal }) => getMetricHistory(period, signal),
    refetchInterval: visible ? metricHistoryPeriodConfig[period].refreshMs : false,
  })
  const history = query.data?.period === period ? query.data : undefined

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-300">Metric History</h3>
          <p className="mt-1 text-xs text-slate-500">
            Completed aggregate buckets, separate from live Current utilization.
          </p>
        </div>
        <button
          type="button"
          aria-label="Refresh metric history"
          className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-xl bg-white/10"
          onClick={() => void query.refetch()}
        >
          <RefreshCw aria-hidden="true" size={17} />
        </button>
      </div>

      <HistoryControls metric={metric} period={period} onMetric={setMetric} onPeriod={setPeriod} />

      {history === undefined && query.isPending ? (
        <div className="mt-4 h-64 animate-pulse rounded-xl bg-white/5" aria-label="Loading metric history" />
      ) : history === undefined ? (
        <HistoryError message={query.error?.message} onRetry={() => void query.refetch()} />
      ) : (
        <HistoryContent
          history={history}
          metric={metric}
          stale={!online || query.isRefetchError}
          staleMessage={isConnectionError(query.error) ? query.error.message : undefined}
        />
      )}
    </Card>
  )
}

function HistoryControls({
  metric,
  period,
  onMetric,
  onPeriod,
}: {
  metric: HistoryMetric
  period: MetricHistoryPeriod
  onMetric: (metric: HistoryMetric) => void
  onPeriod: (period: MetricHistoryPeriod) => void
}) {
  return (
    <div className="mt-4 grid gap-3 sm:grid-cols-2">
      <fieldset>
        <legend className="text-xs font-medium text-slate-500">Metric</legend>
        <div className="mt-2 grid grid-cols-3 gap-1 rounded-xl bg-slate-950/70 p-1">
          {metrics.map((option) => (
            <SelectorButton
              key={option.value}
              selected={metric === option.value}
              onClick={() => onMetric(option.value)}
            >
              {option.label}
            </SelectorButton>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend className="text-xs font-medium text-slate-500">Period</legend>
        <div className="mt-2 grid grid-cols-4 gap-1 rounded-xl bg-slate-950/70 p-1">
          {metricHistoryPeriods.map((option) => (
            <SelectorButton
              key={option}
              selected={period === option}
              onClick={() => onPeriod(option)}
            >
              {metricHistoryPeriodConfig[option].label}
            </SelectorButton>
          ))}
        </div>
      </fieldset>
    </div>
  )
}

function SelectorButton({
  children,
  selected,
  onClick,
}: {
  children: string
  selected: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={`min-h-11 rounded-lg px-2 text-xs font-semibold transition ${
        selected ? 'bg-teal-400/15 text-teal-200' : 'text-slate-400 hover:text-slate-200'
      }`}
      onClick={onClick}
    >
      {children}
    </button>
  )
}

function HistoryContent({
  history,
  metric,
  stale,
  staleMessage,
}: {
  history: MetricHistory
  metric: HistoryMetric
  stale: boolean
  staleMessage?: string
}) {
  if (history.points.length === 0) {
    return (
      <div className="mt-4 space-y-3">
        {stale && <HistoryStaleNotice message={staleMessage} />}
        <HistoryNotice
          title="No metric history yet"
          detail="No completed aggregate buckets are available for this period."
        />
      </div>
    )
  }

  const slots = buildMetricHistorySlots(history, metric)
  const summary = summarizeMetricHistory(slots, metric)
  const hasGap = slots.some((slot) => slot.point === null)
  if (summary.latest === null) {
    return (
      <div className="mt-4 space-y-3">
        {stale && <HistoryStaleNotice message={staleMessage} />}
        <HistoryNotice
          title="Metric unavailable"
          detail="Stored points do not contain a usable value for this metric."
        />
      </div>
    )
  }

  const metricLabel = metrics.find((option) => option.value === metric)?.label ?? metric
  const averageLabel = metric === 'disk' ? 'Usage' : 'Average'

  return (
    <div className="mt-4 space-y-3">
      {stale && (
        <HistoryStaleNotice message={staleMessage} />
      )}
      {hasGap && (
        <p className="rounded-xl bg-sky-400/10 px-3 py-2 text-sm text-sky-100">
          Showing available retained data. Missing buckets are left as gaps.
        </p>
      )}

      <div
        className="h-64 min-w-0 overflow-hidden"
        role="img"
        aria-label={`${metricLabel} history for the last ${history.period}`}
      >
        <ResponsiveContainer width="100%" height="100%" minWidth={1} minHeight={1}>
          <LineChart data={slots} margin={{ top: 8, right: 8, left: -24, bottom: 0 }}>
            <CartesianGrid stroke="#1e293b" vertical={false} />
            <XAxis
              dataKey="timestamp"
              stroke="#94a3b8"
              fontSize={11}
              minTickGap={28}
              tickFormatter={(value) => formatHistoryAxisTimestamp(String(value), history.period)}
            />
            <YAxis domain={[0, 100]} stroke="#64748b" fontSize={11} />
            <Tooltip
              allowEscapeViewBox={{ x: false, y: false }}
              wrapperStyle={{ maxWidth: 'min(18rem, calc(100vw - 3rem))' }}
              contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 12 }}
              labelFormatter={(value) => formatHistoryTimestamp(String(value))}
              formatter={(value, name) => [formatPercent(Number(value)), String(name)]}
            />
            <Line
              type="monotone"
              dataKey="value"
              name={averageLabel}
              stroke="#5eead4"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
            />
            {metric !== 'disk' && (
              <Line
                type="monotone"
                dataKey="peak"
                name="Peak"
                stroke="#fbbf24"
                strokeWidth={1.5}
                strokeDasharray="4 4"
                dot={false}
                connectNulls={false}
                isAnimationActive={false}
              />
            )}
          </LineChart>
        </ResponsiveContainer>
      </div>

      <HistorySummary metric={metric} summary={summary} />
    </div>
  )
}

function HistorySummary({
  metric,
  summary,
}: {
  metric: HistoryMetric
  summary: ReturnType<typeof summarizeMetricHistory>
}) {
  if (summary.latest === null || summary.latest.point === null || summary.latest.value === null) return null
  const latestPoint = summary.latest.point
  const detail = metric === 'memory'
    ? `${formatBytes(latestPoint.memoryUsedAverageBytes)} / ${formatBytes(latestPoint.memoryTotalBytes)}`
    : metric === 'disk'
      ? `${formatBytes(latestPoint.diskUsedBytes)} / ${formatBytes(latestPoint.diskTotalBytes)}`
      : undefined

  return (
    <div aria-label="Metric history summary">
      <div className={`grid gap-2 ${metric === 'disk' ? 'grid-cols-1' : 'grid-cols-3'}`}>
        <SummaryValue label="Latest completed" value={formatPercent(summary.latest.value)} detail={detail} />
        {metric !== 'disk' && (
          <>
            <SummaryValue label="Average" value={formatPercent(summary.average ?? Number.NaN)} />
            <SummaryValue label="Peak" value={formatPercent(summary.peak ?? Number.NaN)} />
          </>
        )}
      </div>
      <p className="mt-2 text-xs text-slate-500">
        Latest completed bucket: {formatHistoryTimestamp(summary.latest.timestamp)}. This is not the live snapshot.
      </p>
    </div>
  )
}

function SummaryValue({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="rounded-xl bg-white/5 px-3 py-3">
      <p className="text-[10px] font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
      {detail && <p className="mt-1 truncate text-[11px] text-slate-500">{detail}</p>}
    </div>
  )
}

function HistoryError({ message, onRetry }: { message?: string; onRetry: () => void }) {
  return (
    <div className="mt-4 rounded-xl border border-rose-400/30 p-4">
      <p className="font-medium text-rose-200">Unable to load metric history</p>
      <p className="mt-1 text-sm text-slate-400">{message ?? 'No cached metric history is available.'}</p>
      <button
        type="button"
        className="mt-3 min-h-11 rounded-xl bg-white/10 px-4 text-sm font-semibold"
        onClick={onRetry}
      >
        Retry
      </button>
    </div>
  )
}

function HistoryNotice({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mt-4 rounded-xl bg-white/5 px-4 py-6 text-center">
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-sm text-slate-400">{detail}</p>
    </div>
  )
}

function HistoryStaleNotice({ message }: { message?: string }) {
  return (
    <p className="rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
      {message ?? 'Metric history could not be refreshed. The last successful result is shown.'}
    </p>
  )
}
