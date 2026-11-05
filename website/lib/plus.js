import crypto from 'node:crypto'

/**
 * CUTTINX Plus licences. A licence is "CXP1.<payload>.<signature>": base64url JSON, signed with
 * ECDSA P-256 / SHA-256 (DER), exactly what the apps verify (core/…/smart/Plus.kt). The private
 * key lives only in the Vercel setting PLUS_SIGNING_KEY (PEM, PKCS #8).
 */
const PREFIX = 'CXP1.'
const GRACE_MS = 3 * 24 * 3600 * 1000

function privateKey() {
  const pem = process.env.PLUS_SIGNING_KEY
  if (!pem) return null
  return crypto.createPrivateKey(pem.includes('\\n') ? pem.replace(/\\n/g, '\n') : pem)
}

export const plusReady = () => Boolean(process.env.PLUS_SIGNING_KEY)
export const stripeReady = () => Boolean(process.env.STRIPE_SECRET_KEY && process.env.STRIPE_PRICE_MONTHLY)

export const prices = () => ({
  monthly: process.env.PLUS_PRICE_MONTHLY_LABEL || '4,99 € / mois',
  yearly: process.env.STRIPE_PRICE_YEARLY ? (process.env.PLUS_PRICE_YEARLY_LABEL || '39,99 € / an') : null,
})

/** Signs a licence. [exp] in ms. */
export function sign({ sub, email = '', exp, iss }) {
  const key = privateKey()
  if (!key) throw new Error('PLUS_SIGNING_KEY manquant')
  const payload = Buffer.from(JSON.stringify({ v: 1, sub, email, plan: 'plus', exp: Math.round(exp), iss }))
  const sig = crypto.sign('sha256', payload, { key, dsaEncoding: 'der' })
  return PREFIX + payload.toString('base64url') + '.' + sig.toString('base64url')
}

/** Reads a licence signed by this site (null when the signature is wrong). */
export function verify(code) {
  const key = privateKey()
  if (!key || typeof code !== 'string') return null
  const c = code.trim().replace(/\s+/g, '')
  if (!c.startsWith(PREFIX) || c.length > 2000) return null
  const [p, s] = c.slice(PREFIX.length).split('.')
  if (!p || !s) return null
  const payload = Buffer.from(p, 'base64url')
  const ok = crypto.verify('sha256', payload, { key: crypto.createPublicKey(key), dsaEncoding: 'der' }, Buffer.from(s, 'base64url'))
  if (!ok) return null
  try { return JSON.parse(payload.toString('utf8')) } catch { return null }
}

/** Public address of the site, from the settings or the request. */
export function origin(req) {
  const env = process.env.NEXT_PUBLIC_SITE_URL || process.env.SITE_URL
  if (env) return env.replace(/\/$/, '')
  const h = req.headers
  const host = h.get('x-forwarded-host') || h.get('host')
  const proto = h.get('x-forwarded-proto') || 'https'
  return `${proto}://${host}`
}

/** Minimal Stripe REST client (no SDK). */
export async function stripe(path, { method = 'GET', params } = {}) {
  const body = params ? new URLSearchParams(params).toString() : undefined
  const res = await fetch(`https://api.stripe.com/v1/${path}`, {
    method,
    headers: { Authorization: `Bearer ${process.env.STRIPE_SECRET_KEY}`, ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}) },
    body,
    cache: 'no-store',
  })
  const data = await res.json()
  if (!res.ok) throw new Error(data?.error?.message || `Stripe ${res.status}`)
  return data
}

const PAID = new Set(['active', 'trialing', 'past_due'])

/** End of the paid period of a subscription (ms), whatever the Stripe API version. */
function periodEnd(sub) {
  const s = sub.current_period_end ?? sub.items?.data?.[0]?.current_period_end
  return s ? s * 1000 : 0
}

/** A fresh licence for a Stripe subscription that is still paid, else null. */
export async function licenceForSubscription(subId, iss, email = '') {
  const sub = await stripe(`subscriptions/${encodeURIComponent(subId)}`)
  if (!PAID.has(sub.status)) return null
  const end = periodEnd(sub)
  if (!end) return null
  return sign({ sub: sub.id, email, exp: end + GRACE_MS, iss })
}
