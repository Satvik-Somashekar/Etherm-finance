import { Icon } from './Icon'
import { Logo } from './Logo'

const COLUMNS = [
  { title: 'Platform', links: ['Architecture', 'Data Sources', 'Roadmap'] },
  { title: 'Compliance', links: ['Consent Policy', 'DPDP Act', 'Terms'] },
  { title: 'Support', links: ['Status', 'Audit Log'] },
]

export function Footer() {
  return (
    <footer className="w-full border-t border-outline-variant/10 bg-surface-container-lowest py-16">
      <div className="mx-auto flex max-w-container-max flex-col justify-between gap-12 px-margin-mobile md:flex-row lg:px-margin-desktop">
        <div className="space-y-4">
          <div className="flex items-center gap-2 opacity-80">
            <Logo className="h-6 w-6" />
            <span className="text-label-md text-on-surface">Etherm Finance</span>
          </div>
          <p className="max-w-xs text-label-sm leading-relaxed text-on-surface-variant">
            Aggregating consented financial data under India&apos;s Account Aggregator framework,
            with answers grounded in your own records.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-12 sm:grid-cols-3">
          {COLUMNS.map((col) => (
            <div key={col.title} className="flex flex-col gap-3">
              <span className="mb-2 text-label-md text-on-surface">{col.title}</span>
              {col.links.map((link) => (
                <a
                  key={link}
                  href="#"
                  className="text-label-sm text-on-surface-variant transition-colors hover:text-primary"
                >
                  {link}
                </a>
              ))}
            </div>
          ))}
        </div>
      </div>
      <div className="mx-auto mt-16 flex max-w-container-max flex-col items-center justify-between gap-4 border-t border-outline-variant/10 px-margin-mobile pt-8 text-label-sm text-on-surface-variant opacity-40 sm:flex-row lg:px-margin-desktop">
        <p>Mock AA data · not a licensed Financial Information User</p>
        <div className="flex gap-6">
          <Icon name="verified_user" size={16} />
          <Icon name="shield" size={16} />
          <Icon name="lock" size={16} />
        </div>
      </div>
    </footer>
  )
}
