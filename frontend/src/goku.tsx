import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { ago } from './util'
import { Field, useEscToClose } from './ui'
import type { GokuAllowedUser, GokuChatReply, GokuConfig, GokuMessage } from './types'

// Goku's state + send logic, lifted out of the panel so its trigger can live in the sidebar
// nav (see App.tsx) while the panel itself renders elsewhere. Conversation lives only in this
// hook's state; Goku keeps no server-side history, so a reload starts fresh, same as the rate
// limit resetting daily.
export function useGoku() {
  const [available, setAvailable] = useState(false)
  const [admin, setAdmin] = useState(false)
  const [open, setOpen] = useState(false)
  const [showAdmin, setShowAdmin] = useState(false)
  const [messages, setMessages] = useState<GokuMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [remaining, setRemaining] = useState<number | null>(null)

  useEffect(() => {
    api<GokuConfig>('/api/goku/config').then(cfg => { setAvailable(cfg.available); setAdmin(cfg.admin) })
      .catch(() => { setAvailable(false); setAdmin(false) })
  }, [])

  const send = async () => {
    const text = draft.trim()
    if (!text || sending) return
    const next: GokuMessage[] = [...messages, { id: crypto.randomUUID(), role: 'user', content: text }]
    setMessages(next)
    setDraft('')
    setError('')
    setSending(true)
    try {
      const result = await api<GokuChatReply>('/api/goku/chat', {
        method: 'POST',
        body: JSON.stringify({ messages: next.map(({ role, content }) => ({ role, content })) }),
      })
      setMessages(m => [...m, { id: crypto.randomUUID(), role: 'assistant', content: result.reply }])
      setRemaining(result.queriesRemainingToday)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Goku is unavailable right now — try again shortly.')
    } finally {
      setSending(false)
    }
  }

  return { available, admin, open, setOpen, showAdmin, setShowAdmin, messages, draft, setDraft, sending, error, remaining, send }
}

export type Goku = ReturnType<typeof useGoku>

// Sidebar nav entry — same shape as the page links above it (icon + label), so Goku reads as
// part of the app's own navigation rather than a bolted-on widget. Only rendered once `available`
// is true; hidden entirely for anyone off the allowlist.
export function GokuNavButton({ goku }: Readonly<{ goku: Goku }>) {
  return <button type="button" className={`goku-nav-btn${goku.open ? ' active' : ''}`}
    onClick={() => goku.setOpen(o => !o)} title="Ask Goku about your portfolio">
    <span className="goku-nav-icon" aria-hidden>⚡</span> Goku
  </button>
}

// Shown only to admins (app.goku.admin-allowlist), independent of `available` — an admin should
// be able to grant Goku access to others (or to themselves) even before they've granted it to
// themselves personally.
export function GokuAdminButton({ goku }: Readonly<{ goku: Goku }>) {
  return <button type="button" className="goku-nav-btn goku-admin-btn"
    onClick={() => goku.setShowAdmin(true)} title="Manage who can access Goku">
    <span className="goku-nav-icon" aria-hidden>⚙</span> Goku access
  </button>
}

export function GokuPanel({ goku }: Readonly<{ goku: Goku }>) {
  const scrollRef = useRef<HTMLDivElement>(null)
  useEscToClose(() => goku.setOpen(false), false)
  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [goku.messages, goku.sending])

  if (!goku.open) return null
  return <section className="goku-panel">
    <div className="goku-panel-header">
      <div><p className="eyebrow">GOKU</p><h3>Ask about your portfolio</h3></div>
      <button className="close" onClick={() => goku.setOpen(false)} aria-label="Close">×</button>
    </div>
    <div className="goku-messages" ref={scrollRef}>
      {goku.messages.length === 0 && !goku.sending && <p className="hint goku-empty">
        Ask things like "what's my net worth", "which holding is up the most this month", or "what EMIs are due soon".
        Goku only answers from your own data — it won't place trades or tell you what to buy or sell.
      </p>}
      {goku.messages.map(m => <div key={m.id} className={`goku-msg goku-msg-${m.role}`}>{m.content}</div>)}
      {goku.sending && <div className="goku-msg goku-msg-assistant goku-typing"><span /><span /><span /></div>}
      {goku.error && <div className="goku-msg goku-msg-error">{goku.error}</div>}
    </div>
    <div className="goku-input-row">
      <textarea rows={1} value={goku.draft} placeholder="Ask Goku…" disabled={goku.sending}
        onChange={e => goku.setDraft(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void goku.send() } }} />
      <button type="button" className="primary" onClick={() => void goku.send()} disabled={goku.sending || !goku.draft.trim()}>Send</button>
    </div>
    {goku.remaining != null && <p className="goku-remaining">{goku.remaining} question{goku.remaining === 1 ? '' : 's'} left today</p>}
  </section>
}

// Database-backed allowlist admin panel — every call is re-checked server-side against
// app.goku.admin-allowlist, so hiding this modal is a convenience, not the access control.
export function GokuAdminModal({ goku }: Readonly<{ goku: Goku }>) {
  const [entries, setEntries] = useState<GokuAllowedUser[]>([])
  const [loading, setLoading] = useState(true)
  const [email, setEmail] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  useEscToClose(() => goku.setShowAdmin(false), false)

  useEffect(() => {
    if (!goku.showAdmin) return
    setLoading(true)
    api<GokuAllowedUser[]>('/api/goku/admin/allowlist')
      .then(setEntries)
      .catch(err => setError(err instanceof Error ? err.message : 'Could not load the allowlist'))
      .finally(() => setLoading(false))
  }, [goku.showAdmin])

  if (!goku.showAdmin) return null

  const add = async () => {
    const trimmed = email.trim()
    if (!trimmed || saving) return
    setSaving(true)
    setError('')
    try {
      const entry = await api<GokuAllowedUser>('/api/goku/admin/allowlist', { method: 'POST', body: JSON.stringify({ email: trimmed }) })
      setEntries(list => list.some(e => e.email === entry.email) ? list : [...list, entry])
      setEmail('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add that email')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (target: string) => {
    setError('')
    try {
      await api(`/api/goku/admin/allowlist?email=${encodeURIComponent(target)}`, { method: 'DELETE' })
      setEntries(list => list.filter(e => e.email !== target))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not remove that email')
    }
  }

  return <div className="modal-backdrop"><section className="modal narrow">
    <div className="modal-header">
      <div><p className="eyebrow">GOKU</p><h2>Who can access Goku</h2></div>
      <button className="close" onClick={() => goku.setShowAdmin(false)} aria-label="Close">×</button>
    </div>
    <Field label="Add an email" wide>
      <div className="goku-admin-add">
        <input type="email" value={email} placeholder="name@example.com" disabled={saving}
          onChange={e => setEmail(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void add() } }} />
        <button type="button" className="primary" onClick={() => void add()} disabled={saving || !email.trim()}>Add</button>
      </div>
    </Field>
    {error && <p className="hint goku-admin-error">{error}</p>}
    {loading
      ? <p className="hint">Loading…</p>
      : <ul className="goku-admin-list">
          {entries.map(e => <li key={e.email}>
            <div><strong>{e.email}</strong><small>added {ago(e.addedAt)}{e.addedBy ? ` by ${e.addedBy}` : ''}</small></div>
            <button type="button" className="icon-btn delete" title="Remove access" aria-label={`Remove ${e.email}`} onClick={() => void remove(e.email)}>🗑</button>
          </li>)}
          {entries.length === 0 && <li className="hint">No one is allowed yet.</li>}
        </ul>}
  </section></div>
}
