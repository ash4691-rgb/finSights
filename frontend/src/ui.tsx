import { useEffect, useRef, useState } from 'react'
import type * as React from 'react'
import { api } from './api'
import type { SymbolSuggestion } from './types'

export function Switch({ checked, onChange, text, icon, disabled, title }: { checked: boolean; onChange?: () => void; text: string; icon?: string; disabled?: boolean; title?: string }) {
  return <label className={`switch${disabled ? ' disabled' : ''}`} title={title}>
    <input type="checkbox" checked={checked} disabled={disabled} onChange={onChange} />
    <span className="switch-track"><span className="switch-thumb" /></span>
    <span className="switch-text">{icon && <span className="icon">{icon}</span>}{text}</span>
  </label>
}

export function Field({ label: title, children, required, wide }: { label: React.ReactNode; children: React.ReactNode; required?: boolean; wide?: boolean }) { return <label className={wide ? 'field wide' : 'field'}><span>{title}{required && <b> *</b>}</span>{children}</label> }

// A small ⓘ dot that shows `text` in a floating tooltip on hover/focus. Positioned with
// position:fixed off the icon's rect so it never gets clipped by a table's overflow.
export function InfoTip({ text }: { text: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [tip, setTip] = useState<{ left: number; top: number; above: boolean } | null>(null)
  const show = () => {
    const r = ref.current?.getBoundingClientRect()
    if (!r) return
    const vw = window.innerWidth || document.documentElement.clientWidth || 1024
    const vh = window.innerHeight || document.documentElement.clientHeight || 768
    const left = Math.min(Math.max(r.left + r.width / 2, 150), Math.max(vw - 150, 150))
    const above = r.bottom > vh - 130
    setTip({ left, top: above ? r.top - 8 : r.bottom + 8, above })
  }
  return <span ref={ref} className="info-dot" tabIndex={0}
    onMouseEnter={show} onMouseLeave={() => setTip(null)} onFocus={show} onBlur={() => setTip(null)}
    onClick={e => e.stopPropagation()} aria-label={`Description: ${text}`}>i
    {tip && <span className={`info-tip${tip.above ? ' above' : ''}`} style={{ left: tip.left, top: tip.top }}>{text}</span>}
  </span>
}

// A LinkedIn-style tag editor: selected tags as removable chips, a text field
// that filters `suggestions` into a dropdown as you type (with a "create"
// row), and a row of not-yet-picked popular suggestions underneath.
export function TagInput({ tags, suggestions, onChange }: { tags: string[]; suggestions: string[]; onChange: (next: string[]) => void }) {
  const [input, setInput] = useState('')
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const MAX_TAGS = 30
  const full = tags.length >= MAX_TAGS
  const norm = (t: string) => t.trim().toLowerCase().slice(0, 48)
  const has = (t: string) => tags.some(x => norm(x) === norm(t))
  const add = (raw: string) => { const t = norm(raw); if (t && !has(t) && !full) onChange([...tags, t]); setInput(''); setOpen(false) }
  const remove = (t: string) => onChange(tags.filter(x => x !== t))

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const typed = norm(input)
  const matches = suggestions.filter(s => !has(s) && (!typed || s.includes(typed))).slice(0, 8)
  const showCreate = !!typed && !suggestions.some(s => s === typed) && !has(typed)
  const popular = suggestions.filter(s => !has(s)).slice(0, 8)

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <div className="tag-input-box" onClick={() => setOpen(true)}>
        {tags.map(t => <span className="tag-chip" key={t} title={t}><span className="tag-chip-label">{t}</span><button type="button" aria-label={`Remove ${t}`} onClick={e => { e.stopPropagation(); remove(t) }}>×</button></span>)}
        {!full && <input value={input} maxLength={48} placeholder={tags.length ? 'Add another…' : 'Search or add a tag'}
          onFocus={() => setOpen(true)}
          onChange={e => { setInput(e.target.value); setOpen(true) }}
          onKeyDown={e => {
            if (e.key === 'Enter' && typed) { e.preventDefault(); add(input) }
            else if (e.key === 'Backspace' && !input && tags.length) remove(tags[tags.length - 1])
            else if (e.key === 'Escape') setOpen(false)
          }} />}
      </div>
      {open && !full && (matches.length > 0 || showCreate) && <ul className="tag-menu">
        {matches.map(s => <li key={s}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => add(s)}>{s}</button></li>)}
        {showCreate && <li><button type="button" className="tag-menu-create" onMouseDown={e => e.preventDefault()} onClick={() => add(input)}>Add “{input.trim().toLowerCase()}”</button></li>}
      </ul>}
    </div>
    {full
      ? <p className="tag-limit">30-tag limit reached — remove one to add another.</p>
      : popular.length > 0 && <div className="tag-ideas">
          <span>Popular</span>
          {popular.map(t => <button type="button" key={t} className="tag-idea" onClick={() => add(t)}>+ {t}</button>)}
        </div>}
  </div>
}

// Free-text type-ahead over a fixed list of known values (e.g. brokers already
// in use). Filters `suggestions` as you type; picking one fills the field, or
// keep typing to use a new value that isn't in the list yet.
export function SuggestInput({ value, suggestions, onChange, placeholder, required, maxLength }: {
  value: string; suggestions: string[]; onChange: (value: string) => void; placeholder?: string; required?: boolean; maxLength?: number
}) {
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const typed = value.trim().toLowerCase()
  const matches = suggestions.filter(s => s.toLowerCase() !== typed && (!typed || s.toLowerCase().includes(typed))).slice(0, 8)
  const showCreate = !!typed && !suggestions.some(s => s.toLowerCase() === typed)
  const pick = (s: string) => { onChange(s); setOpen(false) }

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <input required={required} maxLength={maxLength} value={value} placeholder={placeholder}
        onFocus={() => setOpen(true)}
        onChange={e => { onChange(e.target.value); setOpen(true) }}
        onKeyDown={e => { if (e.key === 'Escape') setOpen(false) }} />
      {open && (matches.length > 0 || showCreate) && <ul className="tag-menu">
        {matches.map(s => <li key={s}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => pick(s)}>{s}</button></li>)}
        {showCreate && <li><button type="button" className="tag-menu-create" onMouseDown={e => e.preventDefault()} onClick={() => setOpen(false)}>Use “{value.trim()}”</button></li>}
      </ul>}
    </div>
  </div>
}

// Ticker type-ahead backed by /api/market/search (Yahoo Finance). Free text is
// still accepted; picking a row commits the exchange symbol (e.g. RELIANCE.NS).
export function SymbolSearchInput({ value, onChange }: { value: string; onChange: (symbol: string) => void }) {
  const [query, setQuery] = useState(value)
  const [results, setResults] = useState<SymbolSuggestion[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const justPicked = useRef(false)

  useEffect(() => {
    if (justPicked.current) { justPicked.current = false; return }
    const q = query.trim()
    if (q.length < 2) { setResults([]); setLoading(false); return }
    setLoading(true)
    const timer = setTimeout(() => {
      api<SymbolSuggestion[]>(`/api/market/search?q=${encodeURIComponent(q)}`)
        .then(setResults).catch(() => setResults([])).finally(() => setLoading(false))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const pick = (s: SymbolSuggestion) => { justPicked.current = true; setQuery(s.symbol); onChange(s.symbol); setResults([]); setOpen(false) }

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <input value={query} placeholder="Search RELIANCE, INFY, BTC-USD…"
        onFocus={() => setOpen(true)}
        onChange={e => { const v = e.target.value.toUpperCase(); setQuery(v); onChange(v); setOpen(true) }} />
      {open && (loading || results.length > 0) && <ul className="tag-menu">
        {loading && results.length === 0 && <li className="tag-menu-note">Searching…</li>}
        {results.map(s => <li key={s.symbol}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => pick(s)}>
          <b>{s.symbol}</b> <span>{s.name}{s.exchange ? ` · ${s.exchange}` : ''}</span>
        </button></li>)}
      </ul>}
    </div>
  </div>
}
