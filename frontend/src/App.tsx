import { Activity, Boxes, House, Settings } from 'lucide-react'
import { NavLink, Route, Routes } from 'react-router'
import { ContainersPage } from './pages/ContainersPage'
import { ContainerDetailPage } from './pages/ContainerDetailPage'
import { OverviewPage } from './pages/OverviewPage'
import { PlaceholderPage } from './pages/PlaceholderPage'
import { ActivityPage } from './pages/ActivityPage'
import { ConnectionBanner } from './ui/ConnectionBanner'
import { PwaUpdatePrompt } from './ui/PwaUpdatePrompt'
import { cn } from './utils/cn'

const navigation = [
  { to: '/', label: 'Overview', icon: House, end: true },
  { to: '/containers', label: 'Containers', icon: Boxes },
  { to: '/activity', label: 'Activity', icon: Activity },
  { to: '/settings', label: 'Settings', icon: Settings },
]

export function App() {
  return (
    <div className="min-h-dvh bg-slate-950 text-slate-100">
      <ConnectionBanner />
      <div className="mx-auto min-h-dvh max-w-5xl pb-[calc(5.5rem+env(safe-area-inset-bottom))]">
        <header className="px-5 pb-4 pt-[calc(1.25rem+env(safe-area-inset-top))]">
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-teal-300">
            Private operations
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">HomeOps</h1>
        </header>

        <main className="px-4 sm:px-5">
          <Routes>
            <Route path="/" element={<OverviewPage />} />
            <Route path="/containers" element={<ContainersPage />} />
            <Route path="/containers/:id" element={<ContainerDetailPage />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route
              path="/settings"
              element={<PlaceholderPage title="Settings" description="Security-sensitive settings remain server-managed during the read-only milestone." />}
            />
          </Routes>
        </main>
      </div>

      <nav className="fixed inset-x-0 bottom-0 z-30 border-t border-white/10 bg-slate-950/95 pb-[env(safe-area-inset-bottom)] backdrop-blur">
        <div className="mx-auto grid max-w-lg grid-cols-4 gap-1 px-2 py-2">
          {navigation.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex min-h-14 flex-col items-center justify-center gap-1 rounded-xl text-[11px] font-medium text-slate-400 transition',
                  isActive && 'bg-teal-400/10 text-teal-200',
                )
              }
            >
              <Icon aria-hidden="true" size={20} />
              {label}
            </NavLink>
          ))}
        </div>
      </nav>
      <PwaUpdatePrompt />
    </div>
  )
}
