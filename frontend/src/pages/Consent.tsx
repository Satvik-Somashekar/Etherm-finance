import { useMemo, useState } from 'react'
import { GlassCard } from '../components/GlassCard'
import { Icon } from '../components/Icon'
import { profile, sessions } from '../data/mock'
import { FI_TYPE_LABEL } from '../types/fi'
import { daysUntil, formatDate } from '../lib/format'
import { usePrivacy } from '../state/PrivacyContext'
import { useAccounts } from '../state/AccountsContext'
import { CUSTOMER_ID } from '../lib/api'

const PANELS = [
  {
    icon: 'rule_settings',
    tone: 'text-primary',
    title: 'Consent Scopes',
    body: 'Every fetch is bound to the purpose code and date range you approved. Requests outside that window are rejected before they reach a provider.',
    footer: 'Purpose 101 · Wealth management',
  },
  {
    icon: 'schedule',
    tone: 'text-secondary',
    title: 'Data Retention',
    body: 'Revoking a consent deletes the data fetched under it. Once a consent expires, nothing fetched under it can be read or used in an answer.',
    footer: 'Deleted on revoke',
  },
  {
    icon: 'alternate_email',
    tone: 'text-tertiary',
    title: 'AA Handle',
    body: 'Your Virtual User Address identifies you to the Account Aggregator. It carries no account numbers and can be rotated.',
    footer: profile.vua,
  },
  {
    icon: 'receipt_long',
    tone: 'text-primary',
    title: 'Answer Citations',
    body: 'Every Oracle answer lists the consent artefacts it read under and the record ids each figure was computed from.',
    footer: 'Per-answer sources',
  },
]

export function Consent() {
  const { masked, toggle } = usePrivacy()
  const [copied, setCopied] = useState(false)

  const { accounts, source } = useAccounts()
  const customerId = source === 'live' ? CUSTOMER_ID : profile.customerId
  // One row per consent artefact; a consent can cover several accounts at one provider.
  const activeConsents = useMemo(
    () => [
      ...new Map(
        accounts.filter((a) => a.consent.status === 'ACTIVE').map((a) => [a.consent.consentId, a]),
      ).values(),
    ],
    [accounts],
  )
  const soonest = useMemo(
    () =>
      [...activeConsents].sort(
        (a, b) => daysUntil(a.consent.consentExpiry) - daysUntil(b.consent.consentExpiry),
      )[0],
    [activeConsents],
  )

  async function copyId() {
    await navigator.clipboard?.writeText(customerId)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="relative flex w-full flex-col overflow-hidden">
      <div className="pointer-events-none absolute inset-0 -z-10">
        <div className="aura right-0 top-0 h-[800px] w-[800px] translate-x-1/3 -translate-y-1/4 bg-primary/5" />
        <div className="aura bottom-0 left-0 h-[600px] w-[600px] -translate-x-1/4 translate-y-1/4 bg-secondary/5" />
      </div>

      <div className="mx-auto grid w-full max-w-container-max grid-cols-12 gap-gutter px-margin-mobile py-12 lg:px-margin-desktop">
        {/* Profile header */}
        <div className="col-span-12 mb-8 flex flex-col items-start justify-between gap-6 md:flex-row md:items-end">
          <div className="flex items-center gap-6">
            <div className="relative">
              <div className="flex h-24 w-24 items-center justify-center rounded-full border border-primary/20 bg-surface-container-high text-headline-md text-primary">
                {profile.name
                  .split(' ')
                  .map((part) => part[0])
                  .join('')}
              </div>
              <div className="absolute inset-0 rounded-full bg-primary/20 opacity-40 blur-xl" />
            </div>
            <div>
              <div className="mb-2 flex flex-wrap items-center gap-3">
                <h1 className="text-headline-lg-mobile text-on-background lg:text-headline-lg">
                  {profile.name}
                </h1>
                <span className="inline-flex items-center gap-1 rounded-full border border-primary/30 bg-surface-container-high/80 px-3 py-1 text-primary backdrop-blur-sm">
                  <Icon name="verified" size={16} fill={1} />
                  <span className="text-label-sm uppercase tracking-widest">{profile.tier}</span>
                </span>
              </div>
              <p className="flex items-center gap-2 text-body-md text-on-surface-variant">
                ID: {customerId}
                <button
                  type="button"
                  onClick={copyId}
                  aria-label="Copy customer ID"
                  className="opacity-50 transition-opacity hover:opacity-100"
                >
                  <Icon name={copied ? 'check' : 'content_copy'} size={14} />
                </button>
              </p>
            </div>
          </div>

          {/* Privacy Mode masks every rupee figure across the app. */}
          <div className="flex w-full items-center justify-between gap-6 rounded-md border border-surface-container-highest bg-surface-container-lowest/60 p-4 shadow-md backdrop-blur-xl transition-colors hover:border-primary/40 sm:w-72">
            <div>
              <div className="flex items-center gap-2 text-label-md text-on-surface">
                <Icon
                  name={masked ? 'visibility_off' : 'visibility'}
                  size={18}
                  className={masked ? 'text-primary' : 'text-on-surface-variant'}
                />
                Privacy Mode
              </div>
              <div className="mt-1 text-label-sm text-on-surface-variant">Hides all balances</div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={masked}
              aria-label="Privacy Mode"
              onClick={toggle}
              className={`relative h-6 w-11 shrink-0 rounded-full transition-colors ${
                masked ? 'bg-primary' : 'bg-surface-variant'
              }`}
            >
              <span
                className={`absolute top-[2px] h-5 w-5 rounded-full bg-on-background transition-all ${
                  masked ? 'left-[22px]' : 'left-[2px]'
                }`}
              />
            </button>
          </div>
        </div>

        {/* Left column */}
        <div className="col-span-12 flex flex-col gap-gutter lg:col-span-4">
          <GlassCard className="bg-surface-container-low/80 p-6">
            <div className="absolute right-0 top-0 p-4 opacity-10">
              <Icon name="encrypted" className="text-primary" size={120} />
            </div>
            <div className="relative z-10 flex h-full flex-col justify-between gap-8">
              <div>
                <div className="mb-4 flex items-center gap-2 text-label-sm uppercase tracking-[0.2em] text-primary">
                  <span className="h-2 w-2 animate-pulse rounded-full bg-primary shadow-glow-sm" />
                  Consent Posture
                </div>
                <div className="mb-2 text-headline-md text-on-background">
                  {activeConsents.length} active
                  <br />
                  consents
                </div>
                <p className="text-body-md leading-relaxed text-on-surface-variant">
                  Data flows only while these artefacts are valid. Revoking one stops all future
                  fetches from that provider immediately.
                </p>
              </div>
              <div className="flex items-center justify-between rounded-md border border-outline-variant/30 bg-surface-container-highest/50 p-4">
                <div className="flex flex-col gap-1">
                  <span className="text-label-sm text-on-surface-variant">Next expiry</span>
                  <span className="text-label-md text-on-surface">
                    {soonest ? formatDate(soonest.consent.consentExpiry) : '—'}
                  </span>
                </div>
                <button
                  type="button"
                  className="flex items-center gap-1 text-label-md text-primary transition-colors hover:text-primary-fixed"
                >
                  Manage
                  <Icon name="arrow_forward" size={18} />
                </button>
              </div>
            </div>
          </GlassCard>

          <GlassCard className="bg-surface-container-lowest/70 p-6">
            <h2 className="mb-6 flex items-center gap-2 text-headline-sm text-on-surface">
              <Icon name="devices" className="text-on-surface-variant" />
              Active Sessions
            </h2>
            <div className="flex flex-col gap-4">
              {sessions.map((session, index) => (
                <div key={session.id}>
                  {index > 0 && <div className="mb-4 h-px w-full bg-outline-variant/20" />}
                  <div className="group flex items-start justify-between">
                    <div className="flex items-center gap-4">
                      <div
                        className={`flex h-10 w-10 items-center justify-center rounded ${
                          session.current
                            ? 'border border-primary/20 bg-surface-container text-primary shadow-glow-sm'
                            : 'bg-surface-container text-on-surface-variant'
                        }`}
                      >
                        <Icon name={session.icon} />
                      </div>
                      <div>
                        <div className="text-label-md text-on-surface">{session.device}</div>
                        <div className="mt-1 flex items-center gap-1 text-label-sm text-on-surface-variant">
                          {session.current && (
                            <span className="h-1.5 w-1.5 rounded-full bg-secondary" />
                          )}
                          {session.detail}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      aria-label={`Sign out ${session.device}`}
                      className="p-2 text-outline-variant opacity-0 transition-colors hover:text-error group-hover:opacity-100"
                    >
                      <Icon name="logout" size={20} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>

        {/* Right column */}
        <div className="col-span-12 grid grid-cols-1 gap-gutter md:grid-cols-2 lg:col-span-8">
          {PANELS.map((panel) => (
            <GlassCard
              key={panel.title}
              interactive
              className="flex h-full flex-col justify-between bg-surface/60 p-6"
            >
              <div>
                <div className="relative mb-6 flex h-12 w-12 items-center justify-center overflow-hidden rounded-md bg-surface-container-high shadow-inner">
                  <Icon name={panel.icon} size={24} className={`z-10 ${panel.tone}`} />
                  <div className="absolute inset-0 translate-y-full bg-primary/10 transition-transform duration-500 ease-out group-hover:translate-y-0" />
                </div>
                <h3 className="mb-3 text-headline-sm text-on-surface">{panel.title}</h3>
                <p className="mb-6 text-body-md leading-relaxed text-on-surface-variant">
                  {panel.body}
                </p>
              </div>
              <div className="mt-auto flex items-center justify-between border-t border-outline-variant/20 pt-4">
                <span className="font-mono text-label-sm text-on-surface-variant">
                  {panel.footer}
                </span>
                <span className="text-label-md text-primary opacity-0 transition-opacity group-hover:opacity-100">
                  Configure
                </span>
              </div>
            </GlassCard>
          ))}

          {/* Consent ledger */}
          <GlassCard className="col-span-1 bg-surface-container-lowest/70 p-6 md:col-span-2">
            <h2 className="mb-6 flex items-center justify-between text-headline-sm text-on-surface">
              Consent Ledger
              <span className="text-label-sm text-on-surface-variant">
                {activeConsents.length} active
              </span>
            </h2>
            <div className="-mx-2 overflow-x-auto">
              <table className="w-full min-w-[560px] border-collapse">
                <thead>
                  <tr className="text-left text-label-sm uppercase tracking-widest text-on-surface-variant">
                    <th className="px-2 pb-3 font-medium">Provider</th>
                    <th className="px-2 pb-3 font-medium">FI Type</th>
                    <th className="px-2 pb-3 font-medium">Consent ID</th>
                    <th className="px-2 pb-3 font-medium">Frequency</th>
                    <th className="px-2 pb-3 text-right font-medium">Expires</th>
                  </tr>
                </thead>
                <tbody>
                  {activeConsents.map((account) => (
                    <tr
                      key={account.consent.consentId}
                      className="border-t border-outline-variant/20 text-label-md text-on-surface"
                    >
                      <td className="px-2 py-4">{account.fipName}</td>
                      <td className="px-2 py-4 text-on-surface-variant">
                        {FI_TYPE_LABEL[account.fiType]}
                      </td>
                      <td className="px-2 py-4 font-mono text-label-sm text-on-surface-variant">
                        {account.consent.consentId}
                      </td>
                      <td className="px-2 py-4 text-label-sm text-on-surface-variant">
                        {account.consent.frequency
                          ? `${account.consent.frequency.value}× / ${account.consent.frequency.unit.toLowerCase()}`
                          : 'One-time'}
                      </td>
                      <td className="px-2 py-4 text-right text-on-surface-variant">
                        {daysUntil(account.consent.consentExpiry)}d
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  )
}
