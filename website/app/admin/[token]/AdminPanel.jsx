'use client'
import { upload, uploadPresigned } from '@vercel/blob/client'
import { useState } from 'react'
import Icon from '@/components/Icon'

const KINDS = [
  { key: 'android', label: 'Android', ext: '.apk', folder: 'android', type: 'application/vnd.android.package-archive', hint: 'Fichier .apk (dans cuttinx-apk.zip)' },
  { key: 'windowsExe', label: 'Windows · installeur EXE', ext: '.exe', folder: 'windows', type: 'application/vnd.microsoft.portable-executable', hint: 'Fichier .exe (dans cuttinx-pro-windows.zip)' },
  { key: 'windowsMsi', label: 'Windows · MSI (facultatif)', ext: '.msi', folder: 'windows', type: 'application/x-msi', hint: 'Fichier .msi (dans cuttinx-pro-windows.zip)' },
]

function size(b) { if (!b) return ''; const mb = b / 1048576; return mb >= 1024 ? `${(mb / 1024).toFixed(2)} Go` : `${mb.toFixed(1)} Mo` }

export default function AdminPanel({ token, initial, storage, mode }) {
  const [current, setCurrent] = useState(initial)
  const [version, setVersion] = useState(initial?.version || '1.0.0')
  const [notes, setNotes] = useState(initial?.notes || '')
  const [files, setFiles] = useState({})
  const [removed, setRemoved] = useState({})
  const [progress, setProgress] = useState({})
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [over, setOver] = useState(null)

  const pick = (key, file, ext) => {
    if (!file) return
    if (!file.name.toLowerCase().endsWith(ext)) { setMsg({ err: true, text: `« ${file.name} » n'est pas un fichier ${ext}` }); return }
    setFiles((f) => ({ ...f, [key]: file })); setRemoved((r) => ({ ...r, [key]: false })); setMsg(null)
  }

  async function publish() {
    setBusy(true); setMsg(null)
    try {
      const next = { version, notes }
      for (const k of KINDS) {
        const file = files[k.key]
        if (file) {
          const safe = file.name.replace(/[^A-Za-z0-9._-]+/g, '-')
          const send = mode === 'presigned' ? uploadPresigned : upload
          const blob = await send(`downloads/${k.folder}/${Date.now().toString(36)}-${safe}`, file, {
            access: 'public', handleUploadUrl: '/api/upload', clientPayload: token, contentType: k.type,
            multipart: file.size > 20 * 1024 * 1024,
            onUploadProgress: ({ percentage }) => setProgress((p) => ({ ...p, [k.key]: percentage })),
          })
          next[k.key] = { url: blob.url, name: file.name, size: file.size }
        } else if (!removed[k.key] && current?.[k.key]) next[k.key] = current[k.key]
      }
      const res = await fetch('/api/release', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token, release: next }) })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Publication impossible')
      setCurrent(data.release); setFiles({}); setRemoved({}); setProgress({})
      setMsg({ text: `Version ${data.release.version} en ligne. Le site affiche les nouveaux fichiers d'ici une minute.` })
    } catch (e) {
      setMsg({ err: true, text: e.message })
    } finally {
      setBusy(false)
    }
  }

  const nothing = !KINDS.some((k) => files[k.key]) && !KINDS.some((k) => removed[k.key])
  const unchanged = nothing && version === (current?.version || '') && notes === (current?.notes || '')
  return (
    <main className="admin">
      <div className="row"><Icon name="Lock" size={22} /><h1>Publier une version</h1></div>
      <p className="dim">Page privée : garde son adresse pour toi. Dépose les fichiers, choisis le numéro de version, puis publie. Les anciens fichiers remplacés sont supprimés automatiquement.</p>
      {!storage ? <div className="msg err">Le stockage n'est pas encore connecté. Sur Vercel : Storage → Create → Blob (public) → Connect au projet, puis redéploie (Deployments → ⋯ → Redeploy).</div> : null}

      <section className="panel current">
        <h2>En ligne actuellement</h2>
        {current ? (
          <>
            <p>Version <strong>{current.version}</strong>{current.publishedAt ? ` · publiée le ${new Date(current.publishedAt).toLocaleString('fr-FR')}` : ''}</p>
            {KINDS.map((k) => current[k.key] ? (
              <div key={k.key} className="row">
                <span className="dim">{k.label} :</span> <a href={current[k.key].url}>{current[k.key].name}</a> <span className="dim">{size(current[k.key].size)}</span>
                {removed[k.key] ? <span className="dim">(sera retiré)</span> : <button className="link-btn" onClick={() => setRemoved((r) => ({ ...r, [k.key]: true }))}>Retirer</button>}
              </div>
            ) : null)}
            <p className="dim">Liens à partager (toujours la dernière version) : <code>/telecharger/android</code> et <code>/telecharger/windows</code></p>
          </>
        ) : <p className="dim">Rien n'est encore publié ici : en attendant, le site propose les fichiers compilés automatiquement sur GitHub (dernière version).</p>}
      </section>

      <section className="panel">
        <h2>Nouvelle version</h2>
        <label className="field">Numéro de version<input value={version} onChange={(e) => setVersion(e.target.value)} placeholder="1.2.0" maxLength={40} /></label>
        <label className="field">Nouveautés (affichées sur le site)<textarea value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Actions rapides (Ctrl K), historique des versions…" maxLength={3000} /></label>
        {KINDS.map((k) => (
          <label key={k.key} className={`drop${over === k.key ? ' over' : ''}`}
            onDragOver={(e) => { e.preventDefault(); setOver(k.key) }} onDragLeave={() => setOver(null)}
            onDrop={(e) => { e.preventDefault(); setOver(null); pick(k.key, e.dataTransfer.files?.[0], k.ext) }}>
            <input type="file" accept={k.ext} onChange={(e) => pick(k.key, e.target.files?.[0], k.ext)} />
            <strong><Icon name="Download" size={18} /> {k.label}</strong>
            <span className="dim">{files[k.key] ? `${files[k.key].name} · ${size(files[k.key].size)}` : `${k.hint}. Glisse-le ici ou clique.`}</span>
            {progress[k.key] != null ? <div className="bar"><span style={{ width: `${progress[k.key]}%` }} /></div> : null}
          </label>
        ))}
        <button className="btn btn-primary" onClick={publish} disabled={busy || unchanged || !storage}>
          {busy ? 'Envoi en cours… garde la page ouverte' : 'Publier cette version'}
        </button>
        {msg ? <div className={`msg ${msg.err ? 'err' : 'ok'}`}>{msg.text}</div> : null}
      </section>
    </main>
  )
}
