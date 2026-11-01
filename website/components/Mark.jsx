/** The CUTTINX mark: one white blade, two yellow ones and the dot. */
export default function Mark({ size = 32 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 108 108" aria-hidden="true">
      <polygon points="33,30 45,30 75,78 63,78" fill="var(--ink)" />
      <polygon points="63,30 75,30 58.5,56.4 52.5,46.8" fill="var(--yellow)" />
      <polygon points="49.5,61.2 45,78 33,78 46.5,56.4" fill="var(--yellow)" />
      <circle cx="78" cy="74" r="4" fill="var(--yellow)" />
    </svg>
  )
}
