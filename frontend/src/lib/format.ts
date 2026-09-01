const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})

const inrPrecise = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

export function formatCurrency(value: number, precise = false): string {
  return (precise ? inrPrecise : inr).format(value)
}

/** Indian short scale — the readable form for headline figures. */
export function formatCompact(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1_00_00_000) return `₹${(value / 1_00_00_000).toFixed(2)} Cr`
  if (abs >= 1_00_000) return `₹${(value / 1_00_000).toFixed(2)} L`
  if (abs >= 1_000) return `₹${(value / 1_000).toFixed(1)}K`
  return `₹${value}`
}

/** Splits a rupee figure so the paise can be rendered in a dimmer style. */
export function splitCurrency(value: number): { whole: string; fraction: string } {
  const [whole, fraction = '00'] = inrPrecise.format(value).split('.')
  return { whole, fraction }
}

export function formatSignedCompact(value: number, direction: 'CREDIT' | 'DEBIT'): string {
  return `${direction === 'CREDIT' ? '+' : '−'}${formatCompact(Math.abs(value))}`
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** "2m ago" / "5h ago" — used for AA sync freshness. */
export function timeAgo(iso: string | null): string {
  if (!iso) return 'Never'
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 1) return 'Just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.round(hrs / 24)}d ago`
}

export function daysUntil(iso: string): number {
  return Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000)
}
