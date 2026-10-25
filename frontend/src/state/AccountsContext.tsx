import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import {
  accounts as seedAccounts,
  fipCatalogue,
  SEED_BALANCE,
  type FipCandidate,
} from '../data/mock'
import type { ConsentArtifact, FipAccount, FiType } from '../types/fi'
import { loadAccounts, saveAccounts } from '../lib/accountsStore'
import { discoverProviders, linkProvider, loadLinkedAccounts, revokeConsent } from '../lib/api'

/** How long a freshly approved consent spends "syncing" before data lands. */
const SYNC_MS = 1800

/** Opening balance for a newly linked account, jittered so it is not obviously canned. */
function openingBalance(fiType: FiType): number {
  return Math.round(SEED_BALANCE[fiType] * (0.85 + Math.random() * 0.3))
}

/** The seeded providers, expressed as link candidates so they can be re-added. */
const seedCandidates: FipCandidate[] = seedAccounts.map((account) => ({
  fipId: account.fipId,
  fipName: account.fipName,
  fiType: account.fiType,
  maskedAccountNumber: account.maskedAccountNumber,
}))

/** Everything linkable: the catalogue plus every seeded provider, deduped by FIP. */
const allCandidates: FipCandidate[] = [...seedCandidates, ...fipCatalogue].filter(
  (candidate, index, list) => list.findIndex((c) => c.fipId === candidate.fipId) === index,
)

export interface LinkRequest {
  /** Set when approving an account already present in the list. */
  accountId?: string
  /** Set when linking a provider from the candidate list. */
  candidate?: FipCandidate
  consent: ConsentArtifact
}

/**
 * Where account data comes from: the Java services when they answer, otherwise the bundled mock
 * data so the UI still runs on its own.
 */
export type DataSource = 'loading' | 'live' | 'mock'

interface AccountsValue {
  source: DataSource
  /** Set when a live call failed; cleared by the next successful one. */
  error: string | null
  accounts: FipAccount[]
  /** Ids currently mid-fetch; their balances are not meaningful yet. */
  syncingIds: string[]
  /** Sum of balances across accounts with an active consent. */
  netWorth: number
  /** Providers not currently linked — what the connect flow can offer. */
  availableFips: FipCandidate[]
  link: (request: LinkRequest) => void
  remove: (id: string) => void
}

const AccountsContext = createContext<AccountsValue | null>(null)

export function AccountsProvider({ children }: { children: ReactNode }) {
  const [accounts, setAccounts] = useState<FipAccount[]>(loadAccounts)
  const [syncingIds, setSyncingIds] = useState<string[]>([])
  const [source, setSource] = useState<DataSource>('loading')
  const [error, setError] = useState<string | null>(null)
  const [liveCandidates, setLiveCandidates] = useState<FipCandidate[]>([])

  const refreshLive = useCallback(async () => {
    const [linked, discovered] = await Promise.all([loadLinkedAccounts(), discoverProviders()])
    setAccounts(linked)
    setLiveCandidates(discovered)
    setError(null)
  }, [])

  // Prefer the backend; fall back to the mock data if it is not running.
  useEffect(() => {
    let cancelled = false
    refreshLive()
      .then(() => !cancelled && setSource('live'))
      .catch(() => !cancelled && setSource('mock'))
    return () => {
      cancelled = true
    }
  }, [refreshLive])

  const timers = useRef<number[]>([])
  useEffect(() => () => timers.current.forEach(window.clearTimeout), [])

  useEffect(() => {
    if (source === 'mock') saveAccounts(accounts)
  }, [accounts, source])

  /** Lands the first fetch for an account after SYNC_MS. Returns its timer id. */
  const finishSync = useCallback((id: string, fiType: FiType): number => {
    return window.setTimeout(() => {
      setAccounts((current) =>
        current.map((account) =>
          account.id === id
            ? {
                ...account,
                balance: openingBalance(fiType),
                lastSyncedAt: new Date().toISOString(),
              }
            : account,
        ),
      )
      setSyncingIds((current) => current.filter((value) => value !== id))
    }, SYNC_MS)
  }, [])

  // A reload during the simulated fetch leaves an approved account with no data.
  // Resume those once on mount so nothing is stranded mid-sync.
  useEffect(() => {
    const stranded = accounts.filter(
      (a) => a.consent.status === 'ACTIVE' && a.lastSyncedAt === null,
    )
    if (stranded.length === 0) return
    setSyncingIds((current) => [...current, ...stranded.map((a) => a.id)])
    for (const account of stranded) {
      timers.current.push(finishSync(account.id, account.fiType))
    }
    // Mount-only: the link path starts its own timers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * Consent approved: the account goes active immediately but with no data yet,
   * then flips to synced once the simulated first fetch completes.
   */
  const link = useCallback(
    (request: LinkRequest) => {
      if (source === 'live') {
        const fipId = request.candidate?.fipId ?? accounts.find((a) => a.id === request.accountId)?.fipId
        if (!fipId) return
        const fiTypes = [
          ...new Set(liveCandidates.filter((c) => c.fipId === fipId).map((c) => c.fiType)),
        ]
        setSyncingIds((current) => [...current, fipId])
        linkProvider(fipId, fiTypes)
          .then(refreshLive)
          .catch((e: Error) => setError(e.message))
          .finally(() => setSyncingIds((current) => current.filter((v) => v !== fipId)))
        return
      }
      const existing = request.accountId
      const candidate = request.candidate
      const id = existing ?? `acc-${candidate!.fipId.toLowerCase()}-${Date.now()}`

      let fiType: FiType | undefined = candidate?.fiType

      setAccounts((current) => {
        if (existing) {
          const target = current.find((account) => account.id === existing)
          fiType = target?.fiType ?? fiType
          return current.map((account) =>
            account.id === existing
              ? { ...account, consent: request.consent, balance: 0, lastSyncedAt: null }
              : account,
          )
        }
        return [
          ...current,
          {
            id,
            fipName: candidate!.fipName,
            fipId: candidate!.fipId,
            fiType: candidate!.fiType,
            maskedAccountNumber: candidate!.maskedAccountNumber,
            balance: 0,
            lastSyncedAt: null,
            consent: request.consent,
          },
        ]
      })

      setSyncingIds((current) => [...current, id])
      if (fiType) timers.current.push(finishSync(id, fiType))
    },
    [finishSync, source, accounts, liveCandidates, refreshLive],
  )

  /** Revokes consent and unlinks. Totals fall automatically — they are derived. */
  const remove = useCallback((id: string) => {
    if (source === 'live') {
      const consentId = accounts.find((a) => a.id === id)?.consent.consentId
      if (!consentId) return
      revokeConsent(consentId)
        .then(refreshLive)
        .catch((e: Error) => setError(e.message))
      return
    }
    setAccounts((current) => current.filter((account) => account.id !== id))
    setSyncingIds((current) => current.filter((value) => value !== id))
  }, [source, accounts, refreshLive])

  const netWorth = useMemo(
    () =>
      accounts
        .filter((account) => account.consent.status === 'ACTIVE')
        .reduce((sum, account) => sum + account.balance, 0),
    [accounts],
  )

  const availableFips = useMemo(() => {
    const linked = accounts.map((account) => account.fipId)
    const pool = source === 'live' ? liveCandidates : allCandidates
    return pool.filter(
      (candidate, index, list) =>
        !linked.includes(candidate.fipId) &&
        list.findIndex((c) => c.fipId === candidate.fipId) === index,
    )
  }, [accounts, source, liveCandidates])

  const value = useMemo(
    () => ({ source, error, accounts, syncingIds, netWorth, availableFips, link, remove }),
    [source, error, accounts, syncingIds, netWorth, availableFips, link, remove],
  )

  return <AccountsContext.Provider value={value}>{children}</AccountsContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAccounts(): AccountsValue {
  const value = useContext(AccountsContext)
  if (!value) throw new Error('useAccounts must be used inside an AccountsProvider')
  return value
}
