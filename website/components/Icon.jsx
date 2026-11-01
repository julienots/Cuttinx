import icons from '@/lib/icons.json'

/** The CUTTINX icon set (same drawings as in the apps), 24 × 24 grid, rounded 2 px strokes. */
export default function Icon({ name, size = 24, className, title }) {
  const d = icons[name]
  if (!d) return null
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" aria-hidden={title ? undefined : true} role={title ? 'img' : undefined}>
      {title ? <title>{title}</title> : null}
      {d.paths.map((p, i) => d.filled.includes(i)
        ? <path key={i} d={p} fill="currentColor" />
        : <path key={i} d={p} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />)}
    </svg>
  )
}
