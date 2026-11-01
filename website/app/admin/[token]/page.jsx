import { notFound } from 'next/navigation'
import { isAdmin } from '@/lib/auth'
import { getRelease, storageReady, uploadMode } from '@/lib/release'
import AdminPanel from './AdminPanel'

export const dynamic = 'force-dynamic'
export const metadata = { title: 'Publication', robots: { index: false, follow: false } }

/** Secret page: without the exact code in the address it is a plain « page introuvable ». */
export default async function AdminPage({ params }) {
  const { token } = await params
  if (!isAdmin(token)) notFound()
  return <AdminPanel token={token} initial={await getRelease()} storage={storageReady()} mode={uploadMode()} />
}
