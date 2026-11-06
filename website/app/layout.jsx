import localFont from 'next/font/local'
import './globals.css'

const montserrat = localFont({
  src: [
    { path: './fonts/Montserrat_500Medium.ttf', weight: '500', style: 'normal' },
    { path: './fonts/Montserrat_800ExtraBold.ttf', weight: '800', style: 'normal' },
  ],
  display: 'swap',
  variable: '--font',
})

// Google AdSense (même compte Google qu'AdMob). Remplaçable par la variable Vercel NEXT_PUBLIC_ADSENSE_CLIENT ;
// mettre « off » pour retirer les pubs du site.
const adsense = process.env.NEXT_PUBLIC_ADSENSE_CLIENT === 'off' ? null : process.env.NEXT_PUBLIC_ADSENSE_CLIENT || 'ca-pub-2847478731079395'

export const metadata = {
  metadataBase: new URL(process.env.VERCEL_PROJECT_PRODUCTION_URL ? `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}` : 'http://localhost:3000'),
  title: 'CUTTINX PRO, le studio de montage sur ton téléphone et ton PC',
  description: 'Éditeur vidéo multipiste, Twitch Editor, ID Influenceur et Studio Créateur. Sans filigrane, sans abonnement. Android et Windows.',
  openGraph: { title: 'CUTTINX PRO', description: 'Monte comme un pro. Depuis ta poche.', images: ['/media/poster.jpg'] },
  ...(adsense ? { other: { 'google-adsense-account': adsense } } : {}),
}

export const viewport = { themeColor: '#0B0C0F' }

export default function RootLayout({ children }) {
  return (
    <html lang="fr" className={montserrat.variable}>
      {adsense && (
        <head>
          {/* annonces automatiques AdSense : Google choisit les emplacements une fois le site validé */}
          <script async src={`https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${adsense}`} crossOrigin="anonymous" />
        </head>
      )}
      <body>{children}</body>
    </html>
  )
}
