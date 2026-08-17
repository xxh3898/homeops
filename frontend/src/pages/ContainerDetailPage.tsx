import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Box, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import {
  ApiError,
  getContainerDetail,
  isAuthorizationError,
  isConnectionError,
  isContainerDetailTerminalError,
  shouldRetryContainerDetailQuery,
} from '../api/client'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { StatusBadge } from '../ui/StatusBadge'
import { formatBytes, formatContainerCpu, formatTimestamp } from '../utils/format'

const containerIdentifierPattern = /^[0-9a-f]{12}$/

export function ContainerDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const validIdentifier = containerIdentifierPattern.test(id)
  const query = useQuery({
    queryKey: ['container', id],
    queryFn: ({ signal }) => getContainerDetail(id, signal),
    enabled: validIdentifier,
    retry: shouldRetryContainerDetailQuery,
    refetchInterval: visible ? 5_000 : false,
  })

  if (!validIdentifier) {
    return (
      <TerminalState
        title="Invalid container link"
        message="The container identifier in this link is not valid."
      />
    )
  }

  if (query.isPending) {
    return <div className="h-56 animate-pulse rounded-2xl bg-white/5" aria-label="Loading container detail" />
  }

  if (isAuthorizationError(query.error)) {
    return (
      <LoadError
        message={query.error.message}
        onRetry={() => void query.refetch()}
      />
    )
  }

  if (isContainerDetailTerminalError(query.error)) {
    if (query.error.status === 409) {
      return (
        <TerminalState
          title="Container identifier is ambiguous"
          message="More than one reported container matches this identifier. No container was selected."
        />
      )
    }
    if (query.error.status === 404) {
      return (
        <TerminalState
          title={query.data === undefined ? 'Container not reported' : 'Container no longer reported'}
          message="This container is not present in the latest reported snapshot."
        />
      )
    }
    return (
      <TerminalState
        title="Invalid container link"
        message="The container identifier in this link is not valid."
      />
    )
  }

  if (query.data === undefined) {
    return (
      <LoadError
        message={query.error instanceof Error
          ? query.error.message
          : 'No cached container detail is available.'}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const detail = query.data
  const effectivelyStale = detail.stale || !online || query.isRefetchError
  const staleMessage = detailWarning(query.error, online)

  return (
    <div className="space-y-3" aria-label={`Container detail for ${detail.container.name}`}>
      <BackToContainers />
      <Card className={effectivelyStale ? 'border-amber-400/30' : 'border-emerald-400/20'}>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Container detail</p>
            <h2 className="mt-2 break-words text-xl font-semibold">{detail.container.name}</h2>
            <p className="mt-2 text-xs text-slate-500">
              Last collected {formatTimestamp(detail.lastUpdatedAt)}
            </p>
          </div>
          <StatusBadge status={effectivelyStale ? 'STALE' : detail.agentStatus} />
        </div>
        {effectivelyStale && (
          <p role="status" className="mt-4 rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
            {staleMessage}
          </p>
        )}
      </Card>

      <DetailSection title="Identity">
        <Detail label="Name" value={detail.container.name} />
        <Detail label="Short ID" value={detail.container.id} mono />
        <Detail label="Project" value={projectName(detail.container.composeProject)} />
        <Detail label="Image" value={detail.container.image} mono wide />
      </DetailSection>

      <DetailSection title="Runtime">
        <BadgeDetail label="State" status={detail.container.state} description={stateDescription(detail.container.state)} />
        <BadgeDetail label="Health" status={detail.container.health} description={healthDescription(detail.container.health)} />
        <Detail label="Docker status" value={reportedValue(detail.container.status)} wide />
        <Detail label="Started" value={detail.container.startedAt ? formatTimestamp(detail.container.startedAt) : 'Not reported'} />
        <Detail label="Restarts" value={String(detail.container.restartCount)} />
      </DetailSection>

      <DetailSection title="Resources">
        <Detail label="CPU" value={cpuValue(detail.container.cpuUsagePercent)} />
        <Detail
          label="Memory"
          value={memoryValue(detail.container.memoryUsageBytes, detail.container.memoryLimitBytes)}
          wide
        />
      </DetailSection>

      <Card>
        <h3 className="text-base font-semibold">Ports</h3>
        {detail.container.ports.length === 0 ? (
          <p className="mt-3 text-sm text-slate-400">No reported ports</p>
        ) : (
          <ul className="mt-3 space-y-2" aria-label="Reported container ports">
            {detail.container.ports.map((port, index) => (
              <li
                key={`${port.privatePort}-${port.publicPort ?? 'private'}-${port.type}-${index}`}
                className="break-words rounded-xl bg-black/20 px-3 py-2 text-sm text-slate-300"
              >
                {port.publicPort === null
                  ? `Not published → ${port.privatePort}/${port.type.toLowerCase()}`
                  : `${port.publicPort} → ${port.privatePort}/${port.type.toLowerCase()}`}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card>
        <h3 className="text-base font-semibold">Access</h3>
        <p className="mt-3 text-sm font-medium text-slate-200">
          {detail.container.managed ? 'Managed inventory label present' : 'Read-only inventory'}
        </p>
        <p className="mt-2 text-sm text-slate-400">
          Container controls are not available in this read-only phase.
        </p>
      </Card>
    </div>
  )
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card>
      <h3 className="text-base font-semibold">{title}</h3>
      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">{children}</dl>
    </Card>
  )
}

function Detail({
  label,
  value,
  mono = false,
  wide = false,
}: {
  label: string
  value: string
  mono?: boolean
  wide?: boolean
}) {
  return (
    <div className={`min-w-0 ${wide ? 'sm:col-span-2' : ''}`}>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className={`mt-1 break-words text-sm text-slate-200 ${mono ? 'font-mono' : ''}`}>{value}</dd>
    </div>
  )
}

function BadgeDetail({ label, status, description }: { label: string; status: string; description: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-2 flex flex-wrap items-center gap-2">
        <StatusBadge status={status} />
        <span className="text-xs text-slate-400">{description}</span>
      </dd>
    </div>
  )
}

function TerminalState({ title, message }: { title: string; message: string }) {
  return (
    <div className="space-y-3">
      <BackToContainers />
      <Card className="border-amber-400/30">
        <Box aria-hidden="true" className="text-amber-300" />
        <h2 className="mt-3 font-semibold text-amber-100">{title}</h2>
        <p className="mt-2 text-sm text-slate-400">{message}</p>
      </Card>
    </div>
  )
}

function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="space-y-3">
      <BackToContainers />
      <Card className="border-rose-400/30">
        <p className="font-semibold text-rose-200">Unable to load container detail</p>
        <p className="mt-2 text-sm text-slate-400">{message}</p>
        <button
          type="button"
          className="mt-4 inline-flex min-h-11 items-center gap-2 rounded-xl bg-white/10 px-4 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300"
          onClick={onRetry}
        >
          <RefreshCw aria-hidden="true" size={17} /> Retry
        </button>
      </Card>
    </div>
  )
}

function BackToContainers() {
  return (
    <Link
      to="/containers"
      className="inline-flex min-h-11 items-center gap-2 rounded-xl px-3 text-sm font-semibold text-slate-300 transition hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300"
    >
      <ArrowLeft aria-hidden="true" size={17} /> Back to Containers
    </Link>
  )
}

function detailWarning(error: unknown, online: boolean) {
  if (!online) {
    return 'You are offline. This is the last successfully reported container snapshot.'
  }
  if (isConnectionError(error)) {
    return `${error.message} Showing the last successfully reported container snapshot.`
  }
  if (error instanceof ApiError && error.status === 503) {
    return 'Container inventory is temporarily unavailable. Showing the last successfully reported snapshot.'
  }
  if (error instanceof ApiError && error.status >= 500) {
    return 'HomeOps is temporarily unavailable. Showing the last successfully reported container snapshot.'
  }
  return 'This container state comes from a stale Agent snapshot. Do not treat it as current.'
}

function projectName(project: string | null) {
  return project?.trim() || 'Standalone'
}

function reportedValue(value: string | null) {
  return value?.trim() || 'Not reported'
}

function cpuValue(value: number | null) {
  return value === null ? 'Unavailable' : formatContainerCpu(value)
}

function memoryValue(usage: number | null, limit: number | null) {
  if (usage === null) {
    return 'Unavailable'
  }
  if (limit === null) {
    return `${formatBytes(usage)} · Limit not reported`
  }
  return `${formatBytes(usage)} / ${formatBytes(limit)}`
}

function stateDescription(state: string) {
  return state === 'RUNNING' ? 'Reported running at collection time' : 'Reported state at collection time'
}

function healthDescription(health: string) {
  switch (health) {
    case 'HEALTHY':
      return 'Health check passing'
    case 'UNHEALTHY':
      return 'Health check failing'
    case 'STARTING':
      return 'Health check starting'
    case 'NONE':
      return 'No health check'
    default:
      return 'Health not reported'
  }
}
