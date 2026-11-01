import { NextResponse } from 'next/server'
import { isAdmin } from '@/lib/auth'
import { cleanRelease, getRelease, publishRelease, storageReady } from '@/lib/release'

export const dynamic = 'force-dynamic'

export async function GET() {
  return NextResponse.json({ release: await getRelease() })
}

/** Publishes a new version (admin only). */
export async function POST(request) {
  let body
  try { body = await request.json() } catch { return NextResponse.json({ error: 'Requête invalide' }, { status: 400 }) }
  if (!isAdmin(body?.token)) return NextResponse.json({ error: 'Accès refusé' }, { status: 401 })
  if (!storageReady()) return NextResponse.json({ error: 'Stockage Blob non connecté au projet Vercel' }, { status: 500 })
  const release = cleanRelease(body.release)
  if (!release.android && !release.windowsExe && !release.windowsMsi) return NextResponse.json({ error: 'Ajoute au moins un fichier' }, { status: 400 })
  try {
    return NextResponse.json({ release: await publishRelease(release) })
  } catch (e) {
    return NextResponse.json({ error: `Publication impossible : ${e.message}` }, { status: 500 })
  }
}
