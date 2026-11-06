import crypto from 'node:crypto'

/**
 * The admin page lives at /admin/<code>. Only the SHA-256 fingerprint of the code is stored here,
 * so the code itself never appears in the source. To change the code, set ADMIN_TOKEN in the
 * Vercel project settings (it then replaces this fingerprint).
 */
const ADMIN_TOKEN_SHA256 = 'd96a3e5c46d2288b57e3a4026cfc335db5099f535f3a47eb1ab19e5eda575527'

const sha = (s) => crypto.createHash('sha256').update(String(s)).digest()

export function isAdmin(token) {
  if (!token || typeof token !== 'string' || token.length > 200) return false
  const expected = process.env.ADMIN_TOKEN ? sha(process.env.ADMIN_TOKEN) : Buffer.from(ADMIN_TOKEN_SHA256, 'hex')
  return crypto.timingSafeEqual(sha(token), expected)
}
