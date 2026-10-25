/**
 * Who is signed in. Step 1 of login is a demo sign-in: the five synthetic customers are listed on
 * the sign-in page and picking one starts a session. Nothing here is a credential check, and the
 * services still accept any customer id; server-side tokens come in a later step.
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

const STORAGE_KEY = 'etherm.session.v1'

/** The signed-in user, or null. Storage can be blocked or hold junk, so both mean signed out. */
export function loadSession(): DemoUser | null {
  try {
    const id = window.localStorage.getItem(STORAGE_KEY)
    return DEMO_USERS.find((u) => u.id === id) ?? null
  } catch {
    return null
  }
}

export function saveSession(user: DemoUser | null): void {
  try {
    if (user) window.localStorage.setItem(STORAGE_KEY, user.id)
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage unavailable: the session lasts until the page is closed.
  }
}

/** Read by the API client on every call, so a switch of user never reuses the old id. */
export const currentCustomerId = (): string => loadSession()?.id ?? DEMO_USERS[0].id
