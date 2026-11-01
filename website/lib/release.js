import { list, put, del } from '@vercel/blob'

/** The published version is a small JSON file in the Blob store, next to the downloads. */
const KEY = 'release/current.json'
const BLOB_HOST = /\.blob\.vercel-storage\.com$/

/** Never let the page wait on the storage: after [ms] the site shows « Bientôt disponible » instead. */
const withTimeout = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))])

/** A store connected the new way (OIDC) only sets BLOB_STORE_ID; older connections set a read-write token. */
export const storageReady = () => Boolean(process.env.BLOB_READ_WRITE_TOKEN || process.env.BLOB_STORE_ID)

/** Client uploads: signed with the read-write token when there is one, otherwise through presigned URLs (OIDC). */
export const uploadMode = () => (process.env.BLOB_READ_WRITE_TOKEN ? 'token' : 'presigned')

export async function getRelease() {
  if (!storageReady()) return null
  try {
    const { blobs } = await withTimeout(list({ prefix: KEY, limit: 5 }), 5000)
    const b = blobs.find((x) => x.pathname === KEY)
    if (!b) return null
    const r = await fetch(`${b.url}?v=${Date.now()}`, { cache: 'no-store', signal: AbortSignal.timeout(4000) })
    return r.ok ? await r.json() : null
  } catch {
    return null
  }
}

function cleanFile(f) {
  if (!f || typeof f !== 'object') return null
  let url
  try { url = new URL(String(f.url)) } catch { return null }
  if (url.protocol !== 'https:' || !BLOB_HOST.test(url.hostname)) return null
  return { url: url.toString(), name: String(f.name || '').slice(0, 160), size: Math.max(0, Number(f.size) || 0) }
}

export function cleanRelease(input) {
  return {
    version: String(input?.version || '').trim().slice(0, 40) || '1.0',
    notes: String(input?.notes || '').trim().slice(0, 3000),
    android: cleanFile(input?.android),
    windowsExe: cleanFile(input?.windowsExe),
    windowsMsi: cleanFile(input?.windowsMsi),
    publishedAt: new Date().toISOString(),
  }
}

/** Saves the new version and deletes the files the previous one used and the new one no longer needs. */
export async function publishRelease(next) {
  const prev = await getRelease()
  await put(KEY, JSON.stringify(next, null, 2), {
    access: 'public', addRandomSuffix: false, allowOverwrite: true, contentType: 'application/json', cacheControlMaxAge: 60,
  })
  const keep = new Set([next.android?.url, next.windowsExe?.url, next.windowsMsi?.url].filter(Boolean))
  const old = [prev?.android?.url, prev?.windowsExe?.url, prev?.windowsMsi?.url].filter((u) => u && !keep.has(u))
  if (old.length) await del(old).catch(() => {})
  return next
}

export function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / (1024 * 1024)
  return mb >= 1024 ? `${(mb / 1024).toFixed(2).replace('.', ',')} Go` : `${mb.toFixed(mb < 10 ? 1 : 0).replace('.', ',')} Mo`
}

export function formatDate(iso) {
  try { return new Date(iso).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }) } catch { return '' }
}
