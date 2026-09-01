import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { GlassCard } from '../components/GlassCard'
import { Icon } from '../components/Icon'
import { Money } from '../components/Money'
import { Sparkline } from '../components/Sparkline'
import { accounts, insights, netWorthSeries, transactions } from '../data/mock'
import { FI_TYPE_ICON, FI_TYPE_LABEL, type FiType } from '../types/fi'
import { timeAgo } from '../lib/format'

const RANGES = { '1M': 2, '3M': 4, '1Y': 12, ALL: netWorthSeries.length } as const
type Range = keyof typeof RANGES

const ALLOCATION_COLOUR: Record<FiType, string> = {
  DEPOSIT: 'bg-primary',
  MUTUAL_FUNDS: 'bg-tertiary',
  INSURANCE_POLICIES: 'bg-secondary',
  EPF: 'bg-primary-container',
  EQUITIES: 'bg-tertiary-container',
}

export function Dashboard() {
  const [range, setRange] = useState<Range>('1Y')

  // Linked accounts only — a pending consent grants no access to balances.
  const linked = useMemo(() => accounts.filter((a) => a.consent.status === 'ACTIVE'), [])
  const netWorth = useMemo(() => linked.reduce((sum, a) => sum + a.balance, 0), [linked])

  const series = useMemo(
    () => netWorthSeries.slice(netWorthSeries.length - RANGES[range]),
    [range],
  )
  const changePct = useMemo(() => {
    const first = series[0].value
    return ((series[series.length - 1].value - first) / first) * 100
  }, [series])

  const allocation = useMemo(() => {
    const byType = new Map<FiType, number>()
    for (const account of linked) {
      byType.set(account.fiType, (byType.get(account.fiType) ?? 0) + account.balance)
    }
    return [...byType.entries()]
      .map(([fiType, total]) => ({ fiType, total, share: (total / netWorth) * 100 }))
      .sort((a, b) => b.total - a.total)
  }, [linked, netWorth])

  return (
    <div className="flex w-full flex-col">
      {/* Hero */}
      <section className="relative flex min-h-[420px] w-full items-center overflow-hidden bg-surface-container-low px-margin-mobile py-12 lg:h-[500px] lg:px-margin-desktop">
        <div className="absolute inset-0 z-0 bg-gradient-to-br from-surface-container-low via-surface to-background" />
        <div
          className="absolute inset-0 z-0 opacity-30"
          style={{
            backgroundImage:
              'radial-gradient(circle at 70% 30%, rgba(160, 120, 255, 0.55) 0%, transparent 50%)',
          }}
        />

        <div className="relative z-10 mx-auto flex w-full max-w-container-max flex-col justify-between gap-8 pb-8 lg:flex-row lg:items-end">
          <div className="animate-fade-up">
            <h1 className="mb-2 text-headline-lg-mobile text-on-surface lg:text-headline-lg">
              Total Net Worth
            </h1>
            <div className="flex flex-wrap items-baseline gap-4">
              <Money
                value={netWorth}
                variant="split"
                className="text-[44px] leading-none tracking-tighter text-on-surface sm:text-[64px] lg:text-[72px]"
                fractionClassName="text-on-surface-variant text-[28px] sm:text-[40px] lg:text-[48px]"
              />
              <div
                className={`flex items-center gap-1 rounded-full px-3 py-1 ${
                  changePct >= 0 ? 'bg-primary/10 text-primary' : 'bg-error/10 text-error'
                }`}
              >
                <Icon name={changePct >= 0 ? 'trending_up' : 'trending_down'} size={16} />
                <span className="text-label-md">
                  {changePct >= 0 ? '+' : ''}
                  {changePct.toFixed(1)}%
                </span>
              </div>
            </div>
            <p className="mt-4 text-body-md text-on-surface-variant">
              Aggregated across {linked.length} consented accounts · {allocation.length} FI types
            </p>
          </div>

          <div className="flex gap-2 sm:gap-4">
            {(Object.keys(RANGES) as Range[]).map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => setRange(key)}
                aria-pressed={range === key}
                className={`rounded-full px-5 py-3 text-label-md transition-colors sm:px-6 ${
                  range === key
                    ? 'bg-primary text-on-primary shadow-glow'
                    : 'bg-surface-container text-on-surface hover:bg-surface-container-high'
                }`}
              >
                {key}
              </button>
            ))}
          </div>
        </div>

        <div className="pointer-events-none absolute bottom-0 left-0 right-0 z-10 h-48 opacity-60 mix-blend-screen">
          <Sparkline values={series.map((p) => p.value)} />
        </div>
      </section>

      {/* Cards overlap the hero so the sparkline reads as one continuous surface. */}
      <section className="relative z-20 mx-auto -mt-12 grid w-full max-w-container-max grid-cols-12 gap-6 px-margin-mobile py-16 lg:-mt-20 lg:gap-8 lg:px-margin-desktop">
        <GlassCard className="col-span-12 p-8 lg:col-span-4">
          <h2 className="mb-6 flex items-center justify-between text-headline-sm text-on-surface">
            Allocation
            <Icon name="pie_chart" className="text-primary" />
          </h2>
          <div className="space-y-6">
            {allocation.map((slice, index) => (
              <div key={slice.fiType} className="space-y-2">
                <div className="flex justify-between text-label-md">
                  <span className="text-on-surface">{FI_TYPE_LABEL[slice.fiType]}</span>
                  <Money value={slice.total} className="text-on-surface-variant" />
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-surface-container">
                  <div
                    className={`h-full animate-grow rounded-full ${ALLOCATION_COLOUR[slice.fiType]}`}
                    style={{ width: `${slice.share}%`, animationDelay: `${index * 90}ms` }}
                  />
                </div>
                <div className="text-label-sm text-on-surface-variant opacity-60">
                  {slice.share.toFixed(1)}% of portfolio
                </div>
              </div>
            ))}
          </div>
        </GlassCard>

        <GlassCard className="col-span-12 flex flex-col p-8 lg:col-span-4">
          <h2 className="mb-6 flex items-center justify-between text-headline-sm text-on-surface">
            Recent Activity
            <Icon name="history" className="text-primary" />
          </h2>
          <div className="flex-1 space-y-2">
            {transactions.slice(0, 4).map((txn) => (
              <div
                key={txn.id}
                className="group flex cursor-pointer items-center gap-4 rounded-md p-3 transition-colors hover:bg-surface-container/50"
              >
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-surface-container transition-transform group-hover:scale-110">
                  <Icon name={FI_TYPE_ICON[txn.fiType]} className="text-primary" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-label-md text-on-surface">{txn.narration}</p>
                  <p className="truncate text-label-sm text-on-surface-variant">
                    {txn.counterparty}
                  </p>
                </div>
                <div className="text-right">
                  <Money
                    value={txn.amount}
                    className={`text-label-md ${
                      txn.direction === 'CREDIT' ? 'text-success' : 'text-error'
                    }`}
                  />
                  <p className="mt-1 text-label-sm text-on-surface-variant">
                    {timeAgo(txn.valueDate)}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </GlassCard>

        <div className="relative col-span-12 flex flex-col overflow-hidden rounded-lg bg-primary p-8 shadow-xl lg:col-span-4">
          <div className="absolute right-0 top-0 p-4 opacity-20">
            <Icon name="neurology" className="text-on-primary" size={64} />
          </div>
          <h2 className="mb-6 flex items-center gap-2 text-headline-sm text-on-primary">
            AI Oracle Insights
            <span className="h-2 w-2 animate-pulse rounded-full bg-on-primary" />
          </h2>
          <div className="relative z-10 flex-1 space-y-4">
            {insights.map((insight) => (
              <div
                key={insight.id}
                className="rounded-md border border-on-primary/20 bg-on-primary/10 p-4 backdrop-blur-md"
              >
                <p className="mb-1 text-body-md text-on-primary">{insight.title}</p>
                <p className="text-label-sm leading-relaxed text-on-primary/80">{insight.body}</p>
                <p className="mt-3 flex items-center gap-1 text-label-sm text-on-primary/60">
                  <Icon name="link" size={14} />
                  Grounded in {insight.sources.length} record
                  {insight.sources.length === 1 ? '' : 's'}
                </p>
              </div>
            ))}
            <Link
              to="/oracle"
              className="flex w-full items-center justify-center gap-2 rounded bg-on-primary px-4 py-3 text-label-md text-primary transition-opacity hover:opacity-90"
            >
              Ask the Oracle
              <Icon name="arrow_forward" size={18} />
            </Link>
          </div>
        </div>
      </section>
    </div>
  )
}
