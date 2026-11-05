import Icon from '@/components/Icon'
import Mark from '@/components/Mark'
import PromoVideo from '@/components/PromoVideo'
import { formatDate, formatSize, getPublicRelease } from '@/lib/release'

export const dynamic = 'force-dynamic'

const features = [
  { icon: 'Split', title: 'Montage multipiste', text: 'Vidéos, photos, textes, sons et calques d’effets sur autant de pistes que tu veux. Découpe, rogne, déplace, à l’image près.' },
  { icon: 'Idea', title: 'Réalisateur', text: '« Optimise pour Reels en 30 secondes, look cinéma. » Tu écris ce que tu veux, il prépare le plan et monte pour toi.' },
  { icon: 'Check', title: 'Contrôle qualité', text: 'Une note sur 100 avant de publier : accroche, rythme, textes cachés par les boutons, son. Et « Tout corriger » en un geste.' },
  { icon: 'Search', title: 'Actions rapides', text: 'Plus de 270 outils dans une seule recherche. Tape « boucle », « karaoké » ou « écran partagé », c’est fait.' },
  { icon: 'Studio', title: 'Modèles en un geste', text: 'Vlog, bande-annonce, top 5, tutoriel, sport, podcast, story : tout le montage s’applique d’un coup, et reste modifiable.' },
  { icon: 'Video', title: 'Écran partagé et fond flou', text: 'Deux à quatre vidéos à l’écran, avant / après, incrustation ronde façon réaction, fond flou pour le vertical.' },
  { icon: 'Voice', title: 'Son de studio', text: 'Voix de podcast en un geste, débruitage, 8 égaliseurs, 8 effets de voix, fondus enchaînés entre musiques.' },
  { icon: 'Text', title: 'Titres et caméra', text: '14 styles de texte, compte à rebours, classement, bandeau nom, carte de fin. Zooms, panoramiques et secousses en images clés.' },
  { icon: 'JlCut', title: 'Montage précis', text: 'Couper avant ou après la tête de lecture, coupes en J et en L, aimant, solo, marqueurs, plans de durée égale.' },
  { icon: 'Effects', title: 'Effets et transitions', text: 'Looks couleur, transitions, animations d’entrée, zoom lent, rampes de vitesse, textes animés et sous-titres.' },
  { icon: 'Replay', title: 'Historique des versions', text: 'Chaque étape est gardée. Compare, puis reviens à n’importe quelle version sans rien perdre.' },
  { icon: 'Download', title: 'Export sans filigrane', text: 'Export MP4 jusqu’en 4K, au format TikTok, Reels, Shorts ou YouTube. Ta vidéo, sans logo ajouté.' },
]

const spaces = [
  { icon: 'Editor', name: 'Éditeur', color: 'var(--yellow)', text: 'Le montage vidéo complet, du téléphone au PC.' },
  { icon: 'Twitch', name: 'Twitch Editor', color: 'var(--blue)', text: '40 thèmes, scènes, alertes, emotes et badges, export pour OBS.' },
  { icon: 'Identity', name: 'ID Influenceur', color: 'var(--pink)', text: 'Ton identité : couleurs, polices, bios, stories à la une, bannières.' },
  { icon: 'Studio', name: 'Studio Créateur', color: 'var(--orange)', text: '12 formats exacts : miniatures, couvertures, posts, stories, bannières.' },
]

const faq = [
  ['C’est vraiment sans filigrane et sans abonnement ?', 'Oui. Tes vidéos sont exportées sans logo ajouté et l’appli ne demande aucun abonnement.'],
  ['Sur quels appareils ?', 'Android 8.0 ou plus récent, et Windows 10 ou 11 (64 bits).'],
  ['Comment installer l’APK sur Android ?', 'Télécharge le fichier, ouvre-le, puis autorise « Installer des applis inconnues » pour ton navigateur ou ton gestionnaire de fichiers quand Android le demande.'],
  ['Windows affiche « Windows a protégé votre ordinateur » ?', 'C’est normal pour un nouveau logiciel : clique sur « Informations complémentaires » puis « Exécuter quand même ».'],
  ['Où vont mes projets ?', 'Ils restent sur ton appareil. Tu peux les passer du téléphone au PC avec un fichier .cuttinx.'],
]

function DownloadCard({ platform, title, sub, file, extra, steps }) {
  return (
    <div className="dl">
      <div className="dl-head">
        <span className="dl-icon"><Icon name={platform === 'android' ? 'Play' : 'Editor'} size={22} /></span>
        <div>
          <h3>{title}</h3>
          <p className="dim">{sub}</p>
        </div>
      </div>
      {file ? (
        <a className="btn btn-primary btn-wide" href={`/telecharger/${platform}`}>
          <Icon name="Download" size={20} /> Télécharger {file.size ? <span className="size">· {formatSize(file.size)}</span> : null}
        </a>
      ) : (
        <span className="btn btn-disabled btn-wide">Bientôt disponible</span>
      )}
      {extra}
      <ol className="steps">{steps.map((s) => <li key={s}>{s}</li>)}</ol>
    </div>
  )
}

export default async function Home() {
  const r = await getPublicRelease()
  const version = r?.version
  return (
    <>
      <header className="top">
        <a href="#" className="brand"><Mark size={30} /><span>CUTTINX</span><em>PRO</em></a>
        <nav>
          <a href="#fonctions">Fonctions</a>
          <a href="#espaces">Espaces</a>
          <a href="#faq">Questions</a>
          <a href="/plus">Plus</a>
          <a href="#telecharger" className="btn btn-primary btn-small">Télécharger</a>
        </nav>
      </header>

      <main>
        <section className="hero">
          <div className="hero-text">
            {version ? <span className="pill">Version {version}{r.publishedAt ? ` · ${formatDate(r.publishedAt)}` : ''}</span> : <span className="pill">Android · Windows</span>}
            <h1>Monte comme un pro.<br /><span className="y">Depuis ta poche.</span></h1>
            <p className="lead">Éditeur vidéo multipiste, Twitch Editor, ID Influenceur et Studio Créateur dans une seule appli. Sans filigrane, sans abonnement.</p>
            <div className="cta">
              <a className="btn btn-primary" href={r?.android ? '/telecharger/android' : '#telecharger'}><Icon name="Download" size={20} /> Android (APK)</a>
              <a className="btn btn-ghost" href={r?.windowsExe || r?.windowsMsi ? '/telecharger/windows' : '#telecharger'}><Icon name="Download" size={20} /> Windows</a>
            </div>
            <ul className="ticks">
              <li><Icon name="Check" size={18} /> Sans filigrane</li>
              <li><Icon name="Check" size={18} /> Sans abonnement</li>
              <li><Icon name="Check" size={18} /> Export jusqu’en 4K</li>
            </ul>
          </div>
          <PromoVideo />
        </section>

        <section id="fonctions" className="block">
          <p className="kicker">FONCTIONS</p>
          <h2>Tout ce qu’il faut pour publier mieux, plus vite.</h2>
          <div className="grid">
            {features.map((f) => (
              <article key={f.title} className="card">
                <span className="card-icon"><Icon name={f.icon} size={24} /></span>
                <h3>{f.title}</h3>
                <p className="dim">{f.text}</p>
              </article>
            ))}
          </div>
        </section>

        <section id="espaces" className="block">
          <p className="kicker">4 ESPACES</p>
          <h2>Une seule appli. Aucun compromis.</h2>
          <div className="spaces">
            {spaces.map((s, i) => (
              <article key={s.name} className="space" style={{ '--c': s.color }}>
                {i > 0 ? <span className="slash" aria-hidden="true">/</span> : null}
                <span className="space-icon"><Icon name={s.icon} size={28} /></span>
                <h3>{s.name}</h3>
                <p className="dim">{s.text}</p>
              </article>
            ))}
          </div>
        </section>

        <section id="telecharger" className="block">
          <p className="kicker">TÉLÉCHARGER</p>
          <h2>{version ? `La dernière version : ${version}` : 'Télécharge CUTTINX PRO'}</h2>
          {r?.notes ? <div className="notes"><h3>Nouveautés</h3><p>{r.notes}</p></div> : null}
          <div className="dls">
            <DownloadCard platform="android" title="Android" sub="Fichier APK · Android 8.0 ou plus" file={r?.android}
              steps={['Télécharge le fichier APK.', 'Ouvre-le depuis tes téléchargements.', 'Autorise l’installation si Android le demande, puis « Installer ».']} />
            <DownloadCard platform="windows" title="Windows" sub="Installeur · Windows 10 et 11, 64 bits" file={r?.windowsExe || r?.windowsMsi}
              extra={r?.windowsExe && r?.windowsMsi ? <a className="alt" href={r.windowsMsi.url}>Version MSI (entreprise) · {formatSize(r.windowsMsi.size)}</a> : null}
              steps={['Télécharge l’installeur.', 'Lance-le. Si Windows protège ton PC : « Informations complémentaires » puis « Exécuter quand même ».', 'Ouvre CUTTINX PRO depuis le menu Démarrer.']} />
          </div>
        </section>

        <section id="plus" className="block">
          <p className="kicker">CUTTINX PLUS</p>
          <h2>Envie d’aller encore plus loin ?</h2>
          <div className="notes">
            <p>L’appli reste gratuite. Avec <strong>CUTTINX Plus</strong> : zéro pub, tout le Labo débloqué pour toujours et 8 technologies de pointe exclusives (précision à l’image, étalonnage cinéma, mastering broadcast…).</p>
          </div>
          <a className="btn btn-primary" href="/plus">Découvrir CUTTINX Plus</a>
        </section>

        <section id="faq" className="block">
          <p className="kicker">QUESTIONS</p>
          <h2>Bon à savoir</h2>
          <div className="faq">
            {faq.map(([q, a]) => (
              <details key={q}>
                <summary>{q}</summary>
                <p className="dim">{a}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="foot">
        <div className="brand"><Mark size={24} /><span>CUTTINX</span><em>PRO</em></div>
        <p className="dim">SuperEssence Creative Studio · {new Date().getFullYear()}</p>
      </footer>
    </>
  )
}
