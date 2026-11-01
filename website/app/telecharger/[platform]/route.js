import { NextResponse } from 'next/server'
import { getRelease } from '@/lib/release'

export const dynamic = 'force-dynamic'

/** Stable links to share: /telecharger/android and /telecharger/windows always point to the latest file. */
export async function GET(request, { params }) {
  const { platform } = await params
  const r = await getRelease()
  const url = platform === 'android' ? r?.android?.url : platform === 'windows' ? (r?.windowsExe?.url || r?.windowsMsi?.url) : null
  return NextResponse.redirect(url || new URL('/#telecharger', request.url), 302)
}
