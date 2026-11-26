import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { useEscToClose } from './ui'
import { GokuGuide } from './goku-guide'
import type {
  GokuAccessResult, GokuActionCard, GokuActionResult, GokuActionStatus, GokuChatReply, GokuConfig, GokuGroupedByTier,
  GokuMessage, GokuTier, GokuUpload, GokuUserSearchResult,
} from './types'

const MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024

const MAX_HISTORY = 40
const MAX_CONTENT = 4000

const STATUS_WORD: Record<GokuActionStatus, string> = {
  pending: 'not confirmed yet', applying: 'being applied', applied: 'confirmed and applied', dismissed: 'dismissed by the user', failed: 'failed',
}

// What actually goes to /api/goku/chat for one message: the structured payload if the guided flow
// set one, plus — for an assistant turn that proposed changes — what became of each proposal, so
// Goku never claims something was saved that the user dismissed (or vice versa).
function toWire(m: GokuMessage) {
  let content = m.payload ?? m.content
  if (m.actions?.length) {
    content += `\n\n[Proposed changes — ${m.actions.map(a => `${a.summary}: ${STATUS_WORD[a.status]}${a.error ? ` (${a.error})` : ''}`).join('; ')}]`
  }
  return { role: m.role, content: content.slice(0, MAX_CONTENT) }
}

// The backend takes at most 40 messages and the conversation must start with a user turn.
function trimHistory(messages: GokuMessage[]) {
  const recent = messages.slice(-MAX_HISTORY)
  const firstUser = recent.findIndex(m => m.role === 'user')
  return firstUser <= 0 ? recent : recent.slice(firstUser)
}

export type GokuSendOptions = { content?: string; payload?: string; attachment?: GokuUpload | null }

// Goku's state + send logic, lifted out of the launcher/panel so both can render as siblings
// at the top of App.tsx. Conversation lives only in this hook's state; Goku keeps no
// server-side history, so a reload starts fresh, same as the rate limit resetting daily.
// `onDataChanged` runs after any confirmed change, so the page behind the chat isn't stale.
export function useGoku(onDataChanged?: () => void) {
  const [available, setAvailable] = useState(false)
  const [admin, setAdmin] = useState(false)
  const [unavailableReason, setUnavailableReason] = useState('')
  const [open, setOpen] = useState(false)
  const [showAdmin, setShowAdmin] = useState(false)
  const [messages, setMessages] = useState<GokuMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [remaining, setRemaining] = useState<number | null>(null)
  const [attachment, setAttachment] = useState<GokuUpload | null>(null)
  const [attaching, setAttaching] = useState(false)
  const [guideOpen, setGuideOpen] = useState(true)
  const dataChanged = useRef(onDataChanged)
  dataChanged.current = onDataChanged

  useEffect(() => {
    api<GokuConfig>('/api/goku/config')
      .then(cfg => { setAvailable(cfg.available); setAdmin(cfg.admin); setUnavailableReason(cfg.reason ?? '') })
      .catch(() => { setAvailable(false); setAdmin(false); setUnavailableReason('') })
  }, [])

  const attachFile = async (file: File): Promise<GokuUpload | null> => {
    if (file.size > MAX_ATTACHMENT_BYTES) {
      setError(`That file is larger than ${MAX_ATTACHMENT_BYTES / (1024 * 1024)}MB.`)
      return null
    }
    setAttaching(true)
    setError('')
    try {
      const result = await api<GokuUpload>('/api/goku/uploads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream', 'X-Filename': file.name },
        body: file,
      })
      setAttachment(result)
      return result
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not attach that file')
      return null
    } finally {
      setAttaching(false)
    }
  }

  const send = async (opts: GokuSendOptions = {}) => {
    const upload = opts.attachment !== undefined ? opts.attachment : attachment
    const text = (opts.content ?? draft).trim()
    if ((!text && !upload) || sending) return
    const content = text || `Please read the attached file: ${upload!.filename}`
    let payload = opts.payload ?? (upload ? content : undefined)
    if (upload) payload = `${payload}\n\n[Attached file: ${upload.filename} — upload_id: ${upload.uploadId}]`
    const next: GokuMessage[] = [...messages, { id: crypto.randomUUID(), role: 'user', content, payload, attachmentLabel: upload?.filename }]
    setMessages(next)
    if (opts.content === undefined) setDraft('')
    setAttachment(null)
    setGuideOpen(false)
    setError('')
    setSending(true)
    try {
      const result = await api<GokuChatReply>('/api/goku/chat', {
        method: 'POST',
        body: JSON.stringify({ messages: trimHistory(next).map(toWire) }),
      })
      const actions: GokuActionCard[] = (result.pendingActions ?? []).map(a => ({ ...a, status: 'pending' }))
      setMessages(m => [...m, { id: crypto.randomUUID(), role: 'assistant', content: result.reply, actions: actions.length ? actions : undefined }])
      setRemaining(result.queriesRemainingToday)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Goku is unavailable right now — try again shortly.')
    } finally {
      setSending(false)
    }
  }

  const setActionStatus = (actionId: string, status: GokuActionStatus, actionError?: string) =>
    setMessages(ms => ms.map(m => !m.actions ? m : { ...m, actions: m.actions.map(a => a.id === actionId ? { ...a, status, error: actionError } : a) }))

  const confirmAction = async (actionId: string) => {
    setActionStatus(actionId, 'applying')
    try {
      await api<GokuActionResult>(`/api/goku/actions/${encodeURIComponent(actionId)}/confirm`, { method: 'POST' })
      setActionStatus(actionId, 'applied')
      dataChanged.current?.()
    } catch (err) {
      setActionStatus(actionId, 'failed', err instanceof Error ? err.message : 'Could not apply that change')
    }
  }

  const dismissAction = async (actionId: string) => {
    setActionStatus(actionId, 'dismissed')
    await api(`/api/goku/actions/${encodeURIComponent(actionId)}`, { method: 'DELETE' }).catch(() => { /* expires on its own */ })
  }

  // One at a time, in order — a later proposal may depend on an earlier one (a holding before its transactions).
  const confirmAll = async (actionIds: string[]) => {
    for (const id of actionIds) await confirmAction(id)
  }

  const reset = () => {
    messages.flatMap(m => m.actions ?? []).filter(a => a.status === 'pending').forEach(a => void dismissAction(a.id))
    setMessages([])
    setDraft('')
    setAttachment(null)
    setError('')
    setGuideOpen(true)
  }

  return {
    available, admin, unavailableReason, open, setOpen, showAdmin, setShowAdmin, messages, draft, setDraft,
    sending, error, remaining, send, attachment, setAttachment, attaching, attachFile,
    guideOpen, setGuideOpen, confirmAction, dismissAction, confirmAll, reset,
  }
}

export type Goku = ReturnType<typeof useGoku>

// Floating action button, bottom-right on every page — rendered once `available` is true,
// hidden entirely for anyone off the allowlist. Toggles the chat panel; swaps to a close icon
// while it's open so the button itself is the only thing you need to click either way.
export function GokuLauncher({ goku }: Readonly<{ goku: Goku }>) {
  return <button type="button" className={`goku-launcher${goku.open ? ' active' : ''}`}
    onClick={() => goku.setOpen(o => !o)} title={goku.open ? 'Close Goku' : 'Ask Goku about your portfolio'}>
    <span aria-hidden>{goku.open ? '×' : '⚡'}</span>
  </button>
}

// Shown only to admins (app.goku.admin-allowlist), independent of `available` — an admin should
// be able to grant Goku access to others (or to themselves) even before they've granted it to
// themselves personally. Lives in the sidebar nav since it's an admin/settings action, not the
// chat itself.
export function GokuAdminButton({ goku }: Readonly<{ goku: Goku }>) {
  return <button type="button" className="goku-nav-btn goku-admin-btn"
    onClick={() => goku.setShowAdmin(true)} title="Manage who can access Goku">
    <span className="goku-nav-icon" aria-hidden>⚙</span> Goku access
  </button>
}

const ACTION_STATUS_LABEL: Partial<Record<GokuActionStatus, string>> = {
  applying: 'Applying…', applied: '✓ Done', dismissed: 'Dismissed',
}

// A change Goku proposed. Nothing has happened yet — the Confirm click below is the one and only
// trigger (see GokuActionService on the backend); Goku itself can't apply anything.
function GokuActionCards({ goku, actions }: Readonly<{ goku: Goku; actions: GokuActionCard[] }>) {
  const pending = actions.filter(a => a.status === 'pending')
  return <div className="goku-actions">
    {actions.map(a => <div key={a.id} className={`goku-action${a.destructive ? ' destructive' : ''} ${a.status}`}>
      <p>{a.summary}</p>
      {a.status === 'pending' && <div className="goku-action-buttons">
        <button type="button" className="outline" onClick={() => void goku.dismissAction(a.id)}>Dismiss</button>
        <button type="button" className={a.destructive ? 'danger' : 'primary'} onClick={() => void goku.confirmAction(a.id)}>
          {a.destructive ? 'Delete' : 'Confirm'}
        </button>
      </div>}
      {a.status === 'failed' && <small className="goku-action-error">{a.error}</small>}
      {ACTION_STATUS_LABEL[a.status] && <small className="goku-action-status">{ACTION_STATUS_LABEL[a.status]}</small>}
    </div>)}
    {pending.length > 1 && !pending.some(a => a.destructive) && <button type="button" className="primary goku-confirm-all"
      onClick={() => void goku.confirmAll(pending.map(a => a.id))}>Confirm all {pending.length}</button>}
  </div>
}

export function GokuPanel({ goku }: Readonly<{ goku: Goku }>) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  useEscToClose(() => goku.setOpen(false), false)
  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [goku.messages, goku.sending, goku.guideOpen])

  if (!goku.open) return null

  const pickFile = (files: FileList | null) => {
    const file = files?.[0]
    if (file) void goku.attachFile(file)
    if (fileRef.current) fileRef.current.value = ''
  }
  const typeInstead = () => { goku.setGuideOpen(false); setTimeout(() => inputRef.current?.focus(), 0) }

  return <section className="goku-panel">
    <div className="goku-panel-header">
      <div><p className="eyebrow">GOKU</p><h3>Portfolio assistant</h3></div>
      <div className="goku-panel-tools">
        {!goku.guideOpen && <button type="button" className="goku-menu-btn" onClick={() => goku.setGuideOpen(true)} title="Show the topic menu">☰ Menu</button>}
        {goku.messages.length > 0 && <button type="button" className="goku-menu-btn" onClick={goku.reset} title="Start a new conversation">New chat</button>}
        <button className="close" onClick={() => goku.setOpen(false)} aria-label="Close">×</button>
      </div>
    </div>
    <div className="goku-messages" ref={scrollRef}>
      {goku.messages.map(m => <div key={m.id} className={`goku-msg-group goku-msg-group-${m.role}`}>
        <div className={`goku-msg goku-msg-${m.role}`}>
          {m.attachmentLabel && <span className="goku-attachment-chip">📎 {m.attachmentLabel}</span>}
          {m.content}
        </div>
        {m.actions && <GokuActionCards goku={goku} actions={m.actions} />}
      </div>)}
      {goku.sending && <div className="goku-msg goku-msg-assistant goku-typing"><span /><span /><span /></div>}
      {goku.error && <div className="goku-msg goku-msg-error">{goku.error}</div>}
      {goku.guideOpen && !goku.sending && <GokuGuide goku={goku} onFreeform={typeInstead} />}
    </div>
    {goku.attachment && <div className="goku-pending-attachment">
      <span>📎 {goku.attachment.filename}</span>
      <button type="button" onClick={() => goku.setAttachment(null)} aria-label="Remove attachment">×</button>
    </div>}
    <div className="goku-input-row">
      <input ref={fileRef} type="file" accept=".pdf,.xlsx,.xls,.csv,.tsv,.xml,.txt" hidden
        onChange={e => pickFile(e.target.files)} />
      <button type="button" className="goku-attach-btn" title="Attach a statement to import"
        disabled={goku.sending || goku.attaching} onClick={() => fileRef.current?.click()}>
        {goku.attaching ? '…' : '📎'}
      </button>
      <textarea ref={inputRef} rows={1} value={goku.draft} placeholder="Ask Goku…" disabled={goku.sending}
        onChange={e => goku.setDraft(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void goku.send() } }} />
      <button type="button" className="primary" onClick={() => void goku.send()}
        disabled={goku.sending || (!goku.draft.trim() && !goku.attachment)}>Send</button>
    </div>
    <p className="goku-footnote">
      Goku only sees your own data and never changes anything until you press Confirm.
      {goku.remaining != null && ` · ${goku.remaining} question${goku.remaining === 1 ? '' : 's'} left today`}
    </p>
  </section>
}

const TIER_LABELS: Record<GokuTier, string> = { NO_ACCESS: 'No access', HAIKU: 'Haiku', SONNET: 'Sonnet', OPUS: 'Opus' }
const TIERS: GokuTier[] = ['NO_ACCESS', 'HAIKU', 'SONNET', 'OPUS']
const MODEL_GROUPS: Extract<GokuTier, 'HAIKU' | 'SONNET' | 'OPUS'>[] = ['HAIKU', 'SONNET', 'OPUS']
const EMPTY_GROUPED: GokuGroupedByTier = { HAIKU: [], SONNET: [], OPUS: [] }

// Database-backed allowlist admin panel — every call is re-checked server-side against
// app.goku.admin-allowlist, so hiding this modal is a convenience, not the access control.
//
// Top: a typeahead over every registered user (not just ones already allowed) — picking one
// shows a single tier dropdown that both grants/changes/revokes their access in one PUT.
// Bottom: everyone currently allowed, grouped into the three tiers so an admin can see who's on
// what without hunting through one flat list.
export function GokuAdminModal({ goku }: Readonly<{ goku: Goku }>) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<GokuUserSearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const [selected, setSelected] = useState<GokuUserSearchResult | null>(null)
  const [assigning, setAssigning] = useState(false)
  const [grouped, setGrouped] = useState<GokuGroupedByTier>(EMPTY_GROUPED)
  const [loadingGrouped, setLoadingGrouped] = useState(true)
  const [error, setError] = useState('')
  const boxRef = useRef<HTMLDivElement>(null)
  useEscToClose(() => goku.setShowAdmin(false), false)

  const loadGrouped = () => {
    setLoadingGrouped(true)
    api<GokuGroupedByTier>('/api/goku/admin/allowlist/grouped')
      .then(setGrouped)
      .catch(err => setError(err instanceof Error ? err.message : 'Could not load the allowlist'))
      .finally(() => setLoadingGrouped(false))
  }

  useEffect(() => {
    if (goku.showAdmin) loadGrouped()
  }, [goku.showAdmin])

  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) { setResults([]); setSearching(false); return }
    setSearching(true)
    const timer = setTimeout(() => {
      api<GokuUserSearchResult[]>(`/api/goku/admin/users?q=${encodeURIComponent(q)}`)
        .then(setResults).catch(() => setResults([])).finally(() => setSearching(false))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setResults([]) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  if (!goku.showAdmin) return null

  const pick = (result: GokuUserSearchResult) => { setSelected(result); setQuery(''); setResults([]) }

  const assign = async (tier: GokuTier) => {
    if (!selected || assigning) return
    setAssigning(true)
    setError('')
    try {
      const result = await api<GokuAccessResult>(`/api/goku/admin/allowlist/access?email=${encodeURIComponent(selected.email)}`, {
        method: 'PUT', body: JSON.stringify({ tier }),
      })
      setSelected(s => s && { ...s, tier: result.tier })
      loadGrouped()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change that access')
    } finally {
      setAssigning(false)
    }
  }

  return <div className="modal-backdrop"><section className="modal narrow">
    <div className="modal-header">
      <div><p className="eyebrow">GOKU</p><h2>Who can access Goku</h2></div>
      <button className="close" onClick={() => goku.setShowAdmin(false)} aria-label="Close">×</button>
    </div>
    {goku.unavailableReason && <p className="hint goku-admin-error">
      Chat isn't showing for you yet: {goku.unavailableReason}
    </p>}
    {error && <p className="hint goku-admin-error">{error}</p>}

    <div className="goku-admin-search" ref={boxRef}>
      <input type="email" value={query} placeholder="Search by email…"
        onChange={e => { setQuery(e.target.value); setSelected(null) }} />
      {query.trim().length >= 2 && (searching || results.length > 0) && <ul className="tag-menu">
        {searching && results.length === 0 && <li className="tag-menu-note">Searching…</li>}
        {results.map(r => <li key={r.email}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => pick(r)}>
          <b>{r.email}</b> <span>{TIER_LABELS[r.tier]}</span>
        </button></li>)}
      </ul>}
    </div>

    {selected && <div className="goku-admin-selected">
      <div><strong>{selected.email}</strong>{selected.displayName && <small>{selected.displayName}</small>}</div>
      <select aria-label={`Access for ${selected.email}`} value={selected.tier} disabled={assigning}
        onChange={e => void assign(e.target.value as GokuTier)}>
        {TIERS.map(t => <option key={t} value={t}>{TIER_LABELS[t]}</option>)}
      </select>
    </div>}

    <div className="goku-admin-groups">
      {loadingGrouped
        ? <p className="hint">Loading…</p>
        : MODEL_GROUPS.map(tier => <details key={tier} className="goku-model-group">
            <summary>{TIER_LABELS[tier]} <span className="goku-model-group-count">{grouped[tier].length}</span></summary>
            <ul className="goku-admin-list">
              {grouped[tier].map(e => <li key={e}>{e}</li>)}
              {grouped[tier].length === 0 && <li className="hint">No one yet.</li>}
            </ul>
          </details>)}
    </div>
  </section></div>
}
