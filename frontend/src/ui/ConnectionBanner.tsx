import { WifiOff } from 'lucide-react'
import { useOnlineStatus } from '../hooks/useOnlineStatus'

export function ConnectionBanner() {
  const online = useOnlineStatus()
  if (online) {
    return null
  }
  return (
    <div className="sticky top-0 z-40 flex min-h-11 items-center justify-center gap-2 bg-amber-400 px-4 text-sm font-semibold text-slate-950">
      <WifiOff aria-hidden="true" size={18} />
      Offline. Status may be stale and control is disabled.
    </div>
  )
}

