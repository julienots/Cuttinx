package com.superessence.cuttinx.claude

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** État de la connexion à Claude, affiché dans l'écran « Claude ». */
data class ClaudeState(
    val enabled: Boolean = false,
    /** off | connecting | online | error */
    val relay: String = "off",
    val message: String = "",
    val lastAction: String = "",
    /** Incrémenté quand Claude crée/modifie un projet ou un média (pour rafraîchir l'interface). */
    val revision: Int = 0,
)

/**
 * Lien entre CUTTINX et Claude : un relais MCP (Supabase Edge Function) transmet les appels d'outils
 * de Claude au téléphone. Les vidéos ne quittent jamais le téléphone : seules des images d'aperçu
 * (planches, images) et des résultats d'analyse remontent à Claude.
 */
object ClaudeLink {
    const val RELAY_URL = "https://pwnjdmemczrhuhsyuboc.supabase.co/functions/v1/cuttinx"

    private val _state = MutableStateFlow(ClaudeState())
    val state: StateFlow<ClaudeState> = _state.asStateFlow()

    fun prefs(ctx: Context) = ctx.getSharedPreferences("claude", Context.MODE_PRIVATE)

    fun token(ctx: Context): String = prefs(ctx).getString("token", null) ?: newToken(ctx)

    fun newToken(ctx: Context): String {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs(ctx).edit().putString("token", t).apply()
        return t
    }

    fun mcpUrl(ctx: Context) = "$RELAY_URL/mcp/${token(ctx)}"

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun update(f: (ClaudeState) -> ClaudeState) { _state.value = f(_state.value) }

    fun bump(action: String = "") = update { it.copy(revision = it.revision + 1, lastAction = action.ifEmpty { it.lastAction }) }
}
