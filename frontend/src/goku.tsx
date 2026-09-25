import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { useEscToClose } from './ui'
import type { GokuChatReply, GokuConfig, GokuMessage } from './types'

// Goku's state + send logic, lifted out of the panel so its trigger can live in the sidebar
// nav (see App.tsx) while the panel itself renders elsewhere. Conversation lives only in this
// hook's state; Goku keeps no server-side history, so a reload starts fresh, same as the rate
// limit resetting daily.
export function useGoku() {
  const [available, setAvailable] = useState(false)
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<GokuMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [remaining, setRemaining] = useState<number | null>(null)

  useEffect(() => {
    api<GokuConfig>('/api/goku/config').then(cfg => setAvailable(cfg.available)).catch(() => setAvailable(false))
  }, [])

  const send = async () => {
    const text = draft.trim()
    if (!text || sending) return
    const next: GokuMessage[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setDraft('')
    setError('')
    setSending(true)
    try {
      const result = await api<GokuChatReply>('/api/goku/chat', { method: 'POST', body: JSON.stringify({ messages: next }) })
      setMessages(m => [...m, { role: 'assistant', content: result.reply }])
      setRemaining(result.queriesRemainingToday)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Goku is unavailable right now — try again shortly.')
    } finally {
      setSending(false)
    }
  }

  return { available, open, setOpen, messages, draft, setDraft, sending, error, remaining, send }
}

export type Goku = ReturnType<typeof useGoku>

// Sidebar nav entry — same shape as the page links above it (icon + label), so Goku reads as
// part of the app's own navigation rather than a bolted-on widget. Only rendered once `available`
// is true; hidden entirely for anyone off the allowlist.
export function GokuNavButton({ goku }: { goku: Goku }) {
  return <button type="button" className={`goku-nav-btn${goku.open ? ' active' : ''}`}
    onClick={() => goku.setOpen(o => !o)} title="Ask Goku about your portfolio">
    <span className="goku-nav-icon" aria-hidden>⚡</span> Goku
  </button>
}

export function GokuPanel({ goku }: { goku: Goku }) {
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
      {goku.messages.map((m, i) => <div key={i} className={`goku-msg goku-msg-${m.role}`}>{m.content}</div>)}
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
