import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { useEscToClose } from './ui'
import type { GokuChatReply, GokuConfig, GokuMessage } from './types'

// Floating "Goku" chat bubble, available from any page once /api/goku/config says the signed-in
// user is allowlisted (Phase 1 of the rollout: one account only — see the coordinator plan).
// Conversation lives only in this component's state; Goku itself keeps no server-side history,
// so a reload starts fresh, same as the rate limit resetting daily.
export function GokuWidget() {
  const [available, setAvailable] = useState(false)
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<GokuMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [remaining, setRemaining] = useState<number | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api<GokuConfig>('/api/goku/config').then(cfg => setAvailable(cfg.available)).catch(() => setAvailable(false))
  }, [])

  useEscToClose(() => setOpen(false), false)

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [messages, sending])

  if (!available) return null

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

  return <>
    <button type="button" className="goku-bubble" onClick={() => setOpen(o => !o)} aria-label={open ? 'Close Goku chat' : 'Open Goku chat'} title="Ask Goku about your portfolio">
      {open ? '×' : '💬'}
    </button>
    {open && <section className="goku-panel">
      <div className="goku-panel-header">
        <div><p className="eyebrow">GOKU</p><h3>Ask about your portfolio</h3></div>
        <button className="close" onClick={() => setOpen(false)} aria-label="Close">×</button>
      </div>
      <div className="goku-messages" ref={scrollRef}>
        {messages.length === 0 && !sending && <p className="hint goku-empty">
          Ask things like "what's my net worth", "which holding is up the most this month", or "what EMIs are due soon".
          Goku only answers from your own data — it won't place trades or tell you what to buy or sell.
        </p>}
        {messages.map((m, i) => <div key={i} className={`goku-msg goku-msg-${m.role}`}>{m.content}</div>)}
        {sending && <div className="goku-msg goku-msg-assistant goku-typing"><span /><span /><span /></div>}
        {error && <div className="goku-msg goku-msg-error">{error}</div>}
      </div>
      <div className="goku-input-row">
        <textarea rows={1} value={draft} placeholder="Ask Goku…" disabled={sending}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send() } }} />
        <button type="button" className="primary" onClick={() => void send()} disabled={sending || !draft.trim()}>Send</button>
      </div>
      {remaining != null && <p className="goku-remaining">{remaining} question{remaining === 1 ? '' : 's'} left today</p>}
    </section>}
  </>
}
