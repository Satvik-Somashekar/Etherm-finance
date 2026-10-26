import { useMemo, useState } from 'react'
import { GlassCard } from '../components/GlassCard'
import { Icon } from '../components/Icon'
import { Money } from '../components/Money'
import { ConnectAccountModal } from '../components/ConnectAccountModal'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ExportCard } from '../components/ExportCard'
import { useAccounts, type LinkRequest } from '../state/AccountsContext'
import { FI_TYPE_ICON, FI_TYPE_LABEL, type FipAccount, type FiType } from '../types/fi'
import { daysUntil, timeAgo } from '../lib/format'

type Filter = FiType | 'ALL'

export function Accounts() {
  const { accounts: list, syncingIds, netWorth, availableFips, link, remove } = useAccounts()

  const [filter, setFilter] = useState<Filter>('ALL')
  const [modalOpen, setModalOpen] = useState(false)
  const [approvingAccount, setApprovingAccount] = useState<FipAccount | null>(null)
  const [removing, setRemoving] = useState<FipAccount | null>(null)

  const counts = useMemo(() => {
    const map = new Map<FiType, number>()
    for (const account of list) {
      map.set(account.fiType, (map.get(account.fiType) ?? 0) + 1)
    }
    return map
  }, [list])

  const visible = useMemo(
    () => (filter === 'ALL' ? list : list.filter((a) => a.fiType === filter)),
    [filter, list],
  )

  const filters: Filter[] = ['ALL', ...counts.keys()]

  function openPicker() {
    setApprovingAccount(null)
    setModalOpen(true)
  }

  function openApproval(account: FipAccount) {
    setApprovingAccount(account)
    setModalOpen(true)
  }

  function handleApprove(request: LinkRequest) {
    setModalOpen(false)
    setApprovingAccount(null)
    link(request)
  }

  function confirmRemove() {
    if (!removing) return
    remove(removing.id)
    setRemoving(null)
  }

  return (
    <div className="flex w-full flex-col gap-gutter px-margin-mobile py-12 lg:flex-row lg:px-margin-desktop">
      <aside className="flex w-full shrink-0 flex-col gap-8 lg:sticky lg:top-[120px] lg:h-fit lg:w-64">
        <div className="flex flex-col gap-2">
          <h2 className="mb-4 text-headline-sm uppercase tracking-wider text-on-background opacity-90">
            FI Types
          </h2>
          {filters.map((key) => {
            const active = filter === key
            return (
              <button
                key={key}
                type="button"
                onClick={() => setFilter(key)}
                className={`group flex w-full items-center justify-between rounded px-4 py-3 text-left transition-all ${
                  active
                    ? 'bg-surface-container text-primary'
                    : 'text-on-surface-variant hover:bg-surface-container/50 hover:text-on-surface'
                }`}
              >
                <span className="text-label-md">
                  {key === 'ALL' ? 'All Accounts' : FI_TYPE_LABEL[key]}
                </span>
                <span className="text-label-sm opacity-60">
                  {key === 'ALL' ? list.length : counts.get(key)}
                </span>
              </button>
            )
          })}
        </div>

        <div className="relative overflow-hidden rounded-md bg-surface-container-high p-6">
          <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-primary/10 to-transparent" />
          <Icon name="verified_user" className="mb-4 block text-primary" fill={1} />
          <h3 className="mb-2 text-label-md text-on-surface">Consent-Based Access</h3>
          <p className="mb-4 text-label-sm leading-relaxed text-on-surface-variant">
            Data is fetched only within the purpose and time window you approved in your Account
            Aggregator app. Revoke any time.
          </p>
          <button
            type="button"
            className="flex items-center gap-1 text-label-sm text-primary transition-opacity hover:opacity-80"
          >
            View audit log
            <Icon name="open_in_new" size={16} />
          </button>
        </div>

        <ExportCard />
      </aside>

      <div className="flex flex-1 flex-col gap-8">
        <div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <h1 className="mb-2 text-headline-lg-mobile text-on-background lg:text-headline-lg">
              Linked Accounts
            </h1>
            <p className="text-body-md text-on-surface-variant">
              Financial Information Providers sharing data under your active consents.
            </p>
          </div>
          <button
            type="button"
            onClick={openPicker}
            className="group relative shrink-0 overflow-hidden rounded bg-primary px-6 py-3 text-label-md text-on-primary shadow-glow transition-all hover:shadow-glow-lg"
          >
            <div className="absolute inset-x-0 top-0 h-px bg-white/40" />
            <span className="flex items-center gap-2">
              <Icon name="add" size={20} />
              Link via AA
            </span>
          </button>
        </div>

        <GlassCard className="w-full bg-surface-container-lowest/80 p-8">
          <div className="aura -right-32 -top-32 h-96 w-96 bg-primary/10" />
          <div className="relative z-10 flex flex-col items-start justify-between gap-6 md:flex-row md:items-end">
            <div>
              <span className="label-technical mb-2 block">Aggregated Balance</span>
              <Money
                value={netWorth}
                variant="split"
                className="text-[40px] leading-none tracking-tighter text-on-surface sm:text-[64px]"
                fractionClassName="text-on-surface-variant/50 text-headline-md"
              />
            </div>
            <div className="flex items-center gap-4 rounded-full border border-white/5 bg-surface/50 px-4 py-2 backdrop-blur-md">
              <span
                className={`h-2 w-2 animate-pulse rounded-full ${
                  syncingIds.length > 0 ? 'bg-primary' : 'bg-success'
                }`}
              />
              <span className="font-mono text-label-sm text-on-surface">
                AA SYNC:{' '}
                <span className={syncingIds.length > 0 ? 'text-primary' : 'text-success'}>
                  {syncingIds.length > 0 ? 'FETCHING' : 'LIVE'}
                </span>
              </span>
            </div>
          </div>
        </GlassCard>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
          {visible.map((account) => {
            const syncing = syncingIds.includes(account.id)
            const pending = account.consent.status === 'PENDING'

            if (pending) {
              return (
                <div
                  key={account.id}
                  className="group relative overflow-hidden rounded-lg border border-dashed border-white/10 bg-surface-container-lowest/30 p-6 opacity-60 backdrop-blur-xl transition-all hover:opacity-100"
                >
                  <div className="mb-12 flex items-start justify-between">
                    <div className="flex h-12 w-12 items-center justify-center rounded-md bg-surface-container">
                      <Icon name="hourglass_top" className="text-on-surface-variant" />
                    </div>
                    <div className="rounded-full border border-outline-variant/40 px-3 py-1">
                      <span className="text-label-sm text-on-surface-variant">Consent pending</span>
                    </div>
                  </div>
                  <h3 className="mb-1 text-headline-sm text-on-surface">{account.fipName}</h3>
                  <p className="mb-6 font-mono text-label-sm uppercase text-on-surface-variant">
                    {FI_TYPE_LABEL[account.fiType]} · {account.maskedAccountNumber}
                  </p>
                  <div className="flex items-center justify-between gap-4">
                    <button
                      type="button"
                      onClick={() => openApproval(account)}
                      className="flex items-center gap-1 text-label-md text-primary transition-opacity hover:opacity-80"
                    >
                      Approve in AA app
                      <Icon name="arrow_forward" size={18} />
                    </button>
                    <button
                      type="button"
                      onClick={() => setRemoving(account)}
                      aria-label={`Cancel consent request for ${account.fipName}`}
                      className="p-2 text-outline-variant transition-colors hover:text-error"
                    >
                      <Icon name="delete" size={20} />
                    </button>
                  </div>
                </div>
              )
            }

            return (
              <GlassCard key={account.id} interactive className="bg-surface-container/40 p-6">
                <div className="relative z-10 mb-12 flex items-start justify-between">
                  <div className="flex h-12 w-12 items-center justify-center rounded-md bg-surface-container-highest shadow-inner">
                    <Icon name={FI_TYPE_ICON[account.fiType]} className="text-primary" />
                  </div>
                  <div className="flex items-center gap-2 rounded-full bg-surface-container-highest px-3 py-1">
                    <span
                      className={`h-1.5 w-1.5 rounded-full shadow-glow-sm ${
                        syncing ? 'animate-pulse bg-primary' : 'bg-primary'
                      }`}
                    />
                    <span className="text-label-sm text-on-surface opacity-80">
                      {syncing ? 'Syncing…' : `Synced ${timeAgo(account.lastSyncedAt)}`}
                    </span>
                  </div>
                </div>
                <div className="relative z-10">
                  <h3 className="mb-1 text-headline-sm text-on-surface">{account.fipName}</h3>
                  <p className="mb-6 font-mono text-label-sm uppercase text-on-surface-variant">
                    {FI_TYPE_LABEL[account.fiType]} · {account.maskedAccountNumber}
                  </p>
                  {syncing ? (
                    <div className="flex items-center gap-3">
                      <span className="h-8 w-40 animate-pulse rounded bg-surface-container-highest" />
                      <span className="text-label-sm text-on-surface-variant">Fetching…</span>
                    </div>
                  ) : (
                    <Money
                      value={account.balance}
                      variant="split"
                      className="text-headline-md text-on-surface"
                      fractionClassName="text-label-sm text-on-surface-variant"
                    />
                  )}
                  <div className="mt-6 flex items-center justify-between gap-2 border-t border-outline-variant/20 pt-4 text-label-sm text-on-surface-variant">
                    <span className="flex items-center gap-2">
                      <Icon name="schedule" size={14} />
                      Consent expires in {daysUntil(account.consent.consentExpiry)} days
                    </span>
                    <button
                      type="button"
                      onClick={() => setRemoving(account)}
                      aria-label={`Revoke consent and unlink ${account.fipName}`}
                      className="-mr-2 p-2 text-outline-variant opacity-0 transition-colors hover:text-error focus-visible:opacity-100 group-hover:opacity-100"
                    >
                      <Icon name="link_off" size={20} />
                    </button>
                  </div>
                </div>
              </GlassCard>
            )
          })}
        </div>
      </div>

      <ConnectAccountModal
        open={modalOpen}
        account={approvingAccount}
        candidates={availableFips}
        onClose={() => {
          setModalOpen(false)
          setApprovingAccount(null)
        }}
        onApprove={handleApprove}
      />

      <ConfirmDialog
        open={removing !== null}
        icon={removing?.consent.status === 'PENDING' ? 'cancel' : 'link_off'}
        title={
          removing?.consent.status === 'PENDING'
            ? 'Cancel consent request?'
            : 'Revoke consent and unlink?'
        }
        confirmLabel={removing?.consent.status === 'PENDING' ? 'Cancel request' : 'Revoke & unlink'}
        cancelLabel="Keep account"
        onCancel={() => setRemoving(null)}
        onConfirm={confirmRemove}
      >
        {removing && (
          <>
            <p>
              <span className="text-on-surface">{removing.fipName}</span>{' '}
              <span className="font-mono text-label-sm uppercase">
                · {removing.maskedAccountNumber}
              </span>{' '}
              will be unlinked, and {removing.fipName} will stop sharing data under consent{' '}
              <span className="font-mono text-label-sm">{removing.consent.consentId}</span>.
            </p>
            {removing.consent.status === 'ACTIVE' && (
              <p className="flex items-center justify-between rounded-md border border-outline-variant/30 bg-surface-container-lowest/50 px-4 py-3">
                <span className="text-label-sm uppercase tracking-wider">
                  Removed from your total
                </span>
                <Money value={removing.balance} className="text-label-md text-error" />
              </p>
            )}
            <p className="text-label-sm">
              You can link it again at any time, but the historical data fetched under this consent
              will not be restored.
            </p>
          </>
        )}
      </ConfirmDialog>
    </div>
  )
}
