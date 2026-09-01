# Design

`DESIGN.md` is the Etherm Lumina design system as exported from Google Stitch, and
is the source of truth for the frontend's tokens. It is mirrored into code at
`frontend/tailwind.config.ts` — change both together.

`screens/` holds the original Stitch renders. They are kept for reference only:
the shipped screens use the same visual system but carry Account Aggregator
content (deposits, mutual funds, insurance, EPF, equities) rather than the
fictional crypto copy the export was generated with.

| Reference render | Implemented as |
| --- | --- |
| `screens/dashboard.png` | `frontend/src/pages/Dashboard.tsx` |
| `screens/ai_oracle.png` | `frontend/src/pages/AiOracle.tsx` |
| `screens/connected_accounts.png` | `frontend/src/pages/Accounts.tsx` |
| `screens/profile_security.png` | `frontend/src/pages/Consent.tsx` |

## Token notes

The `borderRadius` scale is redefined per DESIGN.md, so the names do not carry
their stock Tailwind values:

| Utility | Value | Used for |
| --- | --- | --- |
| `rounded-sm` | 0.25rem | chips, hairline elements |
| `rounded` | 0.5rem | buttons, inputs |
| `rounded-md` | 0.75rem | inner panels |
| `rounded-lg` | 1rem | container cards |
| `rounded-xl` | 1.5rem | feature modals |

Depth comes from translucency rather than shadows: the `.glass` component class
in `src/index.css` is the Level 1 surface (semi-transparent fill, 20px backdrop
blur, hairline white border), and `.glass-floating` is the modal/tooltip variant.
