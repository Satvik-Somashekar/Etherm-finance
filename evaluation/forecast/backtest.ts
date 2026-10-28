// Rolling-origin backtest of the edge forecaster (frontend/src/forecast), run with Node's TypeScript support:
//
//   node evaluation/forecast/backtest.ts [--data evaluation/forecast/data] [--out evaluation/results] [--set dev|held-out]
//
// The data is written by fi-core's ForecastBacktestData (30 synthetic customers, three years, deposit
// transactions normalised by the real normaliser, in the transactions-export columns). For every customer and
// every month with at least six months before it, each rung forecasts that month from the earlier months only,
// and is scored against what happened. Writes FORECAST.md and forecast.json for the dev set (seed 42), or
// FORECAST_HELDOUT.md and forecast_heldout.json for the held-out set (seed 7). No dependencies.

import { readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { billKey, detectBills } from '../../frontend/src/forecast/bills.ts'
import { forecast, LADDER, MIN_HISTORY, point, type Options } from '../../frontend/src/forecast/forecast.ts'
import { dayNumber, monthLabel, readTransactions, SPENDING, type Txn } from '../../frontend/src/forecast/ledger.ts'
import { MODELS } from '../../frontend/src/forecast/models.ts'

const arg = (name: string, fallback: string) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : fallback
}
const root = resolve(import.meta.dirname, '..', '..')
const dataDir = resolve(arg('--data', join(root, 'evaluation', 'forecast', process.argv.includes('held-out') ? 'heldout' : 'data')))
const outDir = resolve(arg('--out', join(root, 'evaluation', 'results')))
const heldOut = arg('--set', 'dev') === 'held-out'

const model = (name: string) => MODELS.find((m) => m.name === name)!
const VARIANTS: [string, Options][] = [
  ['naive (last month)', { models: [model('naive')], bills: false, reconcile: false }],
  ['mean of all months', { models: [model('mean')], bills: false, reconcile: false }],
  ['1: best baseline', LADDER.rung1],
  ['2: + smoothing', LADDER.rung2],
  ['3: + recurring bills', LADDER.rung3],
  ['4: + reconciliation', LADDER.rung4],
  ['shipped: 4 without smoothing', LADDER.shipped],
]
const BUCKETS: [string, number, number][] = [
  ['6-11 months', 6, 11],
  ['12-23 months', 12, 23],
  ['24+ months', 24, Infinity],
]
const bucketOf = (history: number) => BUCKETS.find(([, lo, hi]) => history >= lo && history <= hi)![0]
const spending = new Set<string>(SPENDING)

interface Score {
  absError: number
  actual: number
  /** Mean absolute month-to-month change before the origin; MASE divides by it. */
  scale: number
}
const empty = () => ({ total: [] as Score[], categories: [] as Score[], net: [] as Score[] })
const scores = new Map<string, ReturnType<typeof empty>>()
const at = (variant: string, bucket: string) => {
  const key = `${variant}|${bucket}`
  if (!scores.has(key)) scores.set(key, empty())
  return scores.get(key)!
}
// Per series kind: [inside the range, ranges given, sum of widths in rupees, sum of actuals]
const cover = { total: [0, 0, 0, 0], categories: [0, 0, 0, 0], net: [0, 0, 0, 0] } as Record<string, number[]>
const coverByBucket = new Map<string, number[]>()
const bills = { predicted: 0, hit: 0, actual: 0, found: 0, byCadence: {} as Record<string, number> }
const millis: number[] = []
const models: Record<string, number> = {}
let customers = 0
let txnCount = 0
let bytes = 0

const monthly = (txns: Txn[], first: number, last: number) => {
  const n = last - first + 1
  const cats: Record<string, number[]> = {}
  const total = new Array<number>(n).fill(0)
  const net = new Array<number>(n).fill(0)
  for (const t of txns) {
    const i = t.month - first
    net[i] += t.direction === 'CREDIT' ? t.amount : -t.amount
    if (t.direction === 'DEBIT' && spending.has(t.category)) {
      ;(cats[t.category] ??= new Array<number>(n).fill(0))[i] += t.amount
      total[i] += t.amount
    }
  }
  return { cats, total, net }
}
const scaleOf = (y: number[]) => (y.length < 2 ? 0 : y.slice(1).reduce((s, v, i) => s + Math.abs(v - y[i]), 0) / (y.length - 1))

const files = readdirSync(dataDir).filter((f) => f.endsWith('.csv')).sort()
if (files.length === 0) {
  console.error(`No CSVs in ${dataDir}. Generate them first (see fi-core ForecastBacktestData).`)
  process.exit(1)
}
for (const file of files) {
  const csv = readFileSync(join(dataDir, file), 'utf8')
  const txns = readTransactions(csv)
  customers++
  txnCount += txns.length
  bytes += csv.length
  const first = txns[0].month
  const last = txns[txns.length - 1].month
  const actual = monthly(txns, first, last)
  // Ground truth for bills: payments the detector finds when it may look at all the data.
  const recurring = new Set(detectBills(txns, last + 1).filter((b) => b.direction === 'DEBIT').map((b) => b.key))

  for (let target = first + MIN_HISTORY; target <= last; target++) {
    const i = target - first
    const history = i
    const bucket = bucketOf(history)
    const before = (y: number[]) => y.slice(0, i)
    for (const [name, options] of VARIANTS) {
      const p = point(txns, target, options)!
      const s = at(name, bucket)
      s.total.push({ absError: Math.abs(actual.total[i] - p.total), actual: actual.total[i], scale: scaleOf(before(actual.total)) })
      s.net.push({ absError: Math.abs(actual.net[i] - p.net), actual: Math.abs(actual.net[i]), scale: scaleOf(before(actual.net)) })
      for (const c of Object.keys(actual.cats)) {
        const y = actual.cats[c]
        if (!before(y).some((v) => v > 0)) continue
        s.categories.push({ absError: Math.abs(y[i] - (p.categories[c] ?? 0)), actual: y[i], scale: scaleOf(before(y)) })
      }
    }

    const started = performance.now()
    const f = forecast(txns, target)!
    millis.push(performance.now() - started)
    for (const c of f.categories) models[c.model] = (models[c.model] ?? 0) + 1
    const check = (kind: string, low: number | null, high: number | null, y: number) => {
      if (low === null || high === null) return
      const inside = y >= low - 1e-6 && y <= high + 1e-6 ? 1 : 0
      cover[kind][0] += inside
      cover[kind][1]++
      cover[kind][2] += high - low
      cover[kind][3] += Math.abs(y)
      const key = `${kind}|${bucket}`
      const b = coverByBucket.get(key) ?? [0, 0]
      b[0] += inside
      b[1]++
      coverByBucket.set(key, b)
    }
    check('total', f.total.low, f.total.high, actual.total[i])
    check('net', f.net.low, f.net.high, actual.net[i])
    for (const c of f.categories) check('categories', c.low, c.high, actual.cats[c.category]?.[i] ?? 0)

    const paid = txns.filter((t) => t.month === target && t.direction === 'DEBIT')
    for (const b of f.bills.filter((x) => x.direction === 'DEBIT')) {
      bills.predicted++
      bills.byCadence[b.cadence] = (bills.byCadence[b.cadence] ?? 0) + 1
      const match = paid.find((t) => t.payee === b.payee && t.category === b.category
        && Math.abs(dayNumber(t.date) - dayNumber(b.date)) <= 3 && Math.abs(t.amount - b.amount) <= 0.01 * b.amount)
      if (match) bills.hit++
    }
    for (const t of paid.filter((x) => recurring.has(billKey(x)))) {
      bills.actual++
      if (f.bills.some((b) => b.payee === t.payee && b.category === t.category && Math.abs(dayNumber(t.date) - dayNumber(b.date)) <= 3)) bills.found++
    }
  }
}

// ------------------------------------------------------------------------------------------------- report

const mase = (s: Score[]) => {
  const scaled = s.filter((x) => x.scale > 0)
  return scaled.reduce((a, x) => a + x.absError / x.scale, 0) / scaled.length
}
const wmape = (s: Score[]) => (100 * s.reduce((a, x) => a + x.absError, 0)) / s.reduce((a, x) => a + x.actual, 0)
const mae = (s: Score[]) => s.reduce((a, x) => a + x.absError, 0) / s.length
const merged = (variant: string) => {
  const all = empty()
  for (const [b] of BUCKETS) {
    const s = scores.get(`${variant}|${b}`)
    if (!s) continue
    all.total.push(...s.total)
    all.categories.push(...s.categories)
    all.net.push(...s.net)
  }
  return all
}
const f1 = (v: number) => v.toFixed(2)
const pct = (v: number) => `${v.toFixed(1)}%`
const inr = (v: number) => `₹${Math.round(v).toLocaleString('en-IN')}`
const sorted = [...millis].sort((a, b) => a - b)
const q = (p: number) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))]
const forecastsMade = millis.length
const allMonths = files.length ? `${monthLabel(readTransactions(readFileSync(join(dataDir, files[0]), 'utf8'))[0].month)}` : ''

const lines = [
  '# Edge forecasting backtest',
  '',
  `Generated by \`evaluation/forecast/backtest.ts\` from ${customers} synthetic customers (${txnCount.toLocaleString('en-IN')} bank `
  + `transactions, ${(bytes / 1024 / 1024).toFixed(1)} MB of CSV, from ${allMonths}), written by fi-core \`ForecastBacktestData\`. `
  + `Rolling origin: every month with at least ${MIN_HISTORY} months before it is forecast from those earlier months only `
  + `(${forecastsMade} customer-months). Lower is better for MASE and wMAPE. MASE below 1 beats repeating last month's figure.`,
  '',
  '**The synthetic data is regular.** Each customer spends a randomly drawn share of a fixed salary every month, split '
  + 'in fixed proportions, with no trend, no festival months and no life events, and rent, SIPs and premiums never '
  + 'change. Real spending is messier, so these numbers say the code is correct and cheap, not how accurate it will be '
  + 'for real customers.',
  '',
  heldOut
    ? '**Held-out set.** These customers (seed 7) were not looked at while choosing any setting.'
    : '**Dev set.** Three settings were chosen by looking at these customers (seed 42): how many recent months the '
      + 'models are compared on (6, 9 and 12 tried; 12 kept), how much better a more complex model must be to be picked '
      + '(0.05, 0.1 and 0.2 tried; 0.1 kept), and leaving smoothing out of what ships. That makes these figures slightly '
      + 'optimistic; FORECAST_HELDOUT.md repeats the run on customers that were not looked at.',
  '',
  '## Accuracy by rung (all history lengths)',
  '',
  '| Variant | Total spending MASE | Total wMAPE | Category MASE | Category wMAPE | Net flow MASE | Net flow MAE |',
  '| --- | --- | --- | --- | --- | --- | --- |',
  ...VARIANTS.map(([name]) => {
    const s = merged(name)
    return `| ${name} | ${f1(mase(s.total))} | ${pct(wmape(s.total))} | ${f1(mase(s.categories))} | ${pct(wmape(s.categories))} | ${f1(mase(s.net))} | ${inr(mae(s.net))} |`
  }),
  '',
  '## Total spending wMAPE by length of history',
  '',
  `| Variant | ${BUCKETS.map(([b]) => b).join(' | ')} |`,
  `| --- | ${BUCKETS.map(() => '---').join(' | ')} |`,
  ...VARIANTS.map(([name]) => `| ${name} | ${BUCKETS.map(([b]) => {
    const s = scores.get(`${name}|${b}`)
    return s ? pct(wmape(s.total)) : '-'
  }).join(' | ')} |`),
  '',
  '## Rung 5: 80% ranges',
  '',
  'Split-conformal ranges from the shipped pipeline re-run on up to twelve earlier months. Coverage is the share '
  + 'of actual figures that fell inside the range; it should be at least 80%. Width is high minus low. A category '
  + 'whose non-bill spending never changed (rent alone, say) gets its bill as the forecast and no range, so it is '
  + 'not counted here.',
  '',
  '| Series | Ranges given | Coverage | Mean width | Mean actual |',
  '| --- | --- | --- | --- | --- |',
  ...Object.entries(cover).map(([k, [inside, n, width, size]]) =>
    `| ${k === 'net' ? 'net flow (absolute)' : k === 'total' ? 'total spending' : 'each category'} | ${n} | ${pct((100 * inside) / n)} | ${inr(width / n)} | ${inr(size / n)} |`),
  '',
  `| Coverage by history | ${BUCKETS.map(([b]) => b).join(' | ')} |`,
  `| --- | ${BUCKETS.map(() => '---').join(' | ')} |`,
  ...['total', 'categories', 'net'].map((k) => `| ${k === 'net' ? 'net flow' : k === 'total' ? 'total spending' : 'each category'} | ${BUCKETS.map(([b]) => {
    const c = coverByBucket.get(`${k}|${b}`)
    return c ? `${pct((100 * c[0]) / c[1])} (${c[1]})` : '-'
  }).join(' | ')} |`),
  '',
  '## Rung 3: recurring bills',
  '',
  `Predicted bill payments: ${bills.predicted} (${Object.entries(bills.byCadence).map(([k, v]) => `${v} ${k}`).join(', ')}). `
  + `Paid as predicted (same payee, within 3 days, amount within 1%): ${bills.hit} (${pct((100 * bills.hit) / bills.predicted)}). `
  + `Of ${bills.actual} payments to payees the detector recognises as bills when shown all the data, ${bills.found} `
  + `(${pct((100 * bills.found) / bills.actual)}) were predicted for the right month within 3 days. A yearly bill needs `
  + 'two payments before it is recognised, so the first repeat of each insurance premium is missed.',
  '',
  '## Models picked (shipped pipeline, per category forecast)',
  '',
  '| Model | Times picked |',
  '| --- | --- |',
  ...Object.entries(models).sort((a, b) => b[1] - a[1]).map(([m, n]) => `| ${m} | ${n} |`),
  '',
  '## Cost',
  '',
  `One full forecast (shipped point forecasts for every category, the total and the net flow, plus up to twelve `
  + `re-runs to calibrate the ranges) took ${q(0.5).toFixed(1)} ms at the median and ${q(0.95).toFixed(1)} ms at the 95th `
  + `percentile in Node ${process.version} on the machine that ran this report, one thread. It reads only the customer's `
  + `own transactions and keeps a few hundred numbers per series.`,
  '',
]
mkdirSync(outDir, { recursive: true })
writeFileSync(join(outDir, heldOut ? 'FORECAST_HELDOUT.md' : 'FORECAST.md'), lines.join('\n'))
writeFileSync(join(outDir, heldOut ? 'forecast_heldout.json' : 'forecast.json'), JSON.stringify({
  customers, transactions: txnCount, forecasts: forecastsMade,
  variants: Object.fromEntries(VARIANTS.map(([name]) => {
    const s = merged(name)
    return [name, { totalMase: mase(s.total), totalWmape: wmape(s.total), categoryMase: mase(s.categories),
      categoryWmape: wmape(s.categories), netMase: mase(s.net), netMae: mae(s.net) }]
  })),
  coverage: Object.fromEntries(Object.entries(cover).map(([k, [inside, n, width, size]]) => [k, { ranges: n, coverage: inside / n, meanWidth: width / n, meanActual: size / n }])),
  bills, models, millis: { p50: q(0.5), p95: q(0.95) },
}, null, 1))
console.log(lines.join('\n'))

// A smoke gate for CI: the shipped pipeline must beat repeating last month, and its ranges must roughly hold.
const shipped = merged('shipped: 4 without smoothing')
const failures = [
  mase(shipped.total) >= 1 && 'total spending forecast is no better than last month',
  mase(shipped.categories) >= 1 && 'category forecasts are no better than last month',
  cover.categories[0] / cover.categories[1] < 0.75 && 'category ranges hold the actual figure less than 75% of the time',
  cover.total[0] / cover.total[1] < 0.75 && 'total ranges hold the actual figure less than 75% of the time',
].filter(Boolean)
if (failures.length) {
  console.error(`\nFAILED: ${failures.join('; ')}`)
  process.exit(1)
}
