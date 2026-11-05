import Icon from '@/components/Icon'
import Mark from '@/components/Mark'
import { perks, techs } from '@/lib/plusPerks'
import { prices, stripeReady } from '@/lib/plus'

export const dynamic = 'force-dynamic'
export const metadata = { title: 'CUTTINX Plus', description: 'Zéro pub, tout le Labo pour toujours et 8 technologies de pointe exclusives.' }

export default async function PlusPage({ searchParams }) {
  const q = await searchParams
  const ready = stripeReady()
  const p = prices()
  return (
    <>
      <header className="top">
        <a href="/" className="brand"><Mark size={30} /><span>CUTTINX</span><em>PRO</em></a>
        <nav><a href="/">Accueil</a><a href="#offre" className="btn btn-primary btn-small">S’abonner</a></nav>
      </header>
      <main>
        <section className="plus-hero">
          <span className="pill">CUTTINX Plus</span>
          <h1>Le montage <span className="y">sans limite.</span></h1>
          <p className="lead">Zéro pub, tout le Labo débloqué pour toujours et 8 technologies de pointe réservées aux abonnés. Sur Android et sur PC.</p>
        </section>

        {q?.indisponible ? <div className="msg err">L’abonnement ouvre très bientôt. Reviens dans quelques jours !</div> : null}
        {q?.erreur ? <div className="msg err">Le paiement n’a pas pu démarrer. Réessaie dans un instant.</div> : null}

        <section className="block">
          <div className="grid">
            {perks.map(([icon, title, text]) => (
              <article key={title} className="card"><span className="card-icon"><Icon name={icon} size={24} /></span><h3>{title}</h3><p className="dim">{text}</p></article>
            ))}
          </div>
        </section>

        <section className="block">
          <p className="kicker">TECHNOLOGIES PLUS</p>
          <h2>Des outils de pointe, d’une précision à l’image près.</h2>
          <div className="grid">
            {techs.map(([icon, title, text]) => (
              <article key={title} className="card plus-card"><span className="card-icon"><Icon name={icon} size={24} /></span><h3>{title}</h3><p className="dim">{text}</p></article>
            ))}
          </div>
        </section>

        <section id="offre" className="block">
          <p className="kicker">S’ABONNER</p>
          <h2>Choisis ta formule.</h2>
          <div className="offers">
            <article className="offer">
              <h3>Mensuel</h3>
              <p className="price">{p.monthly}</p>
              <p className="dim">Sans engagement, résiliable à tout moment.</p>
              {ready ? <a className="btn btn-primary btn-wide" href="/api/plus/checkout?plan=monthly">S’abonner</a> : <span className="btn btn-disabled btn-wide">Bientôt disponible</span>}
            </article>
            {p.yearly ? (
              <article className="offer best">
                <span className="pill">Le plus avantageux</span>
                <h3>Annuel</h3>
                <p className="price">{p.yearly}</p>
                <p className="dim">Un seul paiement par an.</p>
                {ready ? <a className="btn btn-primary btn-wide" href="/api/plus/checkout?plan=yearly">S’abonner</a> : <span className="btn btn-disabled btn-wide">Bientôt disponible</span>}
              </article>
            ) : null}
          </div>
          <ol className="steps plus-steps">
            <li>Paie en toute sécurité (carte bancaire, Apple Pay, Google Pay) sur la page Stripe.</li>
            <li>Tu reçois aussitôt ton code d’activation.</li>
            <li>Sur ton téléphone, touche « Ouvrir dans CUTTINX ». Sur PC : Labo → CUTTINX Plus → colle le code.</li>
          </ol>
        </section>
      </main>
      <footer className="foot">
        <div className="brand"><Mark size={24} /><span>CUTTINX</span><em>PRO</em></div>
        <p className="dim">Paiement géré par Stripe. CUTTINX ne voit jamais ta carte.</p>
      </footer>
    </>
  )
}
