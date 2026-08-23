import { useInfiniteQuery } from '@tanstack/react-query'
import { Activity, Bot, Boxes, DatabaseBackup, GitCommitHorizontal, RefreshCw, TriangleAlert } from 'lucide-react'
import { getActivity, isAuthorizationError, isConnectionError } from '../api/client'
import type { ActivityEvent } from '../api/types'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePageVisible } from '../hooks/usePageVisible'
import { Card } from '../ui/Card'
import { formatTimestamp } from '../utils/format'

export function ActivityPage() {
  const visible = usePageVisible()
  const online = useOnlineStatus()
  const query = useInfiniteQuery({
    queryKey: ['activity'],
    queryFn: ({ pageParam, signal }) => getActivity(pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchInterval: visible ? 30_000 : false,
  })

  if (query.isPending) return <ActivitySkeleton />
  if (isAuthorizationError(query.error) || query.data === undefined) {
    return <ActivityError message={query.error?.message} onRetry={() => void query.refetch()} />
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
          onClick={() => void query.refetch()}
        >
          <RefreshCw aria-hidden="true" size={18} />
        </button>
      </div>

      {stale && <p className="rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">{staleMessage}</p>}
      <p className="text-xs text-slate-500">Last refreshed {formatTimestamp(query.data.pages[0].generatedAt)}</p>

      {items.length === 0 ? (
        <Card>
          <Activity aria-hidden="true" className="text-slate-500" size={22} />
          <p className="mt-3 font-medium">No activity recorded yet</p>
          <p className="mt-1 text-sm text-slate-400">Deployments, backup results, incidents, Agent changes, and container actions will appear here.</p>
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
    </section>
  )
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
