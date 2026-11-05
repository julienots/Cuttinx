import { licenceForSubscription, origin, verify } from '@/lib/plus'

export const dynamic = 'force-dynamic'

/**
 * The apps send their licence (plain text) a few days before it ends; when the Stripe
 * subscription is still paid they get a new licence, valid until the end of the new period.
 */
export async function POST(req) {
  const old = verify(await req.text())
  if (!old?.sub) return new Response('Licence invalide', { status: 400 })
  if (!old.sub.startsWith('sub_')) return new Response('Code offert : pas de renouvellement', { status: 410 })
  try {
    const code = await licenceForSubscription(old.sub, origin(req), old.email || '')
    if (!code) return new Response('Abonnement terminé', { status: 402 })
    return new Response(code, { headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' } })
  } catch (e) {
    console.error('Plus refresh', e)
    return new Response('Réessaie plus tard', { status: 503 })
  }
}
