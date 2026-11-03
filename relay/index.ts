// Relais MCP CUTTINX (Supabase Edge Function « cuttinx »).
// Claude parle MCP à ce relais ; le relais transmet chaque appel d'outil au téléphone (long-polling)
// et renvoie sa réponse. Les vidéos ne transitent jamais ici : elles restent sur le téléphone.
//
//   POST /cuttinx/mcp/<jeton>                 ← Claude (MCP Streamable HTTP, réponses JSON)
//   GET  /cuttinx/device/<jeton>/next         ← téléphone : attend la prochaine commande
//   POST /cuttinx/device/<jeton>/result/<id>  ← téléphone : renvoie le résultat
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const CALL_WAIT_MS = 110_000; // limite d'une Edge Function gratuite : 150 s
const POLL_WAIT_MS = 25_000;
const DEVICE_TIMEOUT_MS = 75_000;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function hashToken(t: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("vs:" + t));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });

// ---------------------------------------------------------------- outils
const INSTRUCTIONS = `CUTTINX : éditeur vidéo Android de l'utilisateur. Ses vidéos, photos et musiques restent sur son téléphone ; tu vois uniquement les médias qu'il a partagés dans CUTTINX → Claude. Tu crées de VRAIS projets CUTTINX (pistes, transitions, effets GPU, zooms animés, musique, textes) qu'il peut ensuite retoucher à la main dans l'éditeur, puis tu peux les exporter.
Méthode pour un edit :
1. list_media (lis les notes) → view_storyboard sur chaque vidéo pour REGARDER le contenu (puis view_storyboard avec start/end resserrés pour trouver les bornes exactes des moments forts). find_highlights / detect_scenes aident à repérer.
2. Si une musique est fournie : analyze_music → BPM, beats, downbeats, moments forts. Cale les fins de clips sur des beats (durée timeline = (end-start)/speed ; une transition fait chevaucher deux clips de sa durée).
3. list_effects une fois pour connaître les effets/transitions disponibles.
4. create_project avec la recette → render_project quality "draft" → get_render → view_storyboard du rendu pour vérifier → corrige avec create_project + project_id (remplace le projet) → render_project "final".
Le rendu final va dans la galerie (Movies/Cuttinx) et le projet reste modifiable dans CUTTINX.
Si le téléphone n'est pas connecté, demande à l'utilisateur d'ouvrir CUTTINX → Claude et d'activer « Connecté à Claude ».`;

const str = (description?: string) => ({ type: "string", ...(description ? { description } : {}) });
const num = (description?: string, extra: Record<string, unknown> = {}) => ({ type: "number", ...(description ? { description } : {}), ...extra });
const bool = (description?: string) => ({ type: "boolean", ...(description ? { description } : {}) });
const en = (values: string[], description?: string) => ({ type: "string", enum: values, ...(description ? { description } : {}) });
const obj = (properties: Record<string, unknown>, required: string[] = []) => ({ type: "object", properties, required, additionalProperties: false });
const arr = (items: unknown, description?: string) => ({ type: "array", items, ...(description ? { description } : {}) });
const fxList = arr(obj({ id: str("id d'effet vidéo (list_effects)"), params: { type: "object", additionalProperties: { type: "number" }, description: "paramètres de l'effet" } }, ["id"]));

const TOOLS = [
  { name: "device_status", description: "Téléphone connecté ? Espace libre, batterie, nombre de médias partagés et de projets.", inputSchema: obj({}) },
  {
    name: "list_media",
    description: "Liste les médias que l'utilisateur a partagés avec toi dans CUTTINX (vidéos, photos, musiques) et les rendus que tu as exportés.",
    inputSchema: obj({ type: en(["video", "image", "audio"]), kind: en(["upload", "render"]), search: str() }),
  },
  { name: "update_media", description: "Renomme un média partagé ou modifie ses notes.", inputSchema: obj({ media_id: str(), name: str(), notes: str() }, ["media_id"]) },
  {
    name: "view_frame",
    description: "Image d'une vidéo à un instant précis (ou une photo).",
    inputSchema: obj({ media_id: str(), time: num("secondes", { minimum: 0 }), width: num("px, défaut 768", { minimum: 160, maximum: 1280 }) }, ["media_id"]),
  },
  {
    name: "view_storyboard",
    description: "Planche contact horodatée d'une vidéo ou d'un intervalle : le meilleur moyen de la regarder. Resserre start/end pour trouver les bornes exactes d'une figure.",
    inputSchema: obj({ media_id: str(), start: num(), end: num(), columns: num("défaut 4", { minimum: 1, maximum: 6 }), rows: num("défaut 3", { minimum: 1, maximum: 6 }) }, ["media_id"]),
  },
  { name: "detect_scenes", description: "Changements de plan (secondes).", inputSchema: obj({ media_id: str(), interval: num("pas d'échantillonnage s, défaut 0.25") }, ["media_id"]) },
  {
    name: "find_highlights",
    description: "Moments forts probables d'une vidéo (mouvement, netteté, couleurs, son) avec score. À confirmer visuellement.",
    inputSchema: obj({ media_id: str(), count: num("défaut 6"), segment_seconds: num("durée d'un moment, défaut 3") }, ["media_id"]),
  },
  { name: "analyze_audio", description: "Silences et loudness (LUFS) d'un média.", inputSchema: obj({ media_id: str(), noise_db: num(), min_silence: num() }, ["media_id"]) },
  {
    name: "analyze_music",
    description: "Analyse rythmique d'une musique : BPM, beats, downbeats (temps forts de mesure), onsets et moments les plus forts (drops). Indispensable pour caler les coupes.",
    inputSchema: obj({ media_id: str(), start: num("analyser à partir de (s)"), max_seconds: num("défaut 240") }, ["media_id"]),
  },
  { name: "list_effects", description: "Effets vidéo GPU (id, paramètres min/max/défaut), transitions et effets audio disponibles dans CUTTINX.", inputSchema: obj({}) },
  { name: "list_projects", description: "Projets CUTTINX de l'utilisateur.", inputSchema: obj({}) },
  { name: "get_project", description: "Résumé d'un projet CUTTINX (pistes, clips, effets, transitions).", inputSchema: obj({ project_id: str() }, ["project_id"]) },
  {
    name: "create_project",
    description:
      "Crée (ou remplace avec project_id) un projet CUTTINX modifiable à partir d'une recette. Piste principale = clips dans l'ordre ; musique en boucle sur toute la durée ; textes et calques d'effets sur la timeline finale. Tous les temps en secondes.",
    inputSchema: obj({
      name: str(),
      project_id: str("pour REMPLACER un projet existant (itérations)"),
      format: obj({ aspect: en(["9:16", "16:9", "1:1", "4:5", "4:3", "21:9"]), fps: num("24–60, défaut 30"), short_side: num("défaut 1080"), background: str("#RRGGBB") }),
      clips: arr(obj({
        media_id: str(),
        start: num("début dans la source (s)"),
        end: num("fin dans la source (s)"),
        duration: num("durée d'une photo (s)"),
        speed: num("0.1–100 (0.5 = ralenti)"),
        speed_ramp: arr(obj({ position: num("0–1 le long du clip"), speed: num() }, ["position", "speed"]), "courbe de vitesse progressive (rampe ralenti→rapide…), ≥2 points"),
        volume: num("son d'origine 0–4"),
        mute: bool(),
        fill: bool("remplir le cadre en recadrant (défaut true)"),
        scale: num("échelle 1 = cadre"),
        pos_x: num("centre 0–1"), pos_y: num("centre 0–1"), rotation: num("degrés"), mirror: bool(),
        zoom: obj({ from: num("échelle relative au début, ex 1.0"), to: num("échelle à la fin, ex 1.15"), over: num("fraction du clip 0–1, défaut 1"), easing: en(["smooth", "linear", "ease_in", "ease_out"]) }),
        effects: fxList,
        transition_in: obj({ id: str("id de transition (list_effects)"), duration: num("s"), params: { type: "object", additionalProperties: { type: "number" } } }, ["id"]),
        audio_fade_in: num(), audio_fade_out: num(),
      }, ["media_id"]), "clips de la piste principale, dans l'ordre"),
      music: obj({
        media_id: str(), start: num("instant de départ dans la musique (s) — ex. juste avant le drop"),
        at: num("position sur la timeline (s), défaut 0"), end_at: num("fin sur la timeline, défaut fin de la vidéo"),
        volume: num("défaut 1"), fade_in: num(), fade_out: num("défaut 1.5"), loop: bool("défaut true"),
      }, ["media_id"]),
      texts: arr(obj({
        text: str(), start: num(), end: num(),
        position: { description: "top | center | bottom ou 0–1 (vertical)", anyOf: [{ type: "string", enum: ["top", "center", "bottom"] }, { type: "number" }] },
        size: num("taille sp, défaut 64"), color: str("#RRGGBB ou nom"), gradient_to: str("couleur de fin de dégradé"),
        font: en(["bebas neue", "anton", "montserrat extrabold", "montserrat", "oswald", "bangers", "righteous", "fredoka", "pacifico", "lobster", "caveat", "permanent marker", "abril fatface", "playfair display", "press start 2p", "system", "condensed", "black"]),
        stroke: num("épaisseur contour"), stroke_color: str(), background: str("#AARRGGBB"), uppercase: bool(), letter_spacing: num(),
        anim_in: en(["none", "fade", "pop", "slide_up", "slide_down", "slide_left", "slide_right", "typewriter", "zoom", "bounce", "blur_in", "wave"]),
        anim_out: en(["none", "fade", "pop", "slide_up", "slide_down", "slide_left", "slide_right", "typewriter", "zoom", "bounce", "blur_in", "wave"]),
      }, ["text", "start", "end"])),
      effect_layers: arr(obj({ effect_id: str(), params: { type: "object", additionalProperties: { type: "number" } }, start: num(), end: num() }, ["effect_id"]), "calques d'effets appliqués à toute l'image sur un intervalle (grain, look ciné, flash…)"),
    }, ["name", "clips"]),
  },
  {
    name: "render_project",
    description: "Exporte un projet en MP4 sur le téléphone (draft = 480p rapide pour vérifier, final = qualité haute). Renvoie un job_id à suivre avec get_render.",
    inputSchema: obj({ project_id: str(), quality: en(["draft", "final"]), resolution: num("petit côté pour final, ex 1080 ou 2160") }, ["project_id"]),
  },
  { name: "get_render", description: "État d'un export (attend jusqu'à wait_seconds). Le résultat est un média consultable avec view_storyboard.", inputSchema: obj({ job_id: str(), wait_seconds: num("défaut 40, max 90") }, ["job_id"]) },
  { name: "import_from_url", description: "Télécharge un fichier (musique, vidéo, image) depuis une URL directe sur le téléphone et le partage avec toi.", inputSchema: obj({ url: str(), name: str() }, ["url"]) },
  { name: "get_command_result", description: "Récupère le résultat d'une commande trop longue (command_id donné précédemment).", inputSchema: obj({ command_id: str(), wait_seconds: num("défaut 60, max 100") }, ["command_id"]) },
];

const textResult = (text: string, isError = false) => ({ content: [{ type: "text", text }], ...(isError ? { isError: true } : {}) });

async function waitResult(id: string, ms: number) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const { data } = await db.from("vs_commands").select("status,result").eq("id", id).maybeSingle();
    if (!data) return textResult("Commande introuvable.", true);
    if (data.status === "done" || data.status === "error") {
      await db.from("vs_commands").delete().eq("id", id);
      return data.result ?? textResult("Réponse vide du téléphone.", true);
    }
    await sleep(Date.now() - t0 < 5000 ? 250 : 600);
  }
  return textResult(
    `Le téléphone travaille encore (command_id: ${id}). Appelle get_command_result avec ce command_id pour récupérer la réponse.`,
  );
}

async function callTool(tokenHash: string, name: string, args: Record<string, unknown>) {
  if (!TOOLS.some((t) => t.name === name)) return textResult(`Outil inconnu : ${name}`, true);

  if (name === "get_command_result") {
    const wait = Math.min(Number(args.wait_seconds ?? 60), 100) * 1000;
    const { data } = await db.from("vs_commands").select("token_hash").eq("id", String(args.command_id)).maybeSingle();
    if (!data || data.token_hash !== tokenHash) return textResult("Commande introuvable (déjà récupérée ou expirée).", true);
    return waitResult(String(args.command_id), wait);
  }

  const { data: dev } = await db.from("vs_devices").select("last_seen,info").eq("token_hash", tokenHash).maybeSingle();
  const online = dev && Date.now() - new Date(dev.last_seen).getTime() < DEVICE_TIMEOUT_MS;
  if (!online) {
    return textResult(
      "Le téléphone n'est pas connecté. Demande à l'utilisateur d'ouvrir CUTTINX sur son téléphone (avec Internet) → icône ✨ Claude → activer « Connecté à Claude », puis réessaie." +
        (dev ? ` Dernière connexion : ${new Date(dev.last_seen).toISOString()}.` : ""),
      true,
    );
  }

  // get_job peut attendre côté téléphone : on borne pour rester sous la limite du relais
  if (name === "get_render") args = { ...args, wait_seconds: Math.min(Number(args.wait_seconds ?? 40), 90) };

  const { data: cmd, error } = await db.from("vs_commands").insert({ token_hash: tokenHash, tool: name, args }).select("id").single();
  if (error) return textResult("Erreur du relais : " + error.message, true);

  // Ménage occasionnel
  if (Math.random() < 0.05) {
    await db.from("vs_commands").delete().lt("created_at", new Date(Date.now() - 86_400_000).toISOString());
  }
  return waitResult(cmd.id, CALL_WAIT_MS);
}

async function handleRpc(tokenHash: string, msg: any) {
  const { id, method, params } = msg ?? {};
  const reply = (result: unknown) => ({ jsonrpc: "2.0", id, result });
  switch (method) {
    case "initialize":
      return reply({
        protocolVersion: params?.protocolVersion || "2025-06-18",
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "cuttinx", title: "CUTTINX", version: "1.0.0" },
        instructions: INSTRUCTIONS,
      });
    case "ping":
      return reply({});
    case "tools/list":
      return reply({ tools: TOOLS });
    case "tools/call":
      try {
        return reply(await callTool(tokenHash, params?.name, params?.arguments ?? {}));
      } catch (e) {
        return reply(textResult("Erreur du relais : " + (e as Error).message, true));
      }
    case "resources/list":
      return reply({ resources: [] });
    case "prompts/list":
      return reply({ prompts: [] });
    default:
      if (id === undefined || id === null) return null; // notification
      return { jsonrpc: "2.0", id, error: { code: -32601, message: `Méthode inconnue : ${method}` } };
  }
}

// ---------------------------------------------------------------- routes
Deno.serve(async (req) => {
  const url = new URL(req.url);
  const parts = url.pathname.split("/").filter(Boolean);
  const i = parts.indexOf("cuttinx");
  const [section, token, action, extra] = parts.slice(i + 1);

  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
        "Access-Control-Allow-Headers": "content-type,authorization,mcp-protocol-version,mcp-session-id",
      },
    });
  }
  if (!section) return json({ ok: true, service: "cuttinx-relay" });
  if (!token || token.length < 24) return json({ error: "Jeton invalide" }, 404);
  const tokenHash = await hashToken(token);

  // ----- Claude (MCP) -----
  if (section === "mcp") {
    if (req.method !== "POST") {
      return json({ jsonrpc: "2.0", error: { code: -32000, message: "Méthode non autorisée" }, id: null }, 405);
    }
    let body: any;
    try {
      body = await req.json();
    } catch {
      return json({ jsonrpc: "2.0", error: { code: -32700, message: "JSON invalide" }, id: null }, 400);
    }
    if (Array.isArray(body)) {
      const out = (await Promise.all(body.map((m) => handleRpc(tokenHash, m)))).filter(Boolean);
      return out.length ? json(out) : new Response(null, { status: 202 });
    }
    const out = await handleRpc(tokenHash, body);
    return out ? json(out) : new Response(null, { status: 202 });
  }

  // ----- Téléphone -----
  if (section === "device") {
    if (action === "next" && req.method === "GET") {
      const info = url.searchParams.get("info");
      const seen = async () =>
        db.from("vs_devices").upsert({
          token_hash: tokenHash,
          last_seen: new Date().toISOString(),
          ...(info ? { info: JSON.parse(info) } : {}),
        });
      await seen();
      const t0 = Date.now();
      let n = 0;
      while (Date.now() - t0 < POLL_WAIT_MS) {
        const { data, error } = await db.rpc("vs_claim", { p_token_hash: tokenHash });
        if (error) return json({ error: error.message }, 500);
        if (data?.length) {
          const c = data[0];
          return json({ command: { id: c.id, tool: c.tool, args: c.args } });
        }
        if (++n % 15 === 0) await seen();
        await sleep(500);
      }
      return json({ command: null });
    }
    if (action === "result" && extra && req.method === "POST") {
      const body = await req.json().catch(() => null);
      if (!body) return json({ error: "JSON invalide" }, 400);
      const { error } = await db
        .from("vs_commands")
        .update({ status: body.isError ? "error" : "done", result: body, updated_at: new Date().toISOString() })
        .eq("id", extra)
        .eq("token_hash", tokenHash);
      if (error) return json({ error: error.message }, 500);
      await db.from("vs_devices").upsert({ token_hash: tokenHash, last_seen: new Date().toISOString() });
      return json({ ok: true });
    }
  }
  return json({ error: "Introuvable" }, 404);
});
