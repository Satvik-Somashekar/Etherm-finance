---
name: Etherm Lumina
colors:
  surface: '#101415'
  surface-dim: '#101415'
  surface-bright: '#363a3b'
  surface-container-lowest: '#0b0f10'
  surface-container-low: '#191c1e'
  surface-container: '#1d2022'
  surface-container-high: '#272a2c'
  surface-container-highest: '#323537'
  on-surface: '#e0e3e5'
  on-surface-variant: '#cbc3d7'
  inverse-surface: '#e0e3e5'
  inverse-on-surface: '#2d3133'
  outline: '#958ea0'
  outline-variant: '#494454'
  surface-tint: '#d0bcff'
  primary: '#d0bcff'
  on-primary: '#3c0091'
  primary-container: '#a078ff'
  on-primary-container: '#340080'
  inverse-primary: '#6d3bd7'
  secondary: '#c4c1fb'
  on-secondary: '#2d2a5b'
  secondary-container: '#444173'
  on-secondary-container: '#b3afe9'
  tertiary: '#b9c7df'
  on-tertiary: '#233144'
  tertiary-container: '#8392a8'
  on-tertiary-container: '#1c2a3d'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#d0bcff'
  on-primary-fixed: '#23005c'
  on-primary-fixed-variant: '#5516be'
  secondary-fixed: '#e3dfff'
  secondary-fixed-dim: '#c4c1fb'
  on-secondary-fixed: '#181445'
  on-secondary-fixed-variant: '#444173'
  tertiary-fixed: '#d5e3fc'
  tertiary-fixed-dim: '#b9c7df'
  on-tertiary-fixed: '#0d1c2e'
  on-tertiary-fixed-variant: '#3a485b'
  background: '#101415'
  on-background: '#e0e3e5'
  surface-variant: '#323537'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 48px
    fontWeight: '600'
    lineHeight: '1.1'
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.2'
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '500'
    lineHeight: '1.2'
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '500'
    lineHeight: '1.3'
    letterSpacing: '0'
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: '1.6'
    letterSpacing: '0'
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
    letterSpacing: '0'
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: '1'
    letterSpacing: 0.05em
  label-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: '1'
    letterSpacing: '0'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 8px
  container-max: 1280px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 48px
---

## Brand & Style
The brand personality is ethereal, sophisticated, and fluid, moving away from traditional rigid corporate structures toward a high-end, future-forward fintech experience. It targets discerning users who value both technological innovation and aesthetic serenity. 

The design system employs a **Glassmorphism** style to evoke a sense of depth and transparency, reflecting the "Etherm" narrative. Surfaces are semi-translucent, suggesting an open and honest financial ecosystem. Interactions should feel weightless and fluid, using background blurs and subtle gradients to guide the user's eye without visual clutter.

## Colors
The palette shifts from legacy corporate blue to a deep, nocturnal spectrum. 

- **Primary (Vibrant Violet):** Used for calls to action, active states, and highlighting key financial growth metrics. It provides the "ethereal" glow against dark backgrounds.
- **Secondary (Deep Indigo):** The foundation of the UI. This acts as the primary canvas color, providing a sense of stability and depth.
- **Tertiary (Slate):** Used for secondary information, borders, and subtle UI elements to maintain a grounded, professional feel.
- **Neutral (Cloud White):** High-contrast text and iconography to ensure legibility and an authoritative voice.

The default state is **Dark Mode**, which better supports the glassmorphic effects and vibrant accents.

## Typography
This design system utilizes **Inter** with a focus on refined spacing to achieve a high-end fintech look. Headlines use tighter letter-spacing and substantial line heights to appear more editorial and authoritative. 

For large displays, headline sizes are expansive but shift to more legible, compact scales on mobile devices. Labels are frequently used in uppercase with increased tracking to create a "technical" but sophisticated feel for data-heavy views.

## Layout & Spacing
The layout follows a **Fluid Grid** model based on an 8px base unit. 

- **Desktop:** A 12-column grid with generous 48px outer margins to allow the "ethereal" background elements to breathe. 
- **Tablet:** 8-column grid with 32px margins.
- **Mobile:** 4-column grid with 16px margins.

Spacing between functional groups should be expansive (e.g., 64px or 80px) to maintain the minimalist, high-end aesthetic. Use dynamic padding within glassmorphic cards to ensure content never feels cramped against the translucent edges.

## Elevation & Depth
Depth is achieved through **Glassmorphism** and layered translucency rather than traditional shadows.

- **Surface Levels:** The base is the Deep Indigo. Level 1 surfaces (cards) use a semi-transparent Indigo (approx. 40% opacity) with a `20px` background blur.
- **Floating Elements:** Modals and tooltips use a lighter Slate tint (20% opacity) with a `40px` blur and a `1px` stroke (White at 10% opacity) to define the edge.
- **Glows:** High-priority elements use a subtle, diffused outer glow in the Primary Violet color (opacity 15%) to simulate an emissive light source within the "ether."

## Shapes
In line with the friendly yet modern feel, the design system adopts a **Rounded** (Level 2) shape language. 

- **Standard Buttons & Inputs:** 0.5rem (8px) corner radius.
- **Container Cards:** 1rem (16px) corner radius for a softer, more approachable enclosure.
- **Feature Modals:** 1.5rem (24px) for a distinct, high-end feel.

The roundedness helps soften the technical nature of fintech, making the interface feel organic and fluid.

## Components
- **Buttons:** Primary buttons feature a solid Vibrant Violet fill with a subtle "inner glow" top-border. Secondary buttons use a glassmorphic style with a 1px white-transparent border.
- **Inputs:** Fields are semi-transparent with a 1px Slate border that illuminates to Violet on focus. Labels sit just above the field in `label-sm`.
- **Cards:** The hallmark of the system. Cards must have a background blur and a very thin, low-opacity white border to catch "light" at the edges.
- **Chips:** Small, pill-shaped elements used for transaction categories or status (e.g., "Pending"). They use low-opacity fills of the status color (Green for success, Violet for active).
- **Charts & Data:** Use fluid, curved lines for sparklines rather than jagged points. Data points should have a "glow" effect when hovered to maintain the ethereal theme.
- **Lists:** Clean rows separated by subtle 1px Slate dividers at 20% opacity, ensuring the background gradient remains visible through the list items.