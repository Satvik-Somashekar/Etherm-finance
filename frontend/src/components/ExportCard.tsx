import { useState } from 'react'
import { Icon } from './Icon'
import { ApiError, downloadExport, type ExportKind } from '../lib/api'
import { DEMO } from '../lib/demo'

/** CSV downloads of the customer's consented data. Every row comes from records the active consents allow. */
export function ExportCard() {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [busy, setBusy] = useState<ExportKind | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function run(kind: ExportKind) {
    setBusy(kind)
    setError(null)
    try {
      await downloadExport(kind, { from: from || undefined, to: to || undefined })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not reach the services.')
    } finally {
      setBusy(null)
    }
  }

  const buttonClass =
    'flex w-full items-center justify-between rounded bg-surface-container px-4 py-3 text-left text-label-md text-on-surface transition-colors hover:bg-surface-container-high disabled:cursor-not-allowed disabled:opacity-50'
  const inputClass =
    'w-full rounded border border-outline-variant/40 bg-surface-container-lowest px-3 py-2 text-label-sm text-on-surface'

  return (
    <div className="rounded-md bg-surface-container-high p-6">
      <Icon name="download" className="mb-4 block text-primary" />
      <h3 className="mb-2 text-label-md text-on-surface">Export</h3>
      <p className="mb-4 text-label-sm leading-relaxed text-on-surface-variant">
        Download CSVs built from the accounts you have shared. Leave the dates empty for everything.
      </p>
      <div className="mb-4 grid grid-cols-2 gap-2">
        <label className="flex flex-col gap-1 text-label-sm text-on-surface-variant">
          From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className={inputClass} />
        </label>
        <label className="flex flex-col gap-1 text-label-sm text-on-surface-variant">
          To
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className={inputClass} />
        </label>
      </div>
      <div className="flex flex-col gap-2">
        <button type="button" className={buttonClass} disabled={DEMO || busy !== null} onClick={() => run('transactions')}>
          {busy === 'transactions' ? 'Preparing...' : 'Transactions (CSV)'}
          <Icon name="download" size={18} />
        </button>
        <button type="button" className={buttonClass} disabled={DEMO || busy !== null} onClick={() => run('report')}>
          {busy === 'report' ? 'Preparing...' : 'Monthly report (CSV)'}
          <Icon name="download" size={18} />
        </button>
      </div>
      {DEMO && (
        <p className="mt-3 text-label-sm text-on-surface-variant">Exports need the live services, so they are off in the public demo.</p>
      )}
      {error && (
        <p role="alert" className="mt-3 text-label-sm text-error">
          {error}
        </p>
      )}
    </div>
  )
}
