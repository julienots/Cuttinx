import { headers } from 'next/headers'
import Mark from '@/components/Mark'
import Icon from '@/components/Icon'
import { licenceForSubscription, stripe, stripeReady, plusReady } from '@/lib/plus'
import CopyCode from './CopyCode'

export const dynamic = 'force-dynamic'
export const metadata = { title: 'Merci ! · CUTTINX Plus', robots: { index: false, follow: false } }

async function codeFor(sessionId) {
  if (!sessionId || !stripeReady() || !plusReady() || !/^cs_[A-Za-z0-9_]+$/.test(sessionId)) return null
  const s = await stripe(`checkout/sessions/${sessionId}`)
  if (s.status !== 'complete' || !s.subscription) return null
  const h = await headers()
  const site = (process.env.NEXT_PUBLIC_SITE_URL || process.env.SITE_URL || `${h.get('x-forwarded-proto') || 'https'}://${h.get('x-forwarded-host') || h.get('host')}`).replace(/\/$/, '')
  const subId = typeof s.subscription === 'string' ? s.subscription : s.subscription.id
  return licenceForSubscription(subId, site, s.customer_details?.email || '')
}

export default async function Merci({ searchParams }) {
  const { session_id } = await searchParams
  let code = null
  try { code = await codeFor(session_id) } catch (e) { console.error('Plus merci', e) }
  return (
    <>
      <header className="top"><a href="/" className="brand"><Mark size={30} /><span>CUTTINX</span><em>PRO</em></a></header>
      <main className="admin">
        {code ? (
          <>
            <div className="row"><Icon name="Check" size={26} /><h1>Bienvenue dans CUTTINX Plus !</h1></div>
            <p className="dim">Ton abonnement est actif. Voici ton code d’activation : garde cette page ou copie le code quelque part.</p>
            <section className="panel">
              <h2>Sur ton téléphone</h2>
              <a className="btn btn-primary" href={`cuttinx://plus?code=${encodeURIComponent(code)}`}>Ouvrir dans CUTTINX</a>
              <p className="dim">Le bouton ne marche pas ? Copie le code, puis dans l’appli : Labo → CUTTINX Plus → colle-le → Activer.</p>
            </section>
            <section className="panel">
              <h2>Ton code (Android et PC)</h2>
              <CopyCode code={code} />
              <p className="dim">Sur PC : onglet Labo → CUTTINX Plus → colle le code → Activer. Le même code marche sur tous tes appareils. Il se renouvelle tout seul tant que l’abonnement est payé.</p>
            </section>
          </>
        ) : (
          <>
            <h1>Paiement en cours de vérification</h1>
            <p className="dim">Nous n’avons pas encore la confirmation du paiement. Recharge cette page dans quelques secondes. Si rien ne change, contacte-nous avec l’e-mail reçu de Stripe.</p>
            <a className="btn btn-ghost" href="/plus">Retour à CUTTINX Plus</a>
          </>
        )}
      </main>
    </>
  )
}
