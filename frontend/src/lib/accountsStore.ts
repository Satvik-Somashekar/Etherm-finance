import { accounts as seedAccounts } from '../data/mock'
import { FI_TYPE_LABEL, type FipAccount } from '../types/fi'

/**
 * Persists the linked-accounts list so the connect-account flow survives a
 * reload. Versioned: bump the key if FipAccount changes shape, so stale entries
 * are dropped rather than half-read.
 */
const STORAGE_KEY = 'etherm.accounts.v1'

/** A fresh copy of the mock data — never a reference into the mock module. */
export function seed(): FipAccount[] {
  return seedAccounts.map((account) => ({ ...account, consent: { ...account.consent } }))
}

function isFipAccount(value: unknown): value is FipAccount {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<FipAccount>
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.fipId === 'string' &&
    typeof candidate.fipName === 'string' &&
    typeof candidate.maskedAccountNumber === 'string' &&
    typeof candidate.balance === 'number' &&
    (candidate.lastSyncedAt === null || typeof candidate.lastSyncedAt === 'string') &&
    typeof candidate.fiType === 'string' &&
    candidate.fiType in FI_TYPE_LABEL &&
    typeof candidate.consent === 'object' &&
    candidate.consent !== null &&
    typeof candidate.consent.consentId === 'string' &&
    typeof candidate.consent.status === 'string'
  )
}

/** Reads the stored list, falling back to the mock seed on anything unexpected. */
export function loadAccounts(): FipAccount[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return seed()
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed) || parsed.length === 0 || !parsed.every(isFipAccount)) {
      return seed()
    }
    return parsed
  } catch {
    // Private browsing, quota errors, corrupt JSON — fall back rather than crash.
    return seed()
  }
}

export function saveAccounts(list: FipAccount[]): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
  } catch {
    // Persistence is a convenience; failing to write must not break the UI.
  }
}

export function clearAccounts(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Ignore — see saveAccounts.
  }
}
