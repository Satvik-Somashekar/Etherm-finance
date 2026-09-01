import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Icon } from '../components/Icon'
import { Money } from '../components/Money'
import { knowledgeBase, premiumBreakdown, premiumTotals } from '../data/mock'
import { formatCompact } from '../lib/format'

interface ChatMessage {
  id: string
  role: 'user' | 'oracle'
  text: string
  /** Only the seeded answer carries the rich grounded-result block. */
  grounded?: boolean
}

const SEEDED: ChatMessage[] = [
  {
    id: 'm1',
    role: 'user',
    text: 'How much did I spend on insurance premiums last quarter, and which policy drove the increase?',
  },
  {
    id: 'm2',
    role: 'oracle',
    text: `Across your linked insurance accounts I found ${premiumTotals.recordsScanned} premium debits in the last quarter, totalling ${formatCompact(
      premiumTotals.currentQuarter,
    )}. That is up from ${formatCompact(
      premiumTotals.previousQuarter,
    )} the quarter before. The increase is concentrated in the Star Health family floater renewal, which moved to a higher age band.`,
    grounded: true,
  },
]

const BAR_COLOUR = ['bg-primary', 'bg-tertiary', 'bg-secondary', 'bg-primary-container']

export function AiOracle() {
  const [messages, setMessages] = useState<ChatMessage[]>(SEEDED)
  const [draft, setDraft] = useState('')
  const [thinking, setThinking] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, thinking])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const question = draft.trim()
    if (!question || thinking) return

    setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: 'user', text: question }])
    setDraft('')
    setThinking(true)

    // Placeholder until rag-service exposes POST /query. See README for the contract.
    window.setTimeout(() => {
      setThinking(false)
      setMessages((prev) => [
        ...prev,
        {
          id: `o-${Date.now()}`,
          role: 'oracle',
          text: 'Not connected yet — this UI is running on mock AA data. Once rag-service exposes its query endpoint, this answer will be retrieved from your normalized records and computed in code, with the source records cited below it.',
        },
      ])
    }, 1100)
  }

  const share = (amount: number) => (amount / premiumTotals.currentQuarter) * 100
  const deltaPct =
    ((premiumTotals.currentQuarter - premiumTotals.previousQuarter) /
      premiumTotals.previousQuarter) *
    100

  return (
    <div className="flex h-[calc(100vh-136px)] w-full lg:h-[calc(100vh-80px)]">
      {/* Knowledge base */}
      <aside className="hidden w-80 shrink-0 flex-col overflow-y-auto bg-surface-container/50 backdrop-blur-xl lg:flex">
        <div className="p-8 pb-4">
          <h2 className="mb-2 text-headline-sm text-on-surface">Knowledge Base</h2>
          <p className="text-body-md text-on-surface-variant opacity-80">
            Grounded in your consented records.
          </p>
        </div>
        <div className="space-y-6 px-4 pb-8">
          {knowledgeBase.map((group) => (
            <div key={group.category}>
              <div className="mb-1 flex items-center gap-2 px-4 py-2 opacity-50">
                <Icon name={group.icon} size={16} />
                <span className="text-label-sm uppercase tracking-widest text-on-surface">
                  {group.category}
                </span>
              </div>
              <ul className="space-y-1">
                {group.items.map((item) => {
                  const active = item === 'Insurance Policies'
                  return (
                    <li key={item}>
                      <button
                        type="button"
                        className={`group relative flex w-full items-center justify-between overflow-hidden rounded px-4 py-3 text-left text-body-md transition-colors ${
                          active
                            ? 'bg-primary/10 text-primary'
                            : 'text-on-surface hover:bg-surface-container-high'
                        }`}
                      >
                        {active && (
                          <span className="absolute bottom-0 left-0 top-0 w-1 rounded-r-full bg-primary" />
                        )}
                        {item}
                        <Icon
                          name="chevron_right"
                          size={18}
                          className={
                            active ? '' : 'opacity-0 transition-opacity group-hover:opacity-100'
                          }
                        />
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </div>

        <div className="relative mt-auto p-8">
          <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-surface-container-lowest via-surface-container-lowest/50 to-transparent" />
          <div className="relative z-10 rounded-md bg-surface-container-high p-4 shadow-lg">
            <div className="mb-2 flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-on-primary">
                <Icon name="auto_awesome" size={18} />
              </div>
              <span className="text-label-md text-on-surface">Index Status</span>
            </div>
            <div className="mt-2 h-1 w-full rounded-full bg-surface-container-highest">
              <div className="h-1 w-3/4 animate-pulse rounded-full bg-primary" />
            </div>
            <div className="mt-2 flex justify-between text-label-sm text-on-surface-variant">
              <span>Embeddings</span>
              <span className="text-primary">Synchronised</span>
            </div>
          </div>
        </div>
      </aside>

      {/* Chat */}
      <section className="relative flex flex-1 flex-col bg-surface-dim">
        <div className="pointer-events-none absolute inset-0 z-0 overflow-hidden">
          <div className="aura -right-[5%] -top-[10%] h-[40vw] w-[40vw] bg-primary/5" />
          <div className="aura bottom-[20%] left-[10%] h-[30vw] w-[30vw] bg-secondary/5" />
        </div>

        <div className="z-10 flex h-20 shrink-0 items-center justify-between bg-surface/50 px-6 backdrop-blur-md lg:px-8">
          <div className="min-w-0">
            <h1 className="truncate text-headline-sm text-on-surface lg:text-headline-md">
              Oracle Session
            </h1>
            <p className="mt-1 truncate text-label-md text-on-surface-variant opacity-70">
              Scope: Insurance Policies · 4 consented records
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              aria-label="Export transcript"
              className="rounded-full p-2 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            >
              <Icon name="download" />
            </button>
            <button
              type="button"
              aria-label="More options"
              className="rounded-full p-2 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            >
              <Icon name="more_horiz" />
            </button>
          </div>
        </div>

        <div ref={scrollRef} className="z-10 flex-1 space-y-10 overflow-y-auto p-6 lg:p-8">
          <div className="flex justify-center">
            <span className="rounded-full bg-surface-container-high px-4 py-1 text-label-sm text-on-surface-variant opacity-50 backdrop-blur-sm">
              Today
            </span>
          </div>

          {messages.map((message) =>
            message.role === 'user' ? (
              <div key={message.id} className="mx-auto flex w-full max-w-4xl justify-end">
                <div className="max-w-[85%] rounded-lg rounded-br-sm bg-surface-container-highest p-5 shadow-md">
                  <p className="text-body-lg text-on-surface">{message.text}</p>
                </div>
              </div>
            ) : (
              <div key={message.id} className="mx-auto flex w-full max-w-4xl justify-start">
                <div className="flex max-w-[95%] items-start gap-4">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/20 shadow-glow ring-1 ring-primary/30">
                    <Icon name="auto_awesome" className="text-primary" size={20} />
                  </div>
                  <div className="space-y-6 rounded-lg rounded-tl-sm bg-surface-container p-5 shadow-lg backdrop-blur-xl lg:p-6">
                    <p className="text-body-lg text-on-surface">{message.text}</p>

                    {message.grounded && (
                      <>
                        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
                          <Metric label="Premiums this quarter">
                            <Money
                              value={premiumTotals.currentQuarter}
                              className="text-headline-md text-on-surface"
                            />
                          </Metric>
                          <Metric
                            label="Change QoQ"
                            trend={{ up: deltaPct > 0, text: `${deltaPct.toFixed(1)}% QoQ` }}
                          >
                            <span className="text-headline-md text-error">
                              +{deltaPct.toFixed(1)}%
                            </span>
                          </Metric>
                          <Metric label="Active policies">
                            <span className="text-headline-md text-secondary">
                              {premiumTotals.policiesActive}
                            </span>
                          </Metric>
                        </div>

                        <div className="rounded-md bg-surface-dim p-5">
                          <div className="mb-6 flex items-center justify-between">
                            <h3 className="text-label-md uppercase tracking-widest text-on-surface">
                              Premiums by policy
                            </h3>
                            <span className="text-label-sm text-on-surface-variant">
                              Last quarter
                            </span>
                          </div>
                          <div className="space-y-4">
                            {premiumBreakdown.map((row, index) => (
                              <div key={row.recordId}>
                                <div className="mb-1 flex justify-between text-label-sm text-on-surface">
                                  <span>{row.policy}</span>
                                  <span className="text-on-surface-variant">
                                    <Money value={row.amount} /> · {share(row.amount).toFixed(0)}%
                                  </span>
                                </div>
                                <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-container-highest">
                                  <div
                                    className={`h-full animate-grow rounded-full ${BAR_COLOUR[index % BAR_COLOUR.length]}`}
                                    style={{
                                      width: `${share(row.amount)}%`,
                                      animationDelay: `${index * 90}ms`,
                                    }}
                                  />
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>

                        {/* Source traceability — which records produced the number. */}
                        <div className="rounded-md border border-outline-variant/30 bg-surface-container-high/50 p-4">
                          <div className="mb-3 flex items-center gap-2 text-label-sm uppercase tracking-widest text-on-surface-variant">
                            <Icon name="link" size={16} />
                            Sources · computed from {premiumTotals.recordsScanned} records
                          </div>
                          <div className="flex flex-wrap gap-2">
                            {premiumBreakdown.map((row) => (
                              <span
                                key={row.recordId}
                                className="rounded-full bg-surface-container-highest px-3 py-1 font-mono text-label-sm text-on-surface-variant"
                              >
                                {row.recordId}
                              </span>
                            ))}
                          </div>
                          <p className="mt-3 text-label-sm leading-relaxed text-on-surface-variant opacity-70">
                            Totals are summed in code over the retrieved records, not generated by
                            the model.
                          </p>
                        </div>
                      </>
                    )}
                  </div>
                </div>
              </div>
            ),
          )}

          {thinking && (
            <div className="mx-auto flex w-full max-w-4xl items-center gap-3 pl-14 text-label-md text-on-surface-variant">
              <span className="flex gap-1">
                {[0, 150, 300].map((delay) => (
                  <span
                    key={delay}
                    className="h-1.5 w-1.5 animate-pulse rounded-full bg-on-surface-variant"
                    style={{ animationDelay: `${delay}ms` }}
                  />
                ))}
              </span>
              Retrieving records…
            </div>
          )}
        </div>

        <form onSubmit={handleSubmit} className="z-10 shrink-0 px-6 pb-6 lg:px-8">
          <div className="mx-auto flex w-full max-w-4xl items-center gap-3 rounded-lg border border-white/5 bg-surface-container/60 px-4 py-3 backdrop-blur-xl transition-colors focus-within:border-primary/50">
            <Icon name="attach_file" className="text-on-surface-variant" />
            <input
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Ask about your accounts…"
              aria-label="Ask the Oracle"
              className="flex-1 bg-transparent text-body-md text-on-surface outline-none placeholder:text-on-surface-variant/60"
            />
            <button
              type="submit"
              disabled={!draft.trim() || thinking}
              aria-label="Send"
              className="flex h-10 w-10 items-center justify-center rounded bg-primary text-on-primary transition-opacity disabled:opacity-40"
            >
              <Icon name="send" size={20} />
            </button>
          </div>
          <p className="mt-3 flex items-center justify-center gap-2 text-label-sm text-on-surface-variant opacity-60">
            <Icon name="lock" size={14} />
            Answers are grounded in your consented records
          </p>
        </form>
      </section>
    </div>
  )
}

function Metric({
  label,
  children,
  trend,
}: {
  label: string
  children: React.ReactNode
  trend?: { up: boolean; text: string }
}) {
  return (
    <div className="rounded-md bg-surface-container-high p-4">
      <div className="mb-2 text-label-sm uppercase tracking-wider text-on-surface-variant">
        {label}
      </div>
      {children}
      {trend && (
        <div
          className={`mt-1 flex items-center gap-1 text-label-sm ${trend.up ? 'text-error' : 'text-success'}`}
        >
          <Icon name={trend.up ? 'arrow_upward' : 'arrow_downward'} size={14} />
          {trend.text}
        </div>
      )}
    </div>
  )
}
