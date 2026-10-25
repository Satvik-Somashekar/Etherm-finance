import { GlassCard } from '../components/GlassCard'
import { Logo } from '../components/Logo'
import { DEMO_USERS } from '../lib/session'
import { useAuth } from '../state/AuthContext'

export function Login() {
  const { login } = useAuth()
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-margin-mobile py-12">
      <div className="w-full max-w-xl">
        <div className="mb-8 flex items-center justify-center gap-3">
          <Logo />
          <span className="text-headline-sm tracking-tight text-on-surface">Etherm Finance</span>
        </div>
        <GlassCard className="p-8">
          <h1 className="mb-2 text-headline-md text-on-surface">Sign in</h1>
          <p className="mb-6 text-body-md text-on-surface-variant">
            Choose a demo customer. Each has their own accounts, consents and transactions. All of
            them are fictional.
          </p>
          <ul className="flex flex-col gap-3">
            {DEMO_USERS.map((user) => (
              <li key={user.id}>
                <button
                  type="button"
                  onClick={() => login(user)}
                  className="flex w-full items-center gap-4 rounded-xl border border-outline-variant/30 bg-surface-container/60 p-4 text-left transition-colors hover:border-primary/40"
                >
                  <span className="flex h-10 w-10 items-center justify-center rounded-full border border-primary/20 bg-surface-container-high text-label-sm text-primary">
                    {user.name
                      .split(' ')
                      .map((part) => part[0])
                      .join('')}
                  </span>
                  <span>
                    <span className="block text-label-md text-on-surface">{user.name}</span>
                    <span className="block text-label-sm text-on-surface-variant">{user.email}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <p className="mt-6 text-label-sm text-on-surface-variant opacity-70">
            Demo sign-in: no password is checked yet.
          </p>
        </GlassCard>
      </div>
    </div>
  )
}
