/** Wordmark glyph — an aperture ring, drawn inline so it needs no remote asset. */
export function Logo({ className = 'h-8 w-8' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id="logoGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#e9ddff" />
          <stop offset="100%" stopColor="#a078ff" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="30" height="30" rx="9" fill="#1d2022" stroke="#494454" />
      <circle cx="16" cy="16" r="8.5" stroke="url(#logoGrad)" strokeWidth="1.5" opacity="0.5" />
      <path
        d="M16 7.5a8.5 8.5 0 0 1 8.5 8.5"
        stroke="url(#logoGrad)"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
      <circle cx="16" cy="16" r="2.75" fill="url(#logoGrad)" />
    </svg>
  )
}
