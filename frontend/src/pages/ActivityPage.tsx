import { useInfiniteQuery } from '@tanstack/react-query'
import { Activity, Bot, Boxes, DatabaseBackup, GitCommitHorizontal, RefreshCw, TriangleAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  getActivity,
  isAuthorizationError,
  isConnectionError,
  isInvalidActivityCursorError,
  shouldRetryActivityQuery,
} from '../api/client'
import type { ActivityEvent, ActivityTypeFilter } from '../api/types'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { formatTimestamp } from '../utils/format'

export function ActivityPage() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const [type, setType] = useState<ActivityTypeFilter>('ALL')
  const [chainGeneration, setChainGeneration] = useState(0)
  const [continuationInvalid, setContinuationInvalid] = useState(false)
  const query = useInfiniteQuery({
    queryKey: ['activity', type, chainGeneration],
    queryFn: ({ pageParam, signal }) => getActivity(type, pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: !continuationInvalid,
    retry: shouldRetryActivityQuery,
    refetchInterval: visible && !continuationInvalid ? 30_000 : false,
  })

  useEffect(() => {
    if (!continuationInvalid && query.data !== undefined && isInvalidActivityCursorError(query.error)) {
      setContinuationInvalid(true)
    }
  }, [continuationInvalid, query.data, query.error])

  const refresh = () => {
    if (continuationInvalid) {
      setContinuationInvalid(false)
      setChainGeneration((generation) => generation + 1)
      return
    }
    void query.refetch()
  }

  const changeType = (nextType: ActivityTypeFilter) => {
    if (nextType === type) return
    setContinuationInvalid(false)
    setChainGeneration((generation) => generation + 1)
    setType(nextType)
  }

  if (query.isPending) return <ActivitySkeleton />
  if (isAuthorizationError(query.error) || query.data === undefined) {
    const message = query.error instanceof Error ? query.error.message : undefined
    return <ActivityError message={message} onRetry={() => void query.refetch()} />
  }

  const items = query.data.pages.flatMap((page) => page.items)
  const stale = !online || query.isRefetchError
  const staleMessage = isConnectionError(query.error)
    ? query.error.message
    : 'Activity could not be refreshed. The timeline below may be out of date.'

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-sm text-slate-400">Operational history</p>
          <h2 className="mt-1 text-xl font-semibold">Activity</h2>
        </div>
        <button
          type="button"
          aria-label="Refresh activity"
          className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-xl bg-white/10"
          onClick={refresh}
        >
          <RefreshCw aria-hidden="true" size={18} />
        </button>
      </div>

      <label className="block" htmlFor="activity-type-filter">
        <span className="text-sm font-medium text-slate-300">Event type</span>
        <select
          id="activity-type-filter"
          className="mt-2 min-h-11 w-full rounded-xl border border-white/10 bg-slate-900 px-3 text-sm text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-300"
          value={type}
          onChange={(event) => changeType(event.target.value as ActivityTypeFilter)}
        >
          {activityTypeOptions.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </label>

      {continuationInvalid ? (
        <Card className="border-amber-400/30">
          <h3 className="font-semibold text-amber-100">Activity timeline changed</h3>
          <p className="mt-2 text-sm text-slate-400">
            Refresh to continue from a new first page. Older cached pages will not be reused.
          </p>
          <button
            type="button"
            className="mt-4 min-h-11 w-full rounded-xl bg-white/10 px-4 text-sm font-semibold sm:w-auto"
            onClick={refresh}
          >
            Refresh activity timeline
          </button>
        </Card>
      ) : (
        <>
          {stale && (
            <p className="rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">{staleMessage}</p>
          )}
          <p className="text-xs text-slate-500">
            Last refreshed {formatTimestamp(query.data.pages[0].generatedAt)}
          </p>

          {items.length === 0 ? (
            <Card>
              <Activity aria-hidden="true" className="text-slate-500" size={22} />
              <p className="mt-3 font-medium">{emptyState[type].title}</p>
              <p className="mt-1 text-sm text-slate-400">{emptyState[type].description}</p>
            </Card>
          ) : (
            <div className="space-y-3" aria-label="Activity timeline">
              {items.map((item) => <ActivityItem key={`${item.type}:${item.id}:${item.status}`} item={item} />)}
            </div>
          )}

          {query.hasNextPage && (
            <button
              type="button"
              className="min-h-11 w-full rounded-xl bg-white/10 px-4 text-sm font-semibold disabled:opacity-50"
              disabled={query.isFetchingNextPage}
              onClick={() => void query.fetchNextPage()}
            >
              {query.isFetchingNextPage ? 'Loading…' : 'Load older activity'}
            </button>
          )}
        </>
      )}
    </section>
  )
}

const activityTypeOptions: { value: ActivityTypeFilter; label: string }[] = [
  { value: 'ALL', label: 'All activity' },
  { value: 'DEPLOYMENT', label: 'Deployments' },
  { value: 'BACKUP', label: 'Backups' },
  { value: 'INCIDENT', label: 'Incidents' },
  { value: 'AGENT', label: 'Agent' },
  { value: 'CONTAINER_ACTION', label: 'Container actions' },
]

const emptyState: Record<ActivityTypeFilter, { title: string; description: string }> = {
  ALL: {
    title: 'No activity recorded yet',
    description: 'Deployments, backup results, incidents, Agent changes, and container actions will appear here.',
  },
  DEPLOYMENT: {
    title: 'No deployment activity recorded yet',
    description: 'Deployment events will appear here.',
  },
  BACKUP: {
    title: 'No backup activity recorded yet',
    description: 'Backup results will appear here.',
  },
  INCIDENT: {
    title: 'No incident activity recorded yet',
    description: 'Incident openings and recoveries will appear here.',
  },
  AGENT: {
    title: 'No Agent activity recorded yet',
    description: 'Agent lifecycle changes will appear here.',
  },
  CONTAINER_ACTION: {
    title: 'No container action activity recorded yet',
    description: 'Container control audit events will appear here.',
  },
}

function ActivityItem({ item }: { item: ActivityEvent }) {
  const Icon = item.type === 'DEPLOYMENT' ? GitCommitHorizontal
    : item.type === 'BACKUP' ? DatabaseBackup
      : item.type === 'INCIDENT' ? TriangleAlert
        : item.type === 'CONTAINER_ACTION' ? Boxes : Bot
  const tone = item.severity === 'CRITICAL' ? 'text-rose-300'
    : item.severity === 'WARNING' ? 'text-amber-300'
      : item.severity === 'RECOVERY' ? 'text-emerald-300' : 'text-teal-300'
  return (
    <Card className="flex gap-3">
      <Icon aria-hidden="true" className={`mt-0.5 shrink-0 ${tone}`} size={20} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <p className="break-words font-medium [overflow-wrap:anywhere]">{item.title}</p>
          <span className={`break-words text-xs font-semibold [overflow-wrap:anywhere] ${tone}`}>{item.status}</span>
        </div>
        <p className="mt-1 truncate text-sm text-slate-400">{item.context}</p>
        <p className="mt-2 text-xs text-slate-500">{formatTimestamp(item.occurredAt)}</p>
      </div>
    </Card>
  )
}

function ActivityError({ message, onRetry }: { message?: string; onRetry: () => void }) {
  return (
    <Card className="border-rose-400/30">
      <h2 className="font-semibold text-rose-200">Unable to load activity</h2>
      <p className="mt-2 text-sm text-slate-400">{message ?? 'No cached activity is available.'}</p>
      <button type="button" className="mt-4 min-h-11 rounded-xl bg-white/10 px-4 text-sm font-semibold" onClick={onRetry}>Retry</button>
    </Card>
  )
}

function ActivitySkeleton() {
  return <div className="space-y-3" aria-label="Loading activity">{[1, 2, 3].map((item) => <div key={item} className="h-28 animate-pulse rounded-2xl bg-white/5" />)}</div>
}
