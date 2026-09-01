import { useEffect, useMemo, useState } from 'react'
import { Icon } from './Icon'
import { fipCatalogue, type FipCandidate } from '../data/mock'
import { FI_TYPE_ICON, FI_TYPE_LABEL, type ConsentArtifact, type FipAccount } from '../types/fi'
import { formatDate } from '../lib/format'

/** What the modal hands back once the user approves the consent request. */
export interface LinkRequest {
  /** Set when approving an account that already exists in the list. */
  accountId?: string
  /** Set when linking a brand-new FIP from the catalogue. */
  candidate?: FipCandidate
  consent: ConsentArtifact
}

interface ConnectAccountModalProps {
  open: boolean
  /** When provided, the FIP picker is skipped and consent runs for this account. */
  account?: FipAccount | null
  /** FIPs already linked — filtered out of the picker so nothing is duplicated. */
  linkedFipIds: string[]
  onClose: () => void
  onApprove: (request: LinkRequest) => void
}

type Step = 'pick' | 'consent'

const DAY = 86_400_000

/** Builds the consent artefact the user is being asked to approve. */
function draftConsent(fiType: FipCandidate['fiType']): ConsentArtifact {
  const now = Date.now()
  return {
    consentId: `CN-${Math.random().toString(16).slice(2, 6)}-${Math.random().toString(16).slice(2, 6)}`,
    status: 'ACTIVE',
    purposeCode: '101',
    purposeText: 'Wealth management service',
    consentStart: new Date(now).toISOString(),
    consentExpiry: new Date(now + 365 * DAY).toISOString(),
    fetchType: 'PERIODIC',
    frequency: { unit: 'DAY', value: 4 },
    fiTypes: [fiType],
  }
}

export function ConnectAccountModal({
  open,
  account,
  linkedFipIds,
  onClose,
  onApprove,
}: ConnectAccountModalProps) {
  // An existing pending account arrives with its FIP already decided.
  const [step, setStep] = useState<Step>(account ? 'consent' : 'pick')
  const [selected, setSelected] = useState<FipCandidate | null>(null)

  useEffect(() => {
    if (!open) return
    setStep(account ? 'consent' : 'pick')
    setSelected(
      account
        ? {
            fipId: account.fipId,
            fipName: account.fipName,
            fiType: account.fiType,
            maskedAccountNumber: account.maskedAccountNumber,
          }
        : null,
    )
  }, [open, account])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const available = useMemo(
    () => fipCatalogue.filter((fip) => !linkedFipIds.includes(fip.fipId)),
    [linkedFipIds],
  )

  const consent = useMemo(
    () => (selected ? draftConsent(selected.fiType) : null),
    [selected],
  )

  if (!open) return null

  function choose(fip: FipCandidate) {
    setSelected(fip)
    setStep('consent')
  }

  function approve() {
    if (!selected || !consent) return
    onApprove(
      account
        ? { accountId: account.id, consent }
        : { candidate: selected, consent },
    )
  }

  return (
    <div
      className="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 p-4 backdrop-blur-sm sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-label="Link an account via Account Aggregator"
      onClick={onClose}
    >
      <div
        className="glass-floating relative max-h-[90vh] w-full max-w-lg overflow-y-auto bg-surface-container-low/90 p-6 shadow-2xl sm:p-8"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="aura -right-24 -top-24 h-64 w-64 bg-primary/10" />

        <div className="relative z-10">
          <div className="mb-6 flex items-start justify-between gap-4">
            <div>
              <div className="mb-2 flex items-center gap-2 text-label-sm uppercase tracking-[0.2em] text-primary">
                <span className="h-2 w-2 animate-pulse rounded-full bg-primary shadow-glow-sm" />
                Account Aggregator
              </div>
              <h2 className="text-headline-sm text-on-surface">
                {step === 'pick' ? 'Choose a provider' : 'Review consent request'}
              </h2>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded-full p-2 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            >
              <Icon name="close" size={20} />
            </button>
          </div>

          {/* Step indicator */}
          <div className="mb-8 flex items-center gap-2">
            {(['pick', 'consent'] as Step[]).map((key, index) => {
              const done = step === 'consent' && key === 'pick'
              const active = step === key
              return (
                <div key={key} className="flex flex-1 items-center gap-2">
                  <span
                    className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-label-sm transition-colors ${
                      active || done
                        ? 'bg-primary text-on-primary'
                        : 'bg-surface-container text-on-surface-variant'
                    }`}
                  >
                    {done ? <Icon name="check" size={14} /> : index + 1}
                  </span>
                  <span
                    className={`text-label-sm ${active ? 'text-on-surface' : 'text-on-surface-variant'}`}
                  >
                    {key === 'pick' ? 'Provider' : 'Consent'}
                  </span>
                  {index === 0 && <div className="h-px flex-1 bg-outline-variant/30" />}
                </div>
              )
            })}
          </div>

          {step === 'pick' && (
            <div className="space-y-2">
              {available.length === 0 && (
                <p className="rounded-md border border-outline-variant/30 bg-surface-container/40 p-6 text-center text-body-md text-on-surface-variant">
                  Every provider in the catalogue is already linked.
                </p>
              )}
              {available.map((fip) => (
                <button
                  key={fip.fipId}
                  type="button"
                  onClick={() => choose(fip)}
                  className="group flex w-full items-center gap-4 rounded-md border border-white/5 bg-surface-container/40 p-4 text-left transition-all hover:border-primary/30 hover:bg-surface-container/70"
                >
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-md bg-surface-container-highest">
                    <Icon name={FI_TYPE_ICON[fip.fiType]} className="text-primary" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-label-md text-on-surface">{fip.fipName}</div>
                    <div className="truncate font-mono text-label-sm uppercase text-on-surface-variant">
                      {FI_TYPE_LABEL[fip.fiType]} · {fip.maskedAccountNumber}
                    </div>
                  </div>
                  <Icon
                    name="chevron_right"
                    className="text-on-surface-variant transition-transform group-hover:translate-x-1"
                  />
                </button>
              ))}
            </div>
          )}

          {step === 'consent' && selected && consent && (
            <div className="space-y-6">
              <div className="flex items-center gap-4 rounded-md border border-white/5 bg-surface-container/40 p-4">
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-md bg-surface-container-highest">
                  <Icon name={FI_TYPE_ICON[selected.fiType]} className="text-primary" />
                </div>
                <div className="min-w-0">
                  <div className="truncate text-label-md text-on-surface">{selected.fipName}</div>
                  <div className="truncate font-mono text-label-sm uppercase text-on-surface-variant">
                    {FI_TYPE_LABEL[selected.fiType]} · {selected.maskedAccountNumber}
                  </div>
                </div>
              </div>

              <dl className="divide-y divide-outline-variant/20 rounded-md border border-outline-variant/30 bg-surface-container-lowest/50">
                <Row label="Purpose">
                  {consent.purposeCode} · {consent.purposeText}
                </Row>
                <Row label="Fetch type">{consent.fetchType}</Row>
                <Row label="Frequency">
                  {consent.frequency
                    ? `${consent.frequency.value}× / ${consent.frequency.unit.toLowerCase()}`
                    : 'One-time'}
                </Row>
                <Row label="FI types">
                  {consent.fiTypes.map((type) => FI_TYPE_LABEL[type]).join(', ')}
                </Row>
                <Row label="Valid until">{formatDate(consent.consentExpiry)}</Row>
              </dl>

              <p className="flex items-start gap-2 text-label-sm leading-relaxed text-on-surface-variant">
                <Icon name="info" size={16} className="mt-px shrink-0" />
                Approving grants read-only access within this purpose and window. You can revoke it
                at any time from the Consent screen.
              </p>

              <div className="flex flex-col gap-3 sm:flex-row">
                {!account && (
                  <button
                    type="button"
                    onClick={() => setStep('pick')}
                    className="rounded border border-white/10 bg-surface-container/50 px-6 py-3 text-label-md text-on-surface transition-colors hover:bg-surface-container"
                  >
                    Back
                  </button>
                )}
                <button
                  type="button"
                  onClick={approve}
                  className="group relative flex-1 overflow-hidden rounded bg-primary px-6 py-3 text-label-md text-on-primary shadow-glow transition-all hover:shadow-glow-lg"
                >
                  <div className="absolute inset-x-0 top-0 h-px bg-white/40" />
                  <span className="flex items-center justify-center gap-2">
                    <Icon name="verified_user" size={18} fill={1} />
                    Approve consent
                  </span>
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 px-4 py-3">
      <dt className="text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</dt>
      <dd className="text-right text-label-md text-on-surface">{children}</dd>
    </div>
  )
}
