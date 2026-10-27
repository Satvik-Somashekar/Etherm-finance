import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Icon } from '../components/Icon'
import { Money } from '../components/Money'
import { premiumBreakdown, premiumTotals } from '../data/mock'
import { formatCompact } from '../lib/format'
import { ask, type QueryResult } from '../lib/api'
import { DEMO, DEMO_QUESTIONS } from '../lib/demo'
import { useAccounts } from '../state/AccountsContext'
import { GroundedAnswer } from '../components/GroundedAnswer'
import type { FiType } from '../types/fi'

interface ChatMessage {
  id: string
  role: 'user' | 'oracle'
  text: string
  /** Only the seeded mock answer carries the static grounded-result block. */
  grounded?: boolean
  /** A live answer from rag-service, with its Java-computed figures and citations. */
  result?: QueryResult
  /** A marker line shown when the user switches section, not a message. */
  divider?: boolean
  /** Suggested questions for the section a divider opens. */
  examples?: string[]
}

/** A Knowledge Base section: the account types the Oracle may look at while it is selected. */
interface Section {
  key: string
  label: string
  icon: string
  /** Empty means every consented account type. */
  scope: FiType[]
  examples: string[]
}

const SECTIONS: Section[] = [
  {
    key: 'all',
    label: 'All accounts',
    icon: 'hub',
    scope: [],
    examples: [
      'What is my net worth?',
      'How much did I spend on groceries last quarter?',
      'Show my 3 biggest expenses last month',
      'Export my transactions for last quarter as CSV',
    ],
  },
  {
    key: 'deposits',
    label: 'Deposits & Cashflow',
    icon: 'account_balance_wallet',
    scope: ['DEPOSIT'],
    examples: [
      'How much did I spend last month?',
      'What is my savings rate?',
      'Which merchants did I spend the most at?',
      'Export my transactions for last quarter as CSV',
    ],
  },
  {
    key: 'mf',
    label: 'Mutual Fund Holdings',
    icon: 'monitoring',
    scope: ['MUTUAL_FUNDS'],
    examples: ['What is the value of my mutual funds?', 'How much have I invested in mutual funds?'],
  },
  {
    key: 'stocks',
    label: 'Stocks & Demat',
    icon: 'candlestick_chart',
    scope: ['EQUITIES'],
    examples: ['What are my stock holdings worth?', 'Show my equity holdings'],
  },
  {
    key: 'insurance',
    label: 'Insurance Policies',
    icon: 'health_and_safety',
    scope: ['INSURANCE_POLICIES'],
    examples: [
      'What is my insurance cover?',
      'Did my insurance premiums increase last quarter compared to the one before?',
    ],
  },
  {
    key: 'epf',
    label: 'EPF & Retirement',
    icon: 'savings',
    scope: ['EPF'],
    examples: ['What are my EPF contributions this year?', 'What is my EPF balance?'],
  },
]

const INTRO: ChatMessage = {
  id: 'intro',
  role: 'oracle',
  text: 'Ask about your spending, salary, premiums, investments, EPF or balances, or ask for your transactions or a monthly report as a CSV file. Every figure is computed from your consented records, and each answer lists the records it used.',
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
  const { source } = useAccounts()
  const [stored, setMessages] = useState<ChatMessage[]>(SEEDED)
  // With the backend running, the static mock exchange makes way for live answers.
  const fromLive = (list: ChatMessage[]) => (source === 'live' && list === SEEDED ? [INTRO] : list)
  const messages = fromLive(stored)
  const [draft, setDraft] = useState('')
  const [thinking, setThinking] = useState(false)
  const [section, setSection] = useState<Section>(SECTIONS[0])
  const navigate = useNavigate()
  const examples = DEMO ? DEMO_QUESTIONS : section.examples
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, thinking])

  function switchSection(next: Section) {
    if (next.key === section.key) return
    setSection(next)
    setMessages((prev) => [
      ...fromLive(prev),
      {
        id: `d-${Date.now()}`,
        role: 'oracle',
        divider: true,
        examples: DEMO ? DEMO_QUESTIONS : next.examples,
        text: `Context: ${next.label}. From here the Oracle looks only at ${
          next.scope.length ? 'this section' : 'all your consented accounts'
        }.`,
      },
    ])
  }

  function send(question: string) {
    if (!question || thinking) return

    setMessages((prev) => [...fromLive(prev), { id: `u-${Date.now()}`, role: 'user', text: question }])
    setDraft('')
    setThinking(true)

    if (source !== 'live') {
      // Backend not running: say so rather than invent an answer.
      window.setTimeout(() => {
        setThinking(false)
        setMessages((prev) => [
          ...prev,
          {
            id: `o-${Date.now()}`,
            role: 'oracle',
            text: 'The Etherm services are not running, so this screen is showing mock data. Start the backend (see README) to get answers computed from your consented records.',
          },
        ])
      }, 600)
      return
    }

    ask(question, section.scope)
      .then((result) =>
        setMessages((prev) => [
          ...prev,
          { id: `o-${Date.now()}`, role: 'oracle', text: result.answer, result },
        ]),
      )
      .catch((e: Error) =>
        setMessages((prev) => [
          ...prev,
          { id: `o-${Date.now()}`, role: 'oracle', text: `I could not answer that: ${e.message}` },
        ]),
      )
      .finally(() => setThinking(false))
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    send(draft.trim())
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
          <div>
            <div className="mb-1 flex items-center gap-2 px-4 py-2 opacity-50">
              <Icon name="account_balance_wallet" size={16} />
              <span className="text-label-sm uppercase tracking-widest text-on-surface">
                Financial Information
              </span>
            </div>
            <ul className="space-y-1">
              {SECTIONS.map((item) => {
                const active = item.key === section.key
                return (
                  <li key={item.key}>
                    <button
                      type="button"
                      onClick={() => switchSection(item)}
                      aria-pressed={active}
                      className={`group relative flex w-full items-center justify-between overflow-hidden rounded px-4 py-3 text-left text-body-md transition-colors ${
                        active
                          ? 'bg-primary/10 text-primary'
                          : 'text-on-surface hover:bg-surface-container-high'
                      }`}
                    >
                      {active && (
                        <span className="absolute bottom-0 left-0 top-0 w-1 rounded-r-full bg-primary" />
                      )}
                      {item.label}
                      <Icon
                        name="chevron_right"
                        size={18}
                        className={active ? '' : 'opacity-0 transition-opacity group-hover:opacity-100'}
                      />
                    </button>
                  </li>
                )
              })}
            </ul>
          </div>
          <div>
            <div className="mb-1 flex items-center gap-2 px-4 py-2 opacity-50">
              <Icon name="verified_user" size={16} />
              <span className="text-label-sm uppercase tracking-widest text-on-surface">
                Consent & Audit
              </span>
            </div>
            <ul className="space-y-1">
              {['Active Consents', 'Data Access Log'].map((item) => (
                <li key={item}>
                  <button
                    type="button"
                    onClick={() => navigate('/consent')}
                    className="flex w-full items-center justify-between rounded px-4 py-3 text-left text-body-md text-on-surface transition-colors hover:bg-surface-container-high"
                  >
                    {item}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>

        <div className="relative mt-auto p-8">
          <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-surface-container-lowest via-surface-container-lowest/50 to-transparent" />
          <div className="relative z-10 rounded-md bg-surface-container-high p-4 shadow-lg">
            <div className="mb-2 flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-on-primary">
                <Icon name="auto_awesome" size={18} />
              </div>
              <span className="text-label-md text-on-surface">Retrieval</span>
            </div>
            <div className="mt-2 flex justify-between text-label-sm text-on-surface-variant">
              <span>Structured filters</span>
              <span className={source === 'live' ? 'text-primary' : 'text-on-surface-variant'}>
                {source === 'live' ? 'Connected' : 'Offline'}
              </span>
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
              {source === 'live'
                ? `Scope: ${section.scope.length ? section.label : 'all accounts'} · under your active consents`
                : 'Scope: Insurance Policies · 4 consented records (mock)'}
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
            message.divider ? (
              <div key={message.id} className="flex flex-col items-center gap-3">
                <span className="rounded-full bg-surface-container-high px-4 py-1 text-label-sm text-on-surface-variant">
                  {message.text}
                </span>
                <div className="flex max-w-4xl flex-wrap justify-center gap-2">
                  {message.examples?.map((example) => (
                    <button
                      key={example}
                      type="button"
                      onClick={() => send(example)}
                      className="rounded-full bg-surface-container-highest px-3 py-1.5 text-left text-label-md text-on-surface-variant transition-colors hover:text-on-surface"
                    >
                      {example}
                    </button>
                  ))}
                </div>
              </div>
            ) : message.role === 'user' ? (
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

                    {message.result && <GroundedAnswer result={message.result} />}

                    {message.id === 'intro' && (
                      <div className="flex flex-wrap gap-2">
                        {examples.map((example) => (
                          <button
                            key={example}
                            type="button"
                            onClick={() => send(example)}
                            className="rounded-full bg-surface-container-highest px-3 py-1.5 text-left text-label-md text-on-surface-variant transition-colors hover:text-on-surface"
                          >
                            {example}
                          </button>
                        ))}
                      </div>
                    )}

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
