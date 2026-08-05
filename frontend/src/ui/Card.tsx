import type { ReactNode } from 'react'
import { cn } from '../utils/cn'

interface CardProps {
  children: ReactNode
  className?: string
}

export function Card({ children, className }: CardProps) {
  return (
    <section
      className={cn(
        'rounded-2xl border border-white/10 bg-slate-900/70 p-4 shadow-lg shadow-black/10',
        className,
      )}
    >
      {children}
    </section>
  )
}

