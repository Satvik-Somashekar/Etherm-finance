import { NavLink } from 'react-router-dom'
import { Icon } from './Icon'
import { Logo } from './Logo'
import { useAuth } from '../state/AuthContext'

const NAV = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/accounts', label: 'Accounts', end: false },
  { to: '/oracle', label: 'AI Oracle', end: false },
  { to: '/consent', label: 'Consent', end: false },
]

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `text-label-md transition-all ${
    isActive ? 'font-bold text-primary' : 'text-on-surface-variant hover:text-on-surface'
  }`

export function Header() {
  const { user, logout } = useAuth()
  if (!user) return null
  return (
    <header className="fixed top-0 z-50 w-full border-b border-white/5 bg-surface/40 shadow-[0_1px_20px_rgba(0,0,0,0.2)] backdrop-blur-2xl">
      <div className="flex h-20 w-full items-center justify-between px-margin-mobile lg:px-margin-desktop">
        <div className="flex items-center gap-3">
          <Logo />
          <span className="text-headline-sm tracking-tight text-on-surface">Etherm Finance</span>
        </div>

        <nav className="hidden items-center gap-10 lg:flex">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={linkClass}>
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="flex items-center gap-4">
          <button
            type="button"
            className="relative rounded-full p-2 text-on-surface-variant transition-colors hover:text-on-surface"
            aria-label="Notifications"
          >
            <Icon name="notifications" />
            <span className="absolute right-2 top-2 h-1.5 w-1.5 rounded-full bg-primary shadow-glow-sm" />
          </button>
          <div className="flex items-center gap-3 border-l border-outline-variant/30 pl-4">
            <div className="hidden text-right sm:block">
              <div className="text-label-sm text-on-surface">{user.name}</div>
              <div className="text-label-sm text-on-surface-variant opacity-60">Demo customer</div>
            </div>
            <div className="flex h-9 w-9 items-center justify-center rounded-full border border-primary/20 bg-surface-container-high text-label-sm text-primary">
              {user.name
                .split(' ')
                .map((part) => part[0])
                .join('')}
            </div>
            <button
              type="button"
              onClick={logout}
              className="rounded-full px-3 py-1.5 text-label-sm text-on-surface-variant transition-colors hover:text-on-surface"
            >
              Sign out
            </button>
          </div>
        </div>
      </div>

      {/* Mobile: the desktop nav collapses to a scrollable pill row. */}
      <nav className="flex gap-2 overflow-x-auto border-t border-white/5 px-margin-mobile py-3 lg:hidden">
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              `shrink-0 rounded-full px-4 py-2 text-label-md transition-colors ${
                isActive
                  ? 'bg-primary text-on-primary'
                  : 'bg-surface-container text-on-surface-variant'
              }`
            }
          >
            {item.label}
          </NavLink>
        ))}
      </nav>
    </header>
  )
}
