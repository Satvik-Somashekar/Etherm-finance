import type {
  ConsentArtifact,
  FipAccount,
  FiType,
  Insight,
  SourceRef,
  Transaction,
} from '../types/fi'

/**
 * Mock AA data. Schema-accurate but synthetic — per PROJECT_CONTEXT.md, live AA
 * integration (Setu / Finvu / Anumati) is a later phase. Everything the UI shows
 * is derived from these records, never hardcoded into a component.
 */

const now = Date.now()
const iso = (msAgo: number) => new Date(now - msAgo).toISOString()
const isoAhead = (msAhead: number) => new Date(now + msAhead).toISOString()

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

function consent(
  id: string,
  fiTypes: FiType[],
  overrides: Partial<ConsentArtifact> = {},
): ConsentArtifact {
  return {
    consentId: id,
    status: 'ACTIVE',
    purposeCode: '101',
    purposeText: 'Wealth management service',
    consentStart: iso(120 * DAY),
    consentExpiry: isoAhead(245 * DAY),
    fetchType: 'PERIODIC',
    frequency: { unit: 'DAY', value: 4 },
    fiTypes,
    ...overrides,
  }
}

export const accounts: FipAccount[] = [
  {
    id: 'acc-hdfc-sav',
    fipName: 'HDFC Bank',
    fipId: 'HDFC-FIP',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX4921',
    balance: 842150,
    lastSyncedAt: iso(2 * MIN),
    consent: consent('CN-8f21-4a90', ['DEPOSIT']),
  },
  {
    id: 'acc-icici-sav',
    fipName: 'ICICI Bank',
    fipId: 'ICICI-FIP',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX1180',
    balance: 215400,
    lastSyncedAt: iso(6 * MIN),
    consent: consent('CN-2b74-1c05', ['DEPOSIT']),
  },
  {
    id: 'acc-cams-mf',
    fipName: 'CAMS',
    fipId: 'CAMS-FIP',
    fiType: 'MUTUAL_FUNDS',
    maskedAccountNumber: 'Folio ****8823',
    balance: 1486200,
    lastSyncedAt: iso(12 * MIN),
    consent: consent('CN-55e1-9d33', ['MUTUAL_FUNDS']),
  },
  {
    id: 'acc-kfintech-mf',
    fipName: 'KFintech',
    fipId: 'KFIN-FIP',
    fiType: 'MUTUAL_FUNDS',
    maskedAccountNumber: 'Folio ****3391',
    balance: 412750,
    lastSyncedAt: iso(18 * MIN),
    consent: consent('CN-9a02-77bf', ['MUTUAL_FUNDS']),
  },
  {
    id: 'acc-lic-ins',
    fipName: 'LIC of India',
    fipId: 'LIC-FIP',
    fiType: 'INSURANCE_POLICIES',
    maskedAccountNumber: 'Policy ****1102',
    balance: 650000,
    lastSyncedAt: iso(1 * HOUR),
    consent: consent('CN-31da-6e48', ['INSURANCE_POLICIES']),
  },
  {
    id: 'acc-epfo',
    fipName: 'EPFO',
    fipId: 'EPFO-FIP',
    fiType: 'EPF',
    maskedAccountNumber: 'UAN ****7745',
    balance: 918600,
    lastSyncedAt: iso(3 * HOUR),
    consent: consent('CN-6c88-02fa', ['EPF'], {
      fetchType: 'PERIODIC',
      frequency: { unit: 'MONTH', value: 1 },
    }),
  },
  {
    id: 'acc-cdsl-eq',
    fipName: 'CDSL — Zerodha',
    fipId: 'CDSL-FIP',
    fiType: 'EQUITIES',
    maskedAccountNumber: 'DP ****5567',
    balance: 574300,
    lastSyncedAt: iso(4 * MIN),
    consent: consent('CN-af10-3b71', ['EQUITIES']),
  },
  {
    id: 'acc-axis-pending',
    fipName: 'Axis Bank',
    fipId: 'AXIS-FIP',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX0067',
    balance: 0,
    lastSyncedAt: null,
    consent: consent('CN-pending-4412', ['DEPOSIT'], { status: 'PENDING' }),
  },
]

export const transactions: Transaction[] = [
  {
    id: 'txn-9081',
    accountId: 'acc-cams-mf',
    narration: 'SIP — Parag Parikh Flexi Cap',
    counterparty: 'CAMS · Folio ****8823',
    amount: 25000,
    direction: 'DEBIT',
    valueDate: iso(4 * HOUR),
    fiType: 'MUTUAL_FUNDS',
  },
  {
    id: 'txn-9074',
    accountId: 'acc-hdfc-sav',
    narration: 'Salary credit',
    counterparty: 'NEFT · Employer payroll',
    amount: 185000,
    direction: 'CREDIT',
    valueDate: iso(1 * DAY),
    fiType: 'DEPOSIT',
  },
  {
    id: 'txn-9066',
    accountId: 'acc-cdsl-eq',
    narration: 'Equity purchase — HDFC Bank Ltd',
    counterparty: 'Zerodha · DP ****5567',
    amount: 42000,
    direction: 'DEBIT',
    valueDate: iso(5 * DAY),
    fiType: 'EQUITIES',
  },
  {
    id: 'txn-9052',
    accountId: 'acc-lic-ins',
    narration: 'Premium — Jeevan Anand',
    counterparty: 'LIC of India · Policy ****1102',
    amount: 18400,
    direction: 'DEBIT',
    valueDate: iso(8 * DAY),
    fiType: 'INSURANCE_POLICIES',
  },
  {
    id: 'txn-9041',
    accountId: 'acc-epfo',
    narration: 'EPF contribution — employer share',
    counterparty: 'EPFO · UAN ****7745',
    amount: 7800,
    direction: 'CREDIT',
    valueDate: iso(12 * DAY),
    fiType: 'EPF',
  },
]

/** Trailing 12 months of aggregated net worth, in INR. */
export const netWorthSeries: { month: string; value: number }[] = [
  { month: 'Apr', value: 3_912_000 },
  { month: 'May', value: 3_988_000 },
  { month: 'Jun', value: 4_051_000 },
  { month: 'Jul', value: 3_997_000 },
  { month: 'Aug', value: 4_186_000 },
  { month: 'Sep', value: 4_305_000 },
  { month: 'Oct', value: 4_268_000 },
  { month: 'Nov', value: 4_471_000 },
  { month: 'Dec', value: 4_640_000 },
  { month: 'Jan', value: 4_712_000 },
  { month: 'Feb', value: 4_903_000 },
  { month: 'Mar', value: 5_099_400 },
]

const src = (recordId: string, fipName: string, fiType: FiType, label: string): SourceRef => ({
  recordId,
  fipName,
  fiType,
  label,
})

export const insights: Insight[] = [
  {
    id: 'ins-01',
    title: 'Insurance outflow rising',
    body: 'Premiums paid last quarter were 21% higher than the previous quarter, driven by the Star Health renewal. Review before the next renewal cycle.',
    severity: 'action',
    sources: [
      src('txn-9052', 'LIC of India', 'INSURANCE_POLICIES', 'Premium — Jeevan Anand'),
      src('txn-8890', 'Star Health', 'INSURANCE_POLICIES', 'Premium — Family Floater'),
    ],
  },
  {
    id: 'ins-02',
    title: 'Idle liquidity detected',
    body: '₹3.2L has been sitting in savings for 90+ days at 3.5%. A sweep into a liquid fund would carry roughly ₹11,800 more per year.',
    severity: 'info',
    sources: [src('acc-hdfc-sav', 'HDFC Bank', 'DEPOSIT', 'Savings balance history')],
  },
]

/**
 * Pre-computed answer for the seeded Oracle session. The aggregate is calculated
 * over the cited records rather than generated by the model — the retrieve-then-
 * compute strategy from PROJECT_CONTEXT.md.
 */
export const premiumBreakdown = [
  { policy: 'LIC — Jeevan Anand', amount: 18400, recordId: 'txn-9052' },
  { policy: 'Star Health — Family Floater', amount: 14200, recordId: 'txn-8890' },
  { policy: 'HDFC Life — Click 2 Protect', amount: 9600, recordId: 'txn-8812' },
  { policy: 'Bajaj Allianz — Motor', amount: 7850, recordId: 'txn-8770' },
]

export const premiumTotals = {
  currentQuarter: premiumBreakdown.reduce((sum, p) => sum + p.amount, 0),
  previousQuarter: 41300,
  recordsScanned: 4,
  policiesActive: 4,
}

export const knowledgeBase: { category: string; icon: string; items: string[] }[] = [
  {
    category: 'Financial Information',
    icon: 'account_balance_wallet',
    items: ['Deposits & Cashflow', 'Mutual Fund Holdings', 'Insurance Policies', 'EPF & Retirement'],
  },
  {
    category: 'Consent & Audit',
    icon: 'verified_user',
    items: ['Active Consents', 'Data Access Log'],
  },
]

export const sessions = [
  {
    id: 'ses-1',
    device: 'MacBook Pro (M3)',
    icon: 'computer',
    detail: 'Current session · Bengaluru, IN',
    current: true,
  },
  {
    id: 'ses-2',
    device: 'Android · Etherm Finance App',
    icon: 'smartphone',
    detail: 'Active 2 hrs ago · Bengaluru, IN',
    current: false,
  },
]

export const profile = {
  name: 'Ananya Rao',
  tier: 'AA Verified',
  /** AA Virtual ID — the handle used to raise consent requests. */
  vua: 'ananya@onemoney',
  customerId: 'OFA-X902-884V',
}

/**
 * FIPs discoverable through the AA when linking a new account. Same shape as the
 * accounts above; the connect flow turns a pick from here into a FipAccount.
 */
export interface FipCandidate {
  fipId: string
  fipName: string
  fiType: FiType
  maskedAccountNumber: string
}

export const fipCatalogue: FipCandidate[] = [
  {
    fipId: 'AXIS-FIP',
    fipName: 'Axis Bank',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX0067',
  },
  {
    fipId: 'KOTAK-FIP',
    fipName: 'Kotak Mahindra Bank',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX7734',
  },
  {
    fipId: 'SBI-FIP',
    fipName: 'State Bank of India',
    fiType: 'DEPOSIT',
    maskedAccountNumber: 'XXXXXX2298',
  },
  {
    fipId: 'HDFCLIFE-FIP',
    fipName: 'HDFC Life',
    fiType: 'INSURANCE_POLICIES',
    maskedAccountNumber: 'Policy ****4416',
  },
  {
    fipId: 'NSDL-FIP',
    fipName: 'NSDL — Groww',
    fiType: 'EQUITIES',
    maskedAccountNumber: 'DP ****9021',
  },
]

/** Plausible opening balance per FI type, used when a new link finishes syncing. */
export const SEED_BALANCE: Record<FiType, number> = {
  DEPOSIT: 327400,
  MUTUAL_FUNDS: 689500,
  INSURANCE_POLICIES: 415000,
  EPF: 512300,
  EQUITIES: 268900,
}
