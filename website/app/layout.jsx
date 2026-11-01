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

export const metadata = {
  metadataBase: new URL(process.env.VERCEL_PROJECT_PRODUCTION_URL ? `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}` : 'http://localhost:3000'),
  title: 'CUTTINX PRO, le studio de montage sur ton téléphone et ton PC',
  description: 'Éditeur vidéo multipiste, Twitch Editor, ID Influenceur et Studio Créateur. Sans filigrane, sans abonnement. Android et Windows.',
  openGraph: { title: 'CUTTINX PRO', description: 'Monte comme un pro. Depuis ta poche.', images: ['/media/poster.jpg'] },
}

export const viewport = { themeColor: '#0B0C0F' }

export default function RootLayout({ children }) {
  return (
    <html lang="fr" className={montserrat.variable}>
      <body>{children}</body>
    </html>
  )
}
