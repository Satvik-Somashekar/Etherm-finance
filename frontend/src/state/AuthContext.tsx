import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { loadSession, saveSession, SIGNED_OUT_EVENT, type DemoUser } from '../lib/session'
import { signIn } from '../lib/api'

interface AuthValue {
  user: DemoUser | null
  /** Asks the services for a token; rejects with the reason when the passcode is wrong. */
  login: (user: DemoUser, passcode: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<DemoUser | null>(loadSession)

  const login = useCallback(async (next: DemoUser, passcode: string) => {
    const session = await signIn(next.id, passcode)
    saveSession(next, session.token, session.expiresAt)
    setUser(next)
  }, [])
  const logout = useCallback(() => {
    saveSession(null)
    setUser(null)
  }, [])

  // The services refused the token (it expired, or they restarted with another key): sign in again.
  useEffect(() => {
    window.addEventListener(SIGNED_OUT_EVENT, logout)
    return () => window.removeEventListener(SIGNED_OUT_EVENT, logout)
  }, [logout])

  const value = useMemo(() => ({ user, login, logout }), [user, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
