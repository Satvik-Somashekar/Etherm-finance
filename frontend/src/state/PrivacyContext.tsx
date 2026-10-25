import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'

interface PrivacyValue {
  /** When on, every monetary figure in the app renders as a placeholder. */
  masked: boolean
  toggle: () => void
}

const PrivacyContext = createContext<PrivacyValue>({ masked: false, toggle: () => {} })

export function PrivacyProvider({ children }: { children: ReactNode }) {
  const [masked, setMasked] = useState(false)
  const value = useMemo(() => ({ masked, toggle: () => setMasked((m) => !m) }), [masked])
  return <PrivacyContext.Provider value={value}>{children}</PrivacyContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function usePrivacy() {
  return useContext(PrivacyContext)
}
