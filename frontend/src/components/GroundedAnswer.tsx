import { Icon } from './Icon'
import { Money } from './Money'
import type { Figure, QueryResult } from '../lib/api'

const BAR_COLOUR = ['bg-primary', 'bg-tertiary', 'bg-secondary', 'bg-primary-container']
const MAX_CHIPS = 12

/**
 * Renders a backend answer. Every number here is read from `figures`, `breakdown` or `citations`,
 * which the Java compute layer produced; nothing is parsed from the sentence.
 */
export function GroundedAnswer({ result }: { result: QueryResult }) {
  const metrics = result.figures.filter((f) => !['employeeShare', 'employerShare'].includes(f.key) || result.intent === 'EPF_CONTRIBUTIONS')
  const total = result.figures.find((f) => f.key === 'total')?.value ?? 0
  const items = result.breakdown.slice(0, 5)
  const share = (value: number) => (total > 0 ? (value / total) * 100 : 0)
  const chips = result.citations.slice(0, MAX_CHIPS)

  return (
    <>
      {metrics.length > 0 && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          {metrics.slice(0, 6).map((figure) => (
            <div key={figure.key} className="rounded-md bg-surface-container-high p-4">
              <div className="mb-2 text-label-sm uppercase tracking-wider text-on-surface-variant">
                {figure.label}
              </div>
              <FigureValue figure={figure} />
            </div>
          ))}
        </div>
      )}

      {items.length > 1 && (
        <div className="rounded-md bg-surface-dim p-5">
          <div className="mb-6 flex items-center justify-between">
            <h3 className="text-label-md uppercase tracking-widest text-on-surface">Breakdown</h3>
            {result.period && (
              <span className="text-label-sm text-on-surface-variant">{result.period.label}</span>
            )}
          </div>
          <div className="space-y-4">
            {items.map((row, index) => (
              <div key={row.label}>
                <div className="mb-1 flex justify-between gap-4 text-label-sm text-on-surface">
                  <span className="truncate">{row.label}</span>
                  <span className="shrink-0 text-on-surface-variant">
                    <Money value={row.value} /> · {share(row.value).toFixed(0)}%
                  </span>
                </div>
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-container-highest">
                  <div
                    className={`h-full animate-grow rounded-full ${BAR_COLOUR[index % BAR_COLOUR.length]}`}
                    style={{ width: `${Math.min(100, share(row.value))}%`, animationDelay: `${index * 90}ms` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {result.citations.length > 0 && (
        <div className="rounded-md border border-outline-variant/30 bg-surface-container-high/50 p-4">
          <div className="mb-3 flex items-center gap-2 text-label-sm uppercase tracking-widest text-on-surface-variant">
            <Icon name="link" size={16} />
            Sources · computed from {result.grounding.recordsCited} records
          </div>
          <div className="flex flex-wrap gap-2">
            {chips.map((c) => (
              <span
                key={c.recordId}
                title={`${c.date} · ${c.description}`}
                className="rounded-full bg-surface-container-highest px-3 py-1 font-mono text-label-sm text-on-surface-variant"
              >
                {c.recordId}
              </span>
            ))}
            {result.citations.length > MAX_CHIPS && (
              <span className="px-2 py-1 text-label-sm text-on-surface-variant">
                +{result.citations.length - MAX_CHIPS} more
              </span>
            )}
          </div>
          <p className="mt-3 text-label-sm leading-relaxed text-on-surface-variant opacity-70">
            Totals are computed in Java over the retrieved records, within {result.grounding.consentIds.length}{' '}
            active consent{result.grounding.consentIds.length === 1 ? '' : 's'}. Sentence written by{' '}
            {result.phrasedBy === 'template' ? 'a template' : result.phrasedBy}, which never sees the numbers.
          </p>
        </div>
      )}
    </>
  )
}

function FigureValue({ figure }: { figure: Figure }) {
  if (figure.unit === 'INR') {
    return <Money value={figure.value} className="text-headline-md text-on-surface" />
  }
  if (figure.unit === 'PERCENT') {
    const up = figure.value > 0
    return (
      <span className={`text-headline-md ${up ? 'text-error' : 'text-success'}`}>
        {up ? '+' : ''}
        {figure.value.toFixed(1)}%
      </span>
    )
  }
  return <span className="text-headline-md text-secondary">{figure.value}</span>
}
