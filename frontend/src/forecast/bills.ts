// Rung 3: recurring bills. A payee paid the same amount at a steady monthly, quarterly or yearly rhythm is a
// bill, and its next payment can be projected to the day instead of being smoothed with everything else.

import { dayNumber, daysInMonth, monthIndex, type Txn } from './ledger.ts'

export type Cadence = 'monthly' | 'quarterly' | 'yearly'

export interface Bill {
  /** direction|category|payee: the group the bill's payments were found in. */
  key: string
  payee: string
  category: string
  direction: 'DEBIT' | 'CREDIT'
  cadence: Cadence
  /** The latest amount paid. */
  amount: number
  /** Payments seen so far. */
  occurrences: number
  last: string
  /** Every month the group was paid in, and how much. Lets the forecaster split history into bills and the rest. */
  paid: Map<number, number>
}

interface Rule {
  cadence: Cadence
  months: number
  /** Accepted median gap between payments, in days. */
  gap: [number, number]
  /** Largest distance of any gap from the median, in days. */
  jitter: number
  minPayments: number
}

const RULES: Rule[] = [
  { cadence: 'monthly', months: 1, gap: [26, 35], jitter: 5, minPayments: 3 },
  { cadence: 'quarterly', months: 3, gap: [85, 97], jitter: 7, minPayments: 3 },
  { cadence: 'yearly', months: 12, gap: [350, 380], jitter: 10, minPayments: 2 },
]
/** Recent payments must agree within this share of the latest amount. */
const AMOUNT_TOLERANCE = 0.05

const groupKey = (t: Txn) => `${t.direction}|${t.category}|${t.payee}`

const median = (v: number[]) => {
  const s = [...v].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}

/**
 * Bills found in the transactions before `target` (a month index). Only those still running are kept: a bill
 * whose next payment is overdue by more than half its period has stopped.
 */
export function detectBills(txns: Txn[], target: number): Bill[] {
  const groups = new Map<string, Txn[]>()
  for (const t of txns) {
    if (t.month >= target) continue
    const key = groupKey(t)
    const list = groups.get(key)
    if (list) list.push(t)
    else groups.set(key, [t])
  }
  const end = dayNumber(`${Math.floor(target / 12)}-${String((target % 12) + 1).padStart(2, '0')}-01`) - 1
  const bills: Bill[] = []
  for (const [key, list] of groups) {
    if (list.length < 2) continue
    const days = list.map((t) => dayNumber(t.date))
    const gaps = days.slice(1).map((d, i) => d - days[i])
    const gap = median(gaps)
    const rule = RULES.find((r) => gap >= r.gap[0] && gap <= r.gap[1])
    if (!rule || list.length < rule.minPayments) continue
    if (gaps.some((g) => Math.abs(g - gap) > rule.jitter)) continue
    const recent = list.slice(-3).map((t) => t.amount)
    const amount = recent[recent.length - 1]
    if (recent.some((a) => Math.abs(a - amount) > AMOUNT_TOLERANCE * amount)) continue
    if (days[days.length - 1] + 1.5 * gap < end) continue
    const paid = new Map<number, number>()
    for (const t of list) paid.set(t.month, (paid.get(t.month) ?? 0) + t.amount)
    const last = list[list.length - 1]
    bills.push({ key, payee: last.payee, category: last.category, direction: last.direction, cadence: rule.cadence,
      amount, occurrences: list.length, last: last.date, paid })
  }
  return bills
}

/** The dates a bill falls due in `month`, stepping whole periods from its last payment on the same day of the month. */
export function dueDates(bill: Bill, month: number): string[] {
  const step = RULES.find((r) => r.cadence === bill.cadence)!.months
  const day = Number(bill.last.slice(8, 10))
  const out: string[] = []
  for (let m = monthIndex(bill.last) + step; m <= month; m += step) {
    if (m === month) {
      const d = Math.min(day, daysInMonth(m))
      out.push(`${Math.floor(m / 12)}-${String((m % 12) + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`)
    }
  }
  return out
}

export const billKey = groupKey
