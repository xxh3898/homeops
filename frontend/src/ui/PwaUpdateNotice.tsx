interface PwaUpdateNoticeProps {
  needRefresh: boolean
  offlineReady: boolean
  onDismiss: () => void
  onUpdate: () => void
}

export function PwaUpdateNotice({
  needRefresh,
  offlineReady,
  onDismiss,
  onUpdate,
}: PwaUpdateNoticeProps) {
  if (!needRefresh && !offlineReady) {
    return null
  }

  return (
    <div className="fixed inset-x-4 bottom-24 z-50 mx-auto max-w-md rounded-2xl border border-teal-300/30 bg-slate-900 p-4 shadow-2xl">
      <p className="text-sm font-medium">
        {needRefresh ? 'A new HomeOps version is ready.' : 'HomeOps app shell is ready for offline launch.'}
      </p>
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          className="min-h-11 rounded-xl px-4 text-sm text-slate-300"
          onClick={onDismiss}
        >
          Dismiss
        </button>
        {needRefresh && (
          <button
            type="button"
            className="min-h-11 rounded-xl bg-teal-300 px-4 text-sm font-semibold text-slate-950"
            onClick={onUpdate}
          >
            Update
          </button>
        )}
      </div>
    </div>
  )
}
