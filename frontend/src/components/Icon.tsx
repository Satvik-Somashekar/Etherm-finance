interface IconProps {
  name: string
  className?: string
  /** Material Symbols optical fill, 0–1. */
  fill?: 0 | 1
  size?: number
}

export function Icon({ name, className = '', fill = 0, size }: IconProps) {
  return (
    <span
      aria-hidden="true"
      className={`material-symbols-outlined ${className}`}
      style={{
        fontVariationSettings: `'FILL' ${fill}`,
        ...(size ? { fontSize: `${size}px` } : {}),
      }}
    >
      {name}
    </span>
  )
}
