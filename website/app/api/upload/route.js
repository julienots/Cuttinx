import { handleUpload, handleUploadPresigned } from '@vercel/blob/client'
import { issueSignedToken } from '@vercel/blob'
import { NextResponse } from 'next/server'
import { isAdmin } from '@/lib/auth'

const MAX = 2 * 1024 * 1024 * 1024
const MONTH = 60 * 60 * 24 * 30

/** Only the owner (secret code) may upload, and only an APK, EXE or MSI under downloads/. */
function check(pathname, clientPayload) {
  if (!isAdmin(clientPayload)) throw new Error('Accès refusé')
  if (!/^downloads\/(android|windows)\/[^/]+\.(apk|exe|msi)$/i.test(pathname)) throw new Error('Type de fichier refusé (APK, EXE ou MSI)')
}

/**
 * Gives the admin page a short-lived permission to send a file straight to the Blob store.
 * Stores connected with OIDC (BLOB_STORE_ID, no read-write token) use presigned URLs instead.
 */
export async function POST(request) {
  const body = await request.json()
  try {
    if (body?.type === 'blob.generate-presigned-url') {
      const result = await handleUploadPresigned({
        body,
        request,
        getSignedToken: async (pathname, clientPayload) => {
          check(pathname, clientPayload)
          const token = await issueSignedToken({ pathname, operations: ['put'], maximumSizeInBytes: MAX, validUntil: Date.now() + 60 * 60 * 1000 })
          return { token, urlOptions: { maximumSizeInBytes: MAX, cacheControlMaxAge: MONTH } }
        },
      })
      return NextResponse.json(result)
    }
    const result = await handleUpload({
      body,
      request,
      onBeforeGenerateToken: async (pathname, clientPayload) => {
        check(pathname, clientPayload)
        return { addRandomSuffix: true, maximumSizeInBytes: MAX, cacheControlMaxAge: MONTH }
      },
      onUploadCompleted: async () => {},
    })
    return NextResponse.json(result)
  } catch (e) {
    return NextResponse.json({ error: e.message }, { status: 400 })
  }
}
