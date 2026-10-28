// Next month's spending, bills and bank balance, computed from the customer's own transactions on the device
// that holds them. The ladder (README, "Edge forecasting"):
//   1. baselines (mean, naive, moving average, seasonal naive)
//   2. exponential smoothing, picked per series by its recent one-step errors (built and backtested, but not in
//      what the app runs: it did not beat the baselines on the synthetic customers)
//   3. recurring bills projected to the day, so only the rest of the spending is smoothed
//   4. reconciliation, so the category forecasts add up to a total that was also forecast on its own
//   5. split-conformal 80% ranges from the pipeline's own errors on earlier months
// Deterministic: the same transactions always give the same forecast. No network, no storage, no randomness.

import { billKey, detectBills, dueDates, type Bill, type Cadence } from './bills.ts'
import { monthLabel, SPENDING, type Txn } from './ledger.ts'
import { BASELINES, MODELS, select, type Model } from './models.ts'

export interface Options {
  /** Models to choose from: rung 1 only, or rungs 1 and 2. */
  models: Model[]
  /** Rung 3. */
  bills: boolean
  /** Rung 4. */
  reconcile: boolean
}

export const LADDER: Record<'rung1' | 'rung2' | 'rung3' | 'rung4' | 'shipped', Options> = {
  rung1: { models: BASELINES, bills: false, reconcile: false },
  rung2: { models: MODELS, bills: false, reconcile: false },
  rung3: { models: MODELS, bills: true, reconcile: false },
  rung4: { models: MODELS, bills: true, reconcile: true },
  // What the app runs. Smoothing is left out because on the synthetic customers it did not beat the baselines
  // (evaluation/results/FORECAST.md); a rung is climbed only when it measurably helps.
  shipped: { models: BASELINES, bills: true, reconcile: true },
}

export interface Point {
  /** Spending by category, for every category with spending in the history. */
  categories: Record<string, number>
  total: number
  /** Money in minus money out of the bank account over the month. */
  net: number
  /** The model each series was forecast with. */
  models: Record<string, string>
  bills: Bill[]
}

export interface Range {
  value: number
  /** Null when there were too few earlier months to calibrate an 80% range. */
  low: number | null
  high: number | null
}

export interface BillDue {
  payee: string
  category: string
  direction: 'DEBIT' | 'CREDIT'
  cadence: Cadence
  amount: number
  date: string
}

export interface Forecast {
  /** yyyy-MM of the month forecast. */
  month: string
  /** yyyy-MM of the first and last months used. */
  from: string
  to: string
  historyMonths: number
  total: Range
  categories: (Range & { category: string; model: string; billed: number })[]
  net: Range & { model: string }
  bills: BillDue[]
  /** Earlier months the ranges were calibrated on, and the coverage they aim for. */
  calibrationMonths: number
  coverage: number
}

/** Fewest months of history before a forecast is made. */
export const MIN_HISTORY = 6
/** Most earlier months the ranges are calibrated on. */
const CALIBRATION_MONTHS = 12
export const COVERAGE = 0.8

const sum = (v: number[]) => v.reduce((s, x) => s + x, 0)
const meanSquare = (v: number[]) => (v.length ? sum(v.map((x) => x * x)) / v.length : 0)

/** Monthly series from `first` to `target - 1`: spending per category, total spending and net flow, each split into bills and the rest. */
function series(txns: Txn[], first: number, target: number, bills: Bill[]) {
  const n = target - first
  const billed = new Set(bills.map((b) => b.key))
  const spend: Record<string, number[]> = {}
  const spendRest: Record<string, number[]> = {}
  const net = new Array<number>(n).fill(0)
  const netRest = new Array<number>(n).fill(0)
  const spending = new Set<string>(SPENDING)
  for (const t of txns) {
    if (t.month < first || t.month >= target) continue
    const i = t.month - first
    const signed = t.direction === 'CREDIT' ? t.amount : -t.amount
    const isBill = billed.has(billKey(t))
    net[i] += signed
    if (!isBill) netRest[i] += signed
    if (t.direction === 'DEBIT' && spending.has(t.category)) {
      ;(spend[t.category] ??= new Array<number>(n).fill(0))[i] += t.amount
      ;(spendRest[t.category] ??= new Array<number>(n).fill(0))[i] += isBill ? 0 : t.amount
    }
  }
  return { spend, spendRest, net, netRest }
}

/** Bills due in `month`, from bills found before it. */
function due(bills: Bill[], month: number): BillDue[] {
  const out: BillDue[] = []
  for (const b of bills) {
    for (const date of dueDates(b, month)) {
      out.push({ payee: b.payee, category: b.category, direction: b.direction, cadence: b.cadence, amount: b.amount, date })
    }
  }
  return out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : b.amount - a.amount))
}

/** The point forecast for `target`, from transactions before it only. Null when the history is too short. */
export function point(txns: Txn[], target: number, options: Options = LADDER.shipped): Point | null {
  const history = txns.filter((t) => t.month < target)
  if (history.length === 0) return null
  const first = history[0].month
  if (target - first < MIN_HISTORY) return null
  const bills = options.bills ? detectBills(history, target) : []
  const dueNow = due(bills, target)
  const s = series(history, first, target, bills)
  const models: Record<string, string> = {}

  const categories = Object.keys(s.spend).sort()
  const billedSpend = (c: string) => sum(dueNow.filter((b) => b.direction === 'DEBIT' && b.category === c).map((b) => b.amount))
  const rest: Record<string, number> = {}
  const weight: Record<string, number> = {}
  for (const c of categories) {
    const r = select(s.spendRest[c], options.models)
    models[c] = r.model
    rest[c] = Math.max(0, r.forecast)
    weight[c] = meanSquare(r.errors)
  }

  if (options.reconcile && categories.length > 1) {
    // One level of hierarchy: the total of the non-bill spending is forecast on its own, and the categories are
    // moved towards it in proportion to how unsure each one is (weighted least squares with a diagonal of
    // one-step error variances), so steady categories barely move and noisy ones absorb the difference.
    const restTotal = categories.map((c) => s.spendRest[c]).reduce((acc, y) => acc.map((v, i) => v + y[i]))
    const top = select(restTotal, options.models)
    models.total = top.model
    const wTop = meanSquare(top.errors)
    const wSum = sum(categories.map((c) => weight[c]))
    if (wTop + wSum > 0) {
      const gap = Math.max(0, top.forecast) - sum(categories.map((c) => rest[c]))
      for (const c of categories) rest[c] = Math.max(0, rest[c] + (weight[c] / (wTop + wSum)) * gap)
    }
  }

  const out: Record<string, number> = {}
  for (const c of categories) out[c] = rest[c] + billedSpend(c)

  const n = select(s.netRest, options.models)
  models.net = n.model
  const netBills = sum(dueNow.map((b) => (b.direction === 'CREDIT' ? b.amount : -b.amount)))
  return { categories: out, total: sum(Object.values(out)), net: n.forecast + netBills, models, bills }
}

/**
 * The (⌈(n+1)·coverage⌉)-th smallest score: with n exchangeable calibration scores, a new score falls at or below
 * it with probability at least `coverage`. Null when n is too small for that rank to exist.
 */
export function conformalQuantile(scores: number[], coverage = COVERAGE): number | null {
  const rank = Math.ceil((scores.length + 1) * coverage)
  if (rank > scores.length) return null
  return [...scores].sort((a, b) => a - b)[rank - 1]
}

/** Mean absolute month-to-month change: the typical size of a naive forecast's error, used to put series on one scale. */
const scaleOf = (y: number[]) => (y.length < 2 ? 0 : sum(y.slice(1).map((v, i) => Math.abs(v - y[i]))) / (y.length - 1))

/**
 * Next month's forecast with 80% ranges. The ranges come from re-running the whole pipeline at each of the last
 * twelve months that had six months of history before them, and comparing with what happened. Category errors are
 * pooled after dividing each by its category's typical month-to-month change (of the spending that is not a
 * bill), so a few months calibrate every category; the total and the net flow use their own errors.
 */
export function forecast(txns: Txn[], target: number, options: Options = LADDER.shipped): Forecast | null {
  const p = point(txns, target, options)
  if (!p) return null
  const first = txns[0].month
  const actual = series(txns, first, target, [])
  const rest = series(txns, first, target, p.bills).spendRest
  const scale: Record<string, number> = {}
  for (const c of Object.keys(p.categories)) scale[c] = scaleOf(rest[c] ?? [])

  const categoryScores: number[] = []
  const totalScores: number[] = []
  const netScores: number[] = []
  let months = 0
  for (let o = Math.max(first + MIN_HISTORY, target - CALIBRATION_MONTHS); o < target; o++) {
    const q = point(txns, o, options)
    if (!q) continue
    months++
    const i = o - first
    let total = 0
    for (const c of Object.keys(actual.spend)) total += actual.spend[c][i]
    // A category whose non-bill spending never changes (rent and nothing else, say) has nothing to scale by;
    // its forecast is the bill itself and gets no range.
    for (const c of Object.keys(p.categories)) {
      if (scale[c] > 0) categoryScores.push(Math.abs((actual.spend[c]?.[i] ?? 0) - (q.categories[c] ?? 0)) / scale[c])
    }
    totalScores.push(Math.abs(total - q.total))
    netScores.push(Math.abs(actual.net[i] - q.net))
  }

  const range = (value: number, width: number | null, floor: number | null): Range =>
    width === null
      ? { value, low: null, high: null }
      : { value, low: floor === null ? value - width : Math.max(floor, value - width), high: value + width }
  const qCategory = conformalQuantile(categoryScores)
  const bills = due(p.bills, target)
  const billed = (c: string) => sum(bills.filter((b) => b.direction === 'DEBIT' && b.category === c).map((b) => b.amount))

  const categories = Object.entries(p.categories)
    .map(([category, value]) => ({
      category,
      model: p.models[category],
      billed: billed(category),
      ...range(value, scale[category] > 0 ? (qCategory === null ? null : qCategory * scale[category]) : 0, 0),
    }))
    .sort((a, b) => b.value - a.value)

  return {
    month: monthLabel(target),
    from: monthLabel(first),
    to: monthLabel(target - 1),
    historyMonths: target - first,
    total: range(p.total, conformalQuantile(totalScores), 0),
    categories,
    net: { ...range(p.net, conformalQuantile(netScores), null), model: p.models.net },
    bills,
    calibrationMonths: months,
    coverage: COVERAGE,
  }
}

/**
 * The month to forecast: the one after the latest transaction, or the current calendar month if the transactions
 * reach into it (that month is still running, so its partial figures are left out).
 */
export function nextMonth(txns: Txn[], today: string): number | null {
  if (txns.length === 0) return null
  const latest = txns[txns.length - 1].month
  const current = Number(today.slice(0, 4)) * 12 + Number(today.slice(5, 7)) - 1
  return Math.min(latest + 1, current)
}
