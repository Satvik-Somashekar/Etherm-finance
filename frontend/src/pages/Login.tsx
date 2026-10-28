import { useState } from 'react'
import { GlassCard } from '../components/GlassCard'
import { Logo } from '../components/Logo'
import { DEMO_USERS, type DemoUser } from '../lib/session'
import { useAuth } from '../state/AuthContext'
import { DEMO } from '../lib/demo'

// Presentation build: the shared demo passcode is sent for you, so choosing a customer is all it takes.
// The services still check it; set VITE_DEMO_PASSCODE if they were started with ETHERM_DEMO_PASSCODE.
const PASSCODE = import.meta.env.VITE_DEMO_PASSCODE ?? 'etherm-demo'

export function Login() {
  const { login } = useAuth()
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function choose(user: DemoUser) {
    setBusy(user.id)
    setError(null)
    try {
      await login(user, PASSCODE)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Sign-in failed.')
    } finally {
      setBusy(null)
    }
  }

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
          {error && (
            <p role="alert" className="mb-4 rounded-xl border border-error/30 bg-error/10 p-3 text-label-md text-error">
              {error}
            </p>
          )}
          <ul className="flex flex-col gap-3">
            {DEMO_USERS.map((user) => (
              <li key={user.id}>
                <button
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void choose(user)}
                  className="flex w-full items-center gap-4 rounded-xl border border-outline-variant/30 bg-surface-container/60 p-4 text-left transition-colors hover:border-primary/40 disabled:opacity-60"
                >
                  <span className="flex h-10 w-10 items-center justify-center rounded-full border border-primary/20 bg-surface-container-high text-label-sm text-primary">
                    {user.name
                      .split(' ')
                      .map((part) => part[0])
                      .join('')}
                  </span>
                  <span>
                    <span className="block text-label-md text-on-surface">{user.name}</span>
                    <span className="block text-label-sm text-on-surface-variant">
                      {busy === user.id ? 'Signing in…' : user.email}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <p className="mt-6 text-label-sm text-on-surface-variant opacity-70">
            {DEMO
              ? 'This is demo mode: the data and answers were recorded from the real services, so no passcode is needed.'
              : 'The demo passcode is sent for you. The services give back a token that only works for the customer you choose.'}
          </p>
        </GlassCard>
      </div>
    </div>
  )
}
