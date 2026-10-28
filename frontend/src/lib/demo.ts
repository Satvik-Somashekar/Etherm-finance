import { ApiError } from './errors'
import { currentCustomerId } from './session'
import questions from '../demo/questions.json'

/**
 * Demo mode serves recorded responses instead of calling the Java services, so the app runs on a
 * static host. Every response was captured from the real services by scripts/record-demo.mjs;
 * nothing here computes a figure. Consent changes are remembered in memory only.
 */
export const DEMO = import.meta.env.VITE_DEMO === 'true'

/** The questions whose answers were recorded; also the Oracle's suggestion chips. */
export const DEMO_QUESTIONS: string[] = questions

interface Recording {
  accounts: { account: { fipId: string }; consent: { consentId: string } }[]
  fips: unknown[]
  overview: unknown
  answers: Record<string, unknown>
  /** The bank-account transactions export, for the on-device forecast. */
  transactions?: string
}

const recordings = new Map<string, Promise<Recording>>()
/** Per customer: providers whose consent was revoked in this session. */
const inactive = new Map<string, Set<string>>()
/** Consents created in this session, so approving one can restore its provider. */
const created = new Map<string, string>()

const normalise = (q: string) =>
  q.toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim()

function load(customerId: string): Promise<Recording> {
  let recording = recordings.get(customerId)
  if (!recording) {
    const file = customerId.split('@')[0]
    recording = fetch(`${import.meta.env.BASE_URL}demo/${file}.json`).then((r) => {
      if (!r.ok) throw new ApiError(r.status, `No demo recording for ${customerId}`)
      return r.json() as Promise<Recording>
    })
    recordings.set(customerId, recording)
  }
  return recording
}

const inactiveFor = (customerId: string) => {
  let set = inactive.get(customerId)
  if (!set) {
    set = new Set()
    inactive.set(customerId, set)
  }
  return set
}

/** The recorded bank transactions, or none once the bank's consent is revoked in this session. */
export async function demoTransactions(): Promise<string> {
  const customerId = currentCustomerId()
  const recording = await load(customerId)
  const bank = recording.accounts.find((a) => a.account.fipId.includes('BANK'))
  if (!recording.transactions || !bank || inactiveFor(customerId).has(bank.account.fipId)) return ''
  return recording.transactions
}

export async function demoCall<T>(path: string, init?: RequestInit): Promise<T> {
  const customerId = currentCustomerId()
  const recording = await load(customerId)
  const off = inactiveFor(customerId)
  const method = init?.method ?? 'GET'

  if (path.endsWith('/accounts')) {
    return recording.accounts.filter((a) => !off.has(a.account.fipId)) as T
  }
  if (path.endsWith('/fips')) {
    return recording.fips as T
  }
  if (path.endsWith('/overview')) {
    return recording.overview as T
  }
  if (path.endsWith('/api/query') && method === 'POST') {
    const { question } = JSON.parse(String(init?.body)) as { question: string }
    if (off.size > 0) {
      throw new ApiError(
        403,
        'Demo mode: the recorded answers need every provider linked. Link the revoked provider again on the Accounts page, or run the backend locally.',
      )
    }
    const answer = recording.answers[normalise(question)]
    if (answer) return answer as T
    return {
      question,
      answer:
        'Demo mode answers only the questions that were recorded, so I cannot answer that one. Pick one of the suggested questions, or run the backend locally to ask anything.',
      phrasedBy: 'demo',
      intent: 'UNSUPPORTED',
      period: null,
      figures: [],
      breakdown: [],
      citations: [],
      grounding: { consentIds: [], recordsVisible: 0, recordsMatched: 0, recordsCited: 0, asOf: '' },
    } as T
  }
  if (path.endsWith('/api/consents') && method === 'POST') {
    const body = JSON.parse(String(init?.body)) as { fipIds?: string[]; fiTypes: string[] }
    const consentId = `CN-DEMO-${created.size + 1}`
    created.set(consentId, body.fipIds?.[0] ?? '')
    const now = new Date()
    return {
      consentId,
      customerId,
      status: 'PENDING',
      purposeCode: '101',
      purposeText: 'Wealth management service',
      fiTypes: body.fiTypes,
      consentStart: now.toISOString(),
      consentExpiry: new Date(now.getTime() + 365 * 86_400_000).toISOString(),
      dataFrom: '',
      dataTo: '',
      fetchType: 'PERIODIC',
    } as T
  }
  const action = /\/api\/consents\/([^/]+)\/(approve|revoke)$/.exec(path)
  if (action && method === 'POST') {
    const [, consentId, verb] = action
    if (verb === 'approve') {
      off.delete(created.get(consentId) ?? '')
    } else {
      const account = recording.accounts.find((a) => a.consent.consentId === consentId)
      if (account) off.add(account.account.fipId)
    }
    return {} as T
  }
  throw new ApiError(404, `Demo mode has no recording for ${method} ${path}`)
}
