// The one entry point the page uses, whether it runs in the worker or, where workers are unavailable, inline.

import { forecast, nextMonth, type Forecast } from './forecast.ts'
import { readTransactions } from './ledger.ts'

export interface ForecastRequest {
  /** The consent-bounded bank transactions export. */
  csv: string
  /** yyyy-MM-dd on this device, so a month still running is left out. */
  today: string
}

export type ForecastReply =
  | { ok: true; forecast: Forecast | null; transactions: number; months: number; ms: number }
  | { ok: false; error: string }

export function run({ csv, today }: ForecastRequest): ForecastReply {
  const started = performance.now()
  try {
    const txns = readTransactions(csv)
    const target = nextMonth(txns, today)
    const result = target === null ? null : forecast(txns, target)
    const months = txns.length ? txns[txns.length - 1].month - txns[0].month + 1 : 0
    return { ok: true, forecast: result, transactions: txns.length, months, ms: performance.now() - started }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) }
  }
}
