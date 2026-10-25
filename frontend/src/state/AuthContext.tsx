import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { loadSession, saveSession, type DemoUser } from '../lib/session'

interface AuthValue {
  user: DemoUser | null
  login: (user: DemoUser) => void
  logout: () => void
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<DemoUser | null>(loadSession)

  const login = useCallback((next: DemoUser) => {
    saveSession(next)
    setUser(next)
  }, [])
  const logout = useCallback(() => {
    saveSession(null)
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, login, logout }), [user, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
