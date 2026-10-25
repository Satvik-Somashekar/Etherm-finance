import type { ConsentArtifact, FipAccount, FiType, Insight, Transaction } from '../types/fi'
import type { FipCandidate } from '../data/mock'
import { currentCustomerId } from './session'
import { ApiError } from './errors'
import { DEMO, demoCall } from './demo'

/**
 * Typed client for the Etherm backend, reached through the Vite proxy (see vite.config.ts).
 * Every figure the UI shows from here was computed by the Java services.
 */


/** Display names for the simulated providers in the synthetic dataset. */
const FIP_NAMES: Record<string, string> = {
  'ETHERM-BANK-FIP': 'Etherm Demo Bank',
  'ETHERM-RTA-FIP': 'Demo RTA (Mutual Funds)',
  'ETHERM-INSURER-FIP': 'Demo Insurer',
  'ETHERM-EPFO-FIP': 'EPFO (Demo)',
  'ETHERM-DEPOSITORY-FIP': 'Demo Depository',
}

export const fipName = (fipId: string) => FIP_NAMES[fipId] ?? fipId

export { ApiError } from './errors'

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  if (DEMO) return demoCall<T>(path, init)
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!response.ok) {
    let detail = response.statusText
    try {
      detail = ((await response.json()) as { detail?: string }).detail ?? detail
    } catch {
      // Body was not JSON; keep the status text.
    }
    throw new ApiError(response.status, detail)
  }
  return (await response.json()) as T
}

// ------------------------------------------------------------------ backend shapes

interface BackendConsent {
  consentId: string
  customerId: string
  status: ConsentArtifact['status'] | 'PAUSED'
  purposeCode: string
  purposeText: string
  fiTypes: FiType[]
  consentStart: string
  consentExpiry: string
  dataFrom: string
  dataTo: string
  fetchType: 'ONETIME' | 'PERIODIC'
}

interface AccountView {
  account: {
    accountId: string
    fipId: string
    fiType: FiType
    maskedAccNumber: string
    label: string
    currentValue: number
    valueAsOf: string
    recordCount: number
  }
  consent: BackendConsent
  fetchedAt: string
}

interface DiscoveredAccount {
  fipId: string
  fiType: FiType
  linkRefNumber: string
  maskedAccNumber: string
}

export interface Figure {
  key: string
  label: string
  value: number
  unit: 'INR' | 'COUNT' | 'PERCENT'
  recordIds: string[]
}

export interface BreakdownItem {
  label: string
  value: number
  recordIds: string[]
}

export interface Citation {
  recordId: string
  accountId: string
  fipId: string
  fiType: FiType
  date: string
  amount: number
  description: string
  counterparty: string | null
}

export interface QueryResult {
  question: string
  answer: string
  phrasedBy: string
  intent: string
  period: { from: string; to: string; label: string } | null
  figures: Figure[]
  breakdown: BreakdownItem[]
  citations: Citation[]
  grounding: {
    consentIds: string[]
    recordsVisible: number
    recordsMatched: number
    recordsCited: number
    asOf: string
  }
}

interface BackendOverview {
  asOf: string | null
  history: { month: string; date: string; value: number; records: number }[]
  activity: {
    recordId: string
    fipId: string
    fiType: FiType
    date: string
    amount: number
    direction: 'CREDIT' | 'DEBIT' | null
    category: string
    description: string
    counterparty: string | null
  }[]
  insights: QueryResult[]
}

/** What the Dashboard shows beyond balances, all computed by the Java services. */
export interface Overview {
  asOf: string | null
  /** Month-end net worth, rebuilt from transactions at today's prices. */
  series: { month: string; value: number }[]
  activity: Transaction[]
  insights: Insight[]
}

// ------------------------------------------------------------------------- mapping

function toConsent(c: BackendConsent): ConsentArtifact {
  return {
    consentId: c.consentId,
    // The UI has no PAUSED state; a paused consent grants no access, like a pending one.
    status: c.status === 'PAUSED' ? 'PENDING' : c.status,
    purposeCode: c.purposeCode,
    purposeText: c.purposeText,
    consentStart: c.consentStart,
    consentExpiry: c.consentExpiry,
    fetchType: c.fetchType,
    frequency: c.fetchType === 'PERIODIC' ? { unit: 'DAY', value: 1 } : undefined,
    fiTypes: c.fiTypes,
  }
}

function toAccount(view: AccountView): FipAccount {
  return {
    id: view.account.accountId,
    fipName: fipName(view.account.fipId),
    fipId: view.account.fipId,
    fiType: view.account.fiType,
    maskedAccountNumber: view.account.maskedAccNumber,
    balance: Number(view.account.currentValue),
    lastSyncedAt: view.fetchedAt,
    consent: toConsent(view.consent),
  }
}

// --------------------------------------------------------------------------- calls

/** Accounts shared through the customer's active consents, valued by the Java services. */
export async function loadLinkedAccounts(): Promise<FipAccount[]> {
  const views = await call<AccountView[]>(
    `/svc/normalization/api/customers/${encodeURIComponent(currentCustomerId())}/accounts`,
  )
  return views.map(toAccount)
}

/** Providers holding the customer's accounts (account discovery: masked numbers only). */
export async function discoverProviders(): Promise<FipCandidate[]> {
  const found = await call<DiscoveredAccount[]>(
    `/svc/ingestion/api/customers/${encodeURIComponent(currentCustomerId())}/fips`,
  )
  return found.map((a) => ({
    fipId: a.fipId,
    fipName: fipName(a.fipId),
    fiType: a.fiType,
    maskedAccountNumber: a.maskedAccNumber,
  }))
}

/** Creates a consent for one provider, then approves it, which runs the FI fetch. */
export async function linkProvider(fipId: string, fiTypes: FiType[]): Promise<void> {
  const today = new Date()
  const from = new Date(Date.UTC(today.getUTCFullYear() - 1, today.getUTCMonth(), 1))
  const consent = await call<BackendConsent>('/svc/ingestion/api/consents', {
    method: 'POST',
    body: JSON.stringify({
      customerId: currentCustomerId(),
      fiTypes,
      fipIds: [fipId],
      purposeCode: '101',
      purposeText: 'Wealth management service',
      dataFrom: from.toISOString().slice(0, 10),
      dataTo: today.toISOString().slice(0, 10),
      validForDays: 365,
    }),
  })
  await call(`/svc/ingestion/api/consents/${consent.consentId}/approve`, { method: 'POST' })
}

export async function revokeConsent(consentId: string): Promise<void> {
  await call(`/svc/ingestion/api/consents/${consentId}/revoke`, { method: 'POST' })
}

const MONTH = new Intl.DateTimeFormat('en-IN', { month: 'short', timeZone: 'UTC' })

/** Net-worth history, recent activity and insights for the Dashboard. */
export async function loadOverview(): Promise<Overview> {
  const o = await call<BackendOverview>(
    `/svc/rag/api/customers/${encodeURIComponent(currentCustomerId())}/overview`,
  )
  return {
    asOf: o.asOf,
    series: o.history.map((p) => ({
      month: MONTH.format(new Date(`${p.month}-01T00:00:00Z`)),
      value: Number(p.value),
    })),
    activity: o.activity.map((a) => ({
      id: a.recordId,
      accountId: a.recordId.split(':')[0],
      narration: a.description,
      counterparty: a.counterparty ?? fipName(a.fipId),
      amount: Number(a.amount),
      // Investment and policy records carry no direction; money flowing into them leaves the bank.
      direction: a.direction ?? 'DEBIT',
      valueDate: a.date,
      fiType: a.fiType,
    })),
    insights: o.insights.map((r, index) => ({
      id: `insight-${index}`,
      title: r.answer,
      body: '',
      severity: 'info',
      sources: r.citations.map((c) => ({
        recordId: c.recordId,
        fipName: fipName(c.fipId),
        fiType: c.fiType,
        label: c.description,
      })),
    })),
  }
}

export async function ask(question: string): Promise<QueryResult> {
  return call<QueryResult>('/svc/rag/api/query', {
    method: 'POST',
    body: JSON.stringify({ customerId: currentCustomerId(), question }),
  })
}
