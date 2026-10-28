/**
 * Who is signed in. The five synthetic customers are listed on the sign-in page; signing in as one sends the
 * customer id and the demo passcode to ingestion, which returns a signed token that every service checks.
 * The services then answer for that customer only.
 */

export interface DemoUser {
  /** The customer id the backend knows this person by. */
  id: string
  name: string
  email: string
}

export const DEMO_USERS: DemoUser[] = [
  { id: 'user001@etherm-aa', name: 'Aarav Iyer', email: 'aarav.iyer@example.com' },
  { id: 'user002@etherm-aa', name: 'Diya Patel', email: 'diya.patel@example.com' },
  { id: 'user003@etherm-aa', name: 'Ishaan Nair', email: 'ishaan.nair@example.com' },
  { id: 'user004@etherm-aa', name: 'Meera Rao', email: 'meera.rao@example.com' },
  { id: 'user005@etherm-aa', name: 'Kabir Menon', email: 'kabir.menon@example.com' },
]

const STORAGE_KEY = 'etherm.session.v2'

/**
 * What is kept between page loads: who is signed in and the token the services issued for them. It lives in
 * sessionStorage, so it ends when the tab closes, and the token itself expires (an hour by default).
 * A null token means the services were not reachable at sign-in and the app is showing mock data.
 */
interface StoredSession {
  id: string
  token: string | null
  expiresAt: string | null
}

function read(): StoredSession | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const stored = JSON.parse(raw) as StoredSession
    if (stored.expiresAt && Date.parse(stored.expiresAt) <= Date.now()) return null
    return stored
  } catch {
    return null
  }
}

/** The signed-in user, or null. Storage can be blocked or hold junk, so both mean signed out. */
export function loadSession(): DemoUser | null {
  const stored = read()
  return DEMO_USERS.find((u) => u.id === stored?.id) ?? null
}

export function saveSession(user: DemoUser | null, token: string | null = null, expiresAt: string | null = null): void {
  try {
    window.localStorage.removeItem('etherm.session.v1')
    if (user) window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ id: user.id, token, expiresAt }))
    else window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage unavailable: the session lasts until the page is closed.
  }
}

/** Read by the API client on every call, so a switch of user never reuses the old id. */
export const currentCustomerId = (): string => loadSession()?.id ?? DEMO_USERS[0].id

/** The bearer token for the signed-in customer, if the services issued one. */
export const currentToken = (): string | null => read()?.token ?? null

/** Fired when the services refuse the token (expired or invalid), so the app can return to sign-in. */
export const SIGNED_OUT_EVENT = 'etherm:signed-out'
