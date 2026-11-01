import { handleUpload } from '@vercel/blob/client'
import { NextResponse } from 'next/server'
import { isAdmin } from '@/lib/auth'

/** Gives the admin page a short-lived permission to send a file straight to the Blob store. */
export async function POST(request) {
  const body = await request.json()
  try {
    const result = await handleUpload({
      body,
      request,
      onBeforeGenerateToken: async (pathname, clientPayload) => {
        if (!isAdmin(clientPayload)) throw new Error('Accès refusé')
        if (!/^downloads\/(android|windows)\/[^/]+\.(apk|exe|msi)$/i.test(pathname)) throw new Error('Type de fichier refusé (APK, EXE ou MSI)')
        return { addRandomSuffix: true, maximumSizeInBytes: 2 * 1024 * 1024 * 1024, cacheControlMaxAge: 60 * 60 * 24 * 30 }
      },
      onUploadCompleted: async () => {},
    })
    return NextResponse.json(result)
  } catch (e) {
    return NextResponse.json({ error: e.message }, { status: 400 })
  }
}
