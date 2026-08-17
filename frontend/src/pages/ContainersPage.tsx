import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ArrowRight, Box, ChevronDown, RefreshCw } from 'lucide-react'
import { Link } from 'react-router'
import { getContainers, isAuthorizationError, isConnectionError } from '../api/client'
import type { ContainerView } from '../api/types'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { StatusBadge } from '../ui/StatusBadge'
import { formatBytes, formatContainerCpu, formatTimestamp } from '../utils/format'

export function ContainersPage() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const [expandedProject, setExpandedProject] = useState<string | null>(null)
  const query = useQuery({
    queryKey: ['containers'],
    queryFn: ({ signal }) => getContainers(signal),
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
  const staleMessage = isConnectionError(query.error)
    ? query.error.message
    : 'This container snapshot is stale. Do not treat displayed states as current.'
  const projectGroups = groupContainers(inventory.containers)

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
            {staleMessage}
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
      {projectGroups.map((project) => {
        const isExpanded = expandedProject === project.key
        const contentId = `project-containers-${project.key}`
        return (
          <Card key={project.key} className="overflow-hidden p-0">
            <button
              type="button"
              className="flex min-h-16 w-full items-center justify-between gap-3 px-4 py-3 text-left"
              aria-expanded={isExpanded}
              aria-controls={contentId}
              onClick={() => setExpandedProject(isExpanded ? null : project.key)}
            >
              <div className="min-w-0">
                <p className="truncate font-semibold">{project.name}</p>
                <p className="mt-1 text-xs text-slate-500">{projectSummary(project.containers)}</p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <StatusBadge status={projectStatus(project.containers, effectivelyStale)} />
                <ChevronDown
                  aria-hidden="true"
                  size={18}
                  className={`text-slate-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
                />
              </div>
            </button>
            {isExpanded && (
              <div id={contentId} className="space-y-3 border-t border-white/10 bg-black/10 p-3">
                {project.containers.map((container) => (
                  <ContainerCard key={container.id} container={container} stale={effectivelyStale} />
                ))}
              </div>
            )}
          </Card>
        )
      })}
    </div>
  )
}

function ContainerCard({ container, stale }: { container: ContainerView; stale: boolean }) {
  return (
    <div className="rounded-xl border border-white/10 bg-slate-950/60 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-semibold">{container.name}</p>
          <p className="mt-1 truncate text-xs text-slate-500">{container.image}</p>
        </div>
        <StatusBadge status={stale ? 'STALE' : container.health === 'NONE' ? container.state : container.health} />
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
      <Link
        to={`/containers/${container.id}`}
        aria-label={`View details for ${container.name}`}
        className="mt-4 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-white/10 px-4 text-sm font-semibold text-slate-200 transition hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300"
      >
        View details <ArrowRight aria-hidden="true" size={17} />
      </Link>
    </div>
  )
}

interface ProjectGroup {
  key: string
  name: string
  containers: ContainerView[]
}

function groupContainers(containers: ContainerView[]): ProjectGroup[] {
  const groups = new Map<string, ProjectGroup>()
  for (const container of containers) {
    const projectName = container.composeProject?.trim()
    const key = projectName ? `compose:${projectName}` : 'standalone'
    const existing = groups.get(key)
    if (existing) {
      existing.containers.push(container)
      continue
    }
    groups.set(key, {
      key,
      name: projectName ? formatProjectName(projectName) : 'Standalone',
      containers: [container],
    })
  }
  return [...groups.values()]
    .map((group) => ({ ...group, containers: [...group.containers].sort((left, right) => left.name.localeCompare(right.name)) }))
    .sort((left, right) => left.name.localeCompare(right.name))
}

function formatProjectName(project: string) {
  if (project.toLowerCase() === 'homeops') {
    return 'HomeOps'
  }
  return project
    .split(/[-_]+/)
    .filter(Boolean)
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join(' ')
}

function projectSummary(containers: ContainerView[]) {
  const running = containers.filter((container) => container.state === 'RUNNING').length
  return `${containers.length} container${containers.length === 1 ? '' : 's'} · ${running} running`
}

function projectStatus(containers: ContainerView[], stale: boolean) {
  if (stale) {
    return 'STALE'
  }
  if (containers.some((container) => container.health === 'UNHEALTHY')) {
    return 'UNHEALTHY'
  }
  if (containers.some((container) => container.state !== 'RUNNING')) {
    return 'NOT RUNNING'
  }
  if (containers.some((container) => container.health === 'UNKNOWN')) {
    return 'UNKNOWN'
  }
  if (containers.some((container) => container.health === 'STARTING')) {
    return 'STARTING'
  }
  return 'RUNNING'
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
