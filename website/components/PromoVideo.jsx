'use client'
import { useRef, useState } from 'react'
import Icon from './Icon'

/** The ad, muted and looping like on social networks; one tap turns the sound on. */
export default function PromoVideo() {
  const ref = useRef(null)
  const [sound, setSound] = useState(false)
  const toggle = () => {
    const v = ref.current
    if (!v) return
    v.muted = sound
    if (!sound) { v.currentTime = 0; v.play().catch(() => {}) }
    setSound(!sound)
  }
  return (
    <div className="phone">
      <video ref={ref} src="/media/pub.mp4" poster="/media/poster.jpg" autoPlay muted loop playsInline preload="metadata" />
      <button className="sound" onClick={toggle} aria-label={sound ? 'Couper le son' : 'Activer le son'}>
        <Icon name={sound ? 'Play' : 'Music'} size={16} /> {sound ? 'Son activé' : 'Activer le son'}
      </button>
    </div>
  )
}
