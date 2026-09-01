import { usePrivacy } from '../state/PrivacyContext'
import { formatCompact, formatCurrency, splitCurrency } from '../lib/format'

type Variant = 'compact' | 'full' | 'split'

interface MoneyProps {
  value: number
  variant?: Variant
  className?: string
  /** Dimmer styling for the paise portion, `split` variant only. */
  fractionClassName?: string
}

/** Every rupee figure goes through here so Privacy Mode can mask the lot. */
export function Money({
  value,
  variant = 'compact',
  className = '',
  fractionClassName = '',
}: MoneyProps) {
  const { masked } = usePrivacy()

  if (masked) {
    return (
      <span className={`tabular-nums tracking-widest ${className}`} title="Hidden by Privacy Mode">
        ₹ ••••••
      </span>
    )
  }

  if (variant === 'split') {
    const { whole, fraction } = splitCurrency(value)
    return (
      <span className={`tabular-nums ${className}`}>
        {whole}
        <span className={fractionClassName}>.{fraction}</span>
      </span>
    )
  }

  return (
    <span className={`tabular-nums ${className}`}>
      {variant === 'full' ? formatCurrency(value, true) : formatCompact(value)}
    </span>
  )
}
