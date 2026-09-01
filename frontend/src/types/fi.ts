/**
 * Types mirror the Account Aggregator FI (Financial Information) schema shapes
 * described in PROJECT_CONTEXT.md, so that swapping mock data for a live AA
 * response later is a data-source change rather than a UI rewrite.
 */

export type FiType =
  | 'DEPOSIT'
  | 'MUTUAL_FUNDS'
  | 'INSURANCE_POLICIES'
  | 'EPF'
  | 'EQUITIES'

export type ConsentStatus = 'ACTIVE' | 'PENDING' | 'EXPIRED' | 'REVOKED'

/** AA consent artifact — time-bound and purpose-bound, per the RBI framework. */
export interface ConsentArtifact {
  consentId: string
  status: ConsentStatus
  /** Purpose code from the AA purpose taxonomy, e.g. "101" — wealth management. */
  purposeCode: string
  purposeText: string
  consentStart: string
  consentExpiry: string
  fetchType: 'ONETIME' | 'PERIODIC'
  frequency?: { unit: 'HOUR' | 'DAY' | 'MONTH'; value: number }
  fiTypes: FiType[]
}

/** One account held at a Financial Information Provider. */
export interface FipAccount {
  id: string
  /** Financial Information Provider, e.g. "HDFC Bank". */
  fipName: string
  fipId: string
  fiType: FiType
  /** Masked per AA rules — the full number is never surfaced to the UI. */
  maskedAccountNumber: string
  /** Current value in INR. */
  balance: number
  lastSyncedAt: string | null
  consent: ConsentArtifact
}

export type TxnDirection = 'CREDIT' | 'DEBIT'

export interface Transaction {
  id: string
  accountId: string
  narration: string
  counterparty: string
  amount: number
  direction: TxnDirection
  valueDate: string
  fiType: FiType
}

/** A record the answer engine actually read, surfaced for source traceability. */
export interface SourceRef {
  recordId: string
  fipName: string
  fiType: FiType
  label: string
}

export interface Insight {
  id: string
  title: string
  body: string
  severity: 'info' | 'action' | 'warning'
  sources: SourceRef[]
}

export const FI_TYPE_LABEL: Record<FiType, string> = {
  DEPOSIT: 'Deposits',
  MUTUAL_FUNDS: 'Mutual Funds',
  INSURANCE_POLICIES: 'Insurance',
  EPF: 'EPF',
  EQUITIES: 'Equities',
}

export const FI_TYPE_ICON: Record<FiType, string> = {
  DEPOSIT: 'account_balance',
  MUTUAL_FUNDS: 'donut_small',
  INSURANCE_POLICIES: 'health_and_safety',
  EPF: 'savings',
  EQUITIES: 'trending_up',
}
