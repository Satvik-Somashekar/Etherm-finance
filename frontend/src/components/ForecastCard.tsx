import { useEffect, useState } from 'react'
import { GlassCard } from './GlassCard'
import { Icon } from './Icon'
import { Money } from './Money'
import { loadBankTransactions } from '../lib/api'
import { formatDate } from '../lib/format'
import { forecastOnDevice } from '../forecast/client'
import type { Forecast, Range } from '../forecast/forecast'
import { MIN_HISTORY } from '../forecast/forecast'

const CATEGORY_LABEL: Record<string, string> = {
  RENT: 'Rent',
  GROCERIES: 'Groceries',
  FOOD: 'Food',
  FUEL: 'Fuel',
  SHOPPING: 'Shopping',
  UTILITIES: 'Utilities',
  TRANSPORT: 'Transport',
  CASH_WITHDRAWAL: 'Cash',
  PREMIUM_DEBIT: 'Insurance premiums',
  OTHER_DEBIT: 'Other',
}
const MONTH = new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })
const monthName = (yyyyMm: string) => MONTH.format(new Date(`${yyyyMm}-01T00:00:00Z`))
/** Forecasts are shown to the nearest hundred rupees; paise would claim a precision they do not have. */
const rounded = (v: number) => Math.round(v / 100) * 100

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; forecast: Forecast; transactions: number; ms: number }
  | { kind: 'empty'; reason: string }
  | { kind: 'error'; reason: string }

interface ForecastCardProps {
  /** Today's balance across the linked bank accounts, or null when no bank account is linked. */
  bankBalance: number | null
  /** Changes when consents change, so the forecast is recomputed from what is shared now. */
  consentKey: string
}

/**
 * Next month's spending, bills and bank balance, computed in this browser from the bank transactions the customer's
 * consents share. The transactions are fetched, forecast in a worker and dropped; nothing is stored or sent back.
 */
export function ForecastCard({ bankBalance, consentKey }: ForecastCardProps) {
  // The result is tagged with the consents it was computed for, so a change of consents shows "Forecasting…"
  // until the new result arrives.
  const [result, setResult] = useState<{ key: string; state: State } | null>(null)
  const state: State = result?.key === consentKey ? result.state : { kind: 'loading' }

  useEffect(() => {
    let cancelled = false
    const settle = (s: State) => !cancelled && setResult({ key: consentKey, state: s })
    loadBankTransactions()
      .then((csv) => forecastOnDevice({ csv, today: new Date().toISOString().slice(0, 10) }))
      .then((reply) => {
        if (!reply.ok) settle({ kind: 'error', reason: 'The forecast could not read the transactions.' })
        else if (reply.transactions === 0)
          settle({ kind: 'empty', reason: "Link a bank account to see next month's forecast from your own transactions." })
        else if (!reply.forecast)
          settle({
            kind: 'empty',
            reason: `The forecast needs at least ${MIN_HISTORY} months of bank history; ${reply.months} shared so far.`,
          })
        else settle({ kind: 'ready', forecast: reply.forecast, transactions: reply.transactions, ms: reply.ms })
      })
      .catch(() => settle({ kind: 'error', reason: 'Could not load your bank transactions.' }))
    return () => {
      cancelled = true
    }
  }, [consentKey])

  return (
    <GlassCard className="col-span-12 p-8">
      <h2 className="mb-1 flex items-center justify-between text-headline-sm text-on-surface">
        {state.kind === 'ready' ? `${monthName(state.forecast.month)} forecast` : 'Next month forecast'}
        <Icon name="query_stats" className="text-primary" />
      </h2>
      <p className="mb-6 flex items-center gap-1 text-label-sm text-on-surface-variant">
        <Icon name="devices" size={14} />
        Computed on this device from your bank transactions. Nothing is stored or sent back.
      </p>

      {state.kind === 'loading' && <p className="text-body-md text-on-surface-variant">Forecasting…</p>}
      {(state.kind === 'empty' || state.kind === 'error') && (
        <p className="text-body-md text-on-surface-variant">{state.reason}</p>
      )}
      {state.kind === 'ready' && <Ready {...state} bankBalance={bankBalance} />}
    </GlassCard>
  )
}

function Ready({
  forecast,
  transactions,
  ms,
  bankBalance,
}: {
  forecast: Forecast
  transactions: number
  ms: number
  bankBalance: number | null
}) {
  const top = forecast.categories.filter((c) => c.value >= 1).slice(0, 5)
  const largest = Math.max(1, ...top.map((c) => c.high ?? c.value))
  const payments = forecast.bills.filter((b) => b.direction === 'DEBIT')
  const credits = forecast.bills.filter((b) => b.direction === 'CREDIT')
  const balance: Range | null =
    bankBalance === null
      ? null
      : {
          value: bankBalance + forecast.net.value,
          low: forecast.net.low === null ? null : bankBalance + forecast.net.low,
          high: forecast.net.high === null ? null : bankBalance + forecast.net.high,
        }

  return (
    <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
      <div className="space-y-6">
        <div>
          <p className="text-label-sm text-on-surface-variant">Spending</p>
          <Money value={rounded(forecast.total.value)} className="text-headline-md text-on-surface" />
          <Between range={forecast.total} />
        </div>
        {balance && (
          <div>
            <p className="text-label-sm text-on-surface-variant">Bank balance at the end of the month</p>
            <Money value={rounded(balance.value)} className="text-headline-sm text-on-surface" />
            <Between range={balance} />
          </div>
        )}
      </div>

      <div className="space-y-4">
        <p className="text-label-sm text-on-surface-variant">By category</p>
        {top.map((c) => (
          <div key={c.category} className="space-y-1">
            <div className="flex justify-between text-label-md">
              <span className="text-on-surface">
                {CATEGORY_LABEL[c.category] ?? c.category}
                {c.billed > 0 && (
                  <span className="ml-2 rounded-full bg-primary/10 px-2 text-label-sm text-primary">bill</span>
                )}
              </span>
              <Money value={c.value} className="text-on-surface-variant" />
            </div>
            <div className="relative h-2 w-full overflow-hidden rounded-full bg-surface-container">
              {c.low !== null && c.high !== null && (
                <div
                  className="absolute h-full rounded-full bg-primary/25"
                  style={{ left: `${(c.low / largest) * 100}%`, width: `${((c.high - c.low) / largest) * 100}%` }}
                />
              )}
              <div className="h-full rounded-full bg-primary" style={{ width: `${(c.value / largest) * 100}%` }} />
            </div>
          </div>
        ))}
      </div>

      <div className="space-y-3">
        <p className="text-label-sm text-on-surface-variant">Regular payments due</p>
        {payments.length === 0 && credits.length === 0 && (
          <p className="text-label-md text-on-surface-variant">No regular payments found for this month.</p>
        )}
        {[...payments, ...credits].slice(0, 6).map((b) => (
          <div key={`${b.direction}${b.payee}${b.date}`} className="flex items-center justify-between gap-4 text-label-md">
            <div className="min-w-0">
              <p className="truncate text-on-surface">{b.payee}</p>
              <p className="text-label-sm text-on-surface-variant">
                {formatDate(b.date)} · {b.cadence}
              </p>
            </div>
            <span className={b.direction === 'CREDIT' ? 'text-success' : 'text-on-surface'}>
              {b.direction === 'CREDIT' ? '+' : '−'}
              <Money value={b.amount} />
            </span>
          </div>
        ))}
      </div>

      <p className="text-label-sm text-on-surface-variant opacity-70 lg:col-span-3">
        From {transactions.toLocaleString('en-IN')} transactions over {forecast.historyMonths} months, in{' '}
        {Math.max(1, Math.round(ms))} ms. Ranges should hold the actual figure 4 times in 5; they are calibrated on{' '}
        {forecast.calibrationMonths} earlier months. Bills are payments of the same amount at a steady rhythm. A
        forecast, not advice.
      </p>
    </div>
  )
}

function Between({ range }: { range: Range }) {
  if (range.low === null || range.high === null) {
    return <p className="text-label-sm text-on-surface-variant">Not enough history for a range yet.</p>
  }
  return (
    <p className="text-label-sm text-on-surface-variant">
      Likely between <Money value={rounded(range.low)} /> and <Money value={rounded(range.high)} />
    </p>
  )
}
