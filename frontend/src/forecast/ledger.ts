// The customer's bank transactions, as the forecaster sees them. They come from the consent-bounded
// transactions CSV the Java services build (rag-service ExportService), so the category of every
// transaction is decided by the Java normaliser, not here. Plain TypeScript with no DOM or Node APIs:
// the same file runs in the browser's worker and in the Node backtest.

export interface Txn {
  /** yyyy-MM-dd */
  date: string
  /** Months since year 0 (year * 12 + month - 1), so consecutive months differ by one. */
  month: number
  day: number
  category: string
  direction: 'DEBIT' | 'CREDIT'
  /** Rupees, always positive; direction says which way the money moved. */
  amount: number
  /** Who the money went to or came from: the counterparty, or the description when there is none. */
  payee: string
}

/**
 * Deposit debits that count as spending. Mirrors Category.SPENDING in fi-core: SIP debits and transfers
 * to the customer's own demat account move money into investments, so they are not spending.
 */
export const SPENDING = [
  'RENT',
  'GROCERIES',
  'FOOD',
  'FUEL',
  'SHOPPING',
  'UTILITIES',
  'TRANSPORT',
  'CASH_WITHDRAWAL',
  'PREMIUM_DEBIT',
  'OTHER_DEBIT',
] as const

export const monthIndex = (date: string) => Number(date.slice(0, 4)) * 12 + Number(date.slice(5, 7)) - 1

/** yyyy-MM for a month index. */
export const monthLabel = (month: number) =>
  `${Math.floor(month / 12)}-${String((month % 12) + 1).padStart(2, '0')}`

export const daysInMonth = (month: number) => new Date(Date.UTC(Math.floor(month / 12), (month % 12) + 1, 0)).getUTCDate()

/** Days since 1970-01-01 for a yyyy-MM-dd date. */
export const dayNumber = (date: string) => Date.UTC(+date.slice(0, 4), +date.slice(5, 7) - 1, +date.slice(8, 10)) / 86_400_000

/** RFC 4180 rows. Quoted cells may hold commas, quotes and line breaks. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        cell += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === ',') {
      row.push(cell)
      cell = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else {
      cell += c
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }
  return rows
}

/** The deposit transactions in an export, oldest first. Rows of other account types are ignored. */
export function readTransactions(csv: string): Txn[] {
  const [header, ...rows] = parseCsv(csv)
  if (!header) return []
  const col = (name: string) => {
    const index = header.indexOf(name)
    if (index < 0) throw new Error(`The transactions export has no ${name} column`)
    return index
  }
  const date = col('date')
  const type = col('type')
  const category = col('category')
  const direction = col('direction')
  const amount = col('amount_inr')
  const description = col('description')
  const counterparty = col('counterparty')
  const out: Txn[] = []
  for (const r of rows) {
    if (r.length < header.length || r[type] !== 'DEPOSIT') continue
    const value = Number(r[amount])
    if (!/^\d{4}-\d{2}-\d{2}$/.test(r[date]) || !Number.isFinite(value)) continue
    if (r[direction] !== 'DEBIT' && r[direction] !== 'CREDIT') continue
    out.push({
      date: r[date],
      month: monthIndex(r[date]),
      day: Number(r[date].slice(8, 10)),
      category: r[category],
      direction: r[direction] as 'DEBIT' | 'CREDIT',
      amount: Math.abs(value),
      payee: payeeName(r[counterparty] || r[description]),
    })
  }
  return out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
}

/** The export defuses cells a spreadsheet would run as a formula with a leading apostrophe; undo that for display. */
function payeeName(raw: string) {
  const name = /^'[=+\-@\t\r]/.test(raw) ? raw.slice(1) : raw
  return name.replace(/\s+/g, ' ').trim().slice(0, 60) || 'Unknown payee'
}
