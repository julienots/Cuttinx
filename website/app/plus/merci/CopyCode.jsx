'use client'
import { useState } from 'react'

export default function CopyCode({ code }) {
  const [done, setDone] = useState(false)
  return (
    <div className="code-box">
      <code>{code}</code>
      <button className="btn btn-ghost btn-small" onClick={async () => { try { await navigator.clipboard.writeText(code); setDone(true) } catch { setDone(false) } }}>
        {done ? 'Copié !' : 'Copier le code'}
      </button>
    </div>
  )
}
