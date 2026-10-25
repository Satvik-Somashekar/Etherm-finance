import { useEffect, type ReactNode } from 'react'
import { Icon } from './Icon'

interface ConfirmDialogProps {
  open: boolean
  title: string
  /** Icon shown in the header badge. */
  icon?: string
  children: ReactNode
  confirmLabel: string
  cancelLabel?: string
  /** `danger` paints the confirm action with the error token. */
  tone?: 'danger' | 'primary'
  onConfirm: () => void
  onCancel: () => void
}

/** Glass confirmation sheet, styled to match ConnectAccountModal. */
export function ConfirmDialog({
  open,
  title,
  icon = 'warning',
  children,
  confirmLabel,
  cancelLabel = 'Cancel',
  tone = 'danger',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  if (!open) return null

  const danger = tone === 'danger'

  return (
    <div
      className="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 p-4 backdrop-blur-sm sm:items-center"
      role="alertdialog"
      aria-modal="true"
      aria-label={title}
      onClick={onCancel}
    >
      <div
        className="glass-floating relative w-full max-w-md bg-surface-container-low/90 p-6 shadow-2xl sm:p-8"
        onClick={(event) => event.stopPropagation()}
      >
        <div
          className={`aura -right-24 -top-24 h-56 w-56 ${danger ? 'bg-error/10' : 'bg-primary/10'}`}
        />
        <div className="relative z-10">
          <div className="mb-5 flex items-start gap-4">
            <div
              className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-md ${
                danger ? 'bg-error/15 text-error' : 'bg-primary/15 text-primary'
              }`}
            >
              <Icon name={icon} />
            </div>
            <h2 className="mt-1 text-headline-sm text-on-surface">{title}</h2>
          </div>

          <div className="space-y-4 text-body-md leading-relaxed text-on-surface-variant">
            {children}
          </div>

          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <button
              type="button"
              onClick={onCancel}
              className="rounded border border-white/10 bg-surface-container/50 px-6 py-3 text-label-md text-on-surface transition-colors hover:bg-surface-container sm:flex-1"
            >
              {cancelLabel}
            </button>
            <button
              type="button"
              onClick={onConfirm}
              autoFocus
              className={`group relative overflow-hidden rounded px-6 py-3 text-label-md transition-all sm:flex-1 ${
                danger
                  ? 'bg-error text-on-error hover:brightness-95'
                  : 'bg-primary text-on-primary shadow-glow hover:shadow-glow-lg'
              }`}
            >
              <div className="absolute inset-x-0 top-0 h-px bg-white/40" />
              {confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
