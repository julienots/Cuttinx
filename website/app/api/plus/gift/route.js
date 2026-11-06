import crypto from 'node:crypto'
import { isAdmin } from '@/lib/auth'
import { origin, plusReady, sign } from '@/lib/plus'

export const dynamic = 'force-dynamic'

/** Admin page only: a CUTTINX Plus code offered for N days (tests, partners, giveaways). */
export async function POST(req) {
  let body
  try { body = await req.json() } catch { return Response.json({ error: 'Requête invalide' }, { status: 400 }) }
  if (!isAdmin(body?.token)) return Response.json({ error: 'Accès refusé' }, { status: 403 })
  if (!plusReady()) return Response.json({ error: 'Ajoute d’abord PLUS_SIGNING_KEY dans les réglages Vercel, puis redéploie.' }, { status: 503 })
  const days = Math.min(3650, Math.max(1, Math.round(Number(body.days) || 30)))
  const email = String(body.email || '').slice(0, 120)
  const exp = Date.now() + days * 24 * 3600 * 1000
  const code = sign({ sub: 'gift_' + crypto.randomBytes(6).toString('hex'), email, exp, iss: origin(req) })
  return Response.json({ code, exp })
}
