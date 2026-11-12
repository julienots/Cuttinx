package com.superessence.cuttinx.core.smart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** What a CUTTINX Plus licence says. Signed by the CUTTINX site; the apps only verify. */
@Serializable
data class PlusLicense(
    val v: Int = 1,
    /** Stripe subscription id ("sub_…"), or "gift_…" for a code given from the admin page. */
    val sub: String = "",
    val email: String = "",
    val plan: String = "plus",
    /** End of the paid period + grace, in ms since 1970. */
    val exp: Long = 0,
    /** Site that issued the licence: where it is renewed. */
    val iss: String = "",
)

/**
 * CUTTINX Plus, the subscription. A licence is a short signed code
 * ("CXP1.<payload>.<signature>", ECDSA P-256 / SHA-256): the apps check it offline with the
 * public key below, and renew it on the issuing site before it ends (each renewal checks that
 * the subscription is still paid). Nothing about the user is sent anywhere else.
 *
 * Plus gives: every Labo technology for good with no advert, the [Tech.plus] technologies and
 * the other privileges listed in [PERKS].
 */
object Plus {
    const val PREFIX = "CXP1."
    /** Public key of the CUTTINX site (X.509, base64). Its private half only lives in the site's settings. */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpzt/K82Y2j+DYCdOaziP+ySFk+zzsOngWdvuiasuqZEI1R02OkcGO9EG1SLUHBy1y+dzrrd4tBDC747Qcgq7pA=="
    private const val KEY = "plus_license"
    private const val RENEW_BEFORE_MS = 7L * 24 * 3_600_000L

    val PERKS = listOf(
        "Zéro pub, partout et pour toujours",
        "Les ${Tech.entries.count { !it.plus }} technologies du Labo débloquées en permanence",
        "${Tech.entries.count { it.pipeline }} outils qui analysent tes vidéos en un geste : réalisateur autonome, stabilisation, raccords mouvement, montage sur la musique, coupe des blancs, best-of, égalisation des plans, vertical intelligent, son studio",
        "${Tech.entries.count { it.plus && !it.pipeline }} technologies de pointe (précision à l'image, étalonnage cinéma, mastering broadcast…)",
        "Toutes les nouvelles technologies Plus dès leur sortie",
        "Badge Plus et aide prioritaire",
    )

    /** Address of the CUTTINX site (its /plus page sells the subscription). Empty = no "subscribe" button. */
    const val DEFAULT_SITE = ""

    /** Site where people subscribe (the apps may override it from their build). */
    @Volatile var siteUrl: String = DEFAULT_SITE

    /** Bumped when the licence changes, so screens can recompose. */
    @Volatile var version: Int = 0
        private set
    var onChange: () -> Unit = {}

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val publicKey: PublicKey by lazy { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))) }
    /** Tests only: verify with another key. */
    @Volatile internal var keyOverride: PublicKey? = null

    /** Checks a code's signature and reads it (expired licences are still read: see [isValid]). */
    fun parse(code: String, key: PublicKey = keyOverride ?: publicKey): PlusLicense? = runCatching {
        val c = code.trim().replace(Regex("\\s+"), "")
        if (!c.startsWith(PREFIX)) return null
        val parts = c.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return null
        val dec = Base64.getUrlDecoder()
        val payload = dec.decode(parts[0])
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update(payload)
        if (!sig.verify(dec.decode(parts[1]))) return null
        json.decodeFromString(PlusLicense.serializer(), payload.decodeToString())
    }.getOrNull()

    fun isValid(l: PlusLicense?, now: Long = TechVault.clock()) = l != null && l.exp > now

    val code: String? get() = TechVault.store.getText(KEY)?.takeIf { it.isNotBlank() }
    val license: PlusLicense? get() = code?.let { parse(it) }
    val active: Boolean get() = isValid(license)

    /** Saves a code typed or opened by the user. Returns an error message, or null when Plus is on. */
    fun activate(code: String): String? {
        val l = parse(code) ?: return "Code invalide. Copie-le en entier depuis la page de confirmation."
        if (!isValid(l)) return "Ce code a expiré. Renouvelle ton abonnement sur le site CUTTINX."
        TechVault.store.putText(KEY, code.trim().replace(Regex("\\s+"), ""))
        changed()
        return null
    }

    fun deactivate() { TechVault.store.putText(KEY, ""); changed() }

    private fun changed() { version++; runCatching { onChange() } }

    /** "jusqu'au 12/03/2027" for the screens. */
    fun until(l: PlusLicense): String = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(l.exp))

    /**
     * Renews the licence on its site when it ends within a week (or has ended): the site checks
     * that the subscription is still paid and sends a new code. Blocking: call it off the UI thread.
     */
    fun renewIfNeeded(now: Long = TechVault.clock()) {
        val c = code ?: return
        val l = parse(c) ?: return
        if (l.exp - now > RENEW_BEFORE_MS || l.sub.startsWith("gift_") || !l.iss.startsWith("https://")) return
        runCatching {
            val con = URI(l.iss.trimEnd('/') + "/api/plus/refresh").toURL().openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = 15_000
            con.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            con.outputStream.use { it.write(c.toByteArray()) }
            if (con.responseCode == 200) {
                val fresh = con.inputStream.use { it.readBytes().decodeToString() }.trim()
                val nl = parse(fresh)
                if (nl != null && nl.exp > l.exp) { TechVault.store.putText(KEY, fresh); changed() }
            }
            con.disconnect()
        }
    }

    /** Starts [renewIfNeeded] on a background thread. */
    fun renewInBackground() { Thread({ renewIfNeeded() }, "cuttinx-plus").apply { isDaemon = true }.start() }
}
