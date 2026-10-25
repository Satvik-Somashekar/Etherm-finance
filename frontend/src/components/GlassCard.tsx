import type { ReactNode } from 'react'

interface GlassCardProps {
  children: ReactNode
  className?: string
  /** Lifts on hover — for cards that navigate somewhere. */
  interactive?: boolean
}

export function GlassCard({ children, className = '', interactive = false }: GlassCardProps) {
  return (
    <div
      className={`glass relative overflow-hidden ${
        interactive
          ? 'group cursor-pointer transition-all duration-300 hover:-translate-y-1 hover:border-primary/20'
          : ''
      } ${className}`}
    >
      {/* Top-edge highlight: the "light" the glass catches. */}
      <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent" />
      {children}
    </div>
  )
}
