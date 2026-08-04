import { AlertTriangle, CheckCircle2, CircleOff, Clock3 } from 'lucide-react'
import { cn } from '../utils/cn'

interface StatusBadgeProps {
  status: string
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const normalized = status.toUpperCase()
  const isHealthy = normalized === 'CONNECTED' || normalized === 'HEALTHY' || normalized === 'RUNNING'
  const isWarning = normalized === 'STALE' || normalized === 'STARTING' || normalized === 'RESTARTING'
  const isOffline = normalized === 'OFFLINE' || normalized === 'NONE'
  const Icon = isHealthy
    ? CheckCircle2
    : isWarning
      ? Clock3
      : isOffline
        ? CircleOff
        : AlertTriangle

  return (
    <span
      className={cn(
        'inline-flex min-h-7 items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold',
        isHealthy && 'border-emerald-400/30 bg-emerald-400/10 text-emerald-200',
        isWarning && 'border-amber-400/30 bg-amber-400/10 text-amber-200',
        isOffline && 'border-slate-500/30 bg-slate-500/10 text-slate-300',
        !isHealthy && !isWarning && !isOffline && 'border-rose-400/30 bg-rose-400/10 text-rose-200',
      )}
    >
      <Icon aria-hidden="true" size={14} />
      {normalized}
    </span>
  )
}

