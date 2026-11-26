import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import type { Category, Country, Holding, Transaction } from './types'
import type { Goku } from './goku'

// Goku's guided menu: a short decision tree (never more than 5 steps) that turns "what do you want
// to do?" into a precise first message — the bucket, the filter, the exact record ids — so Goku
// starts with the right tools instead of guessing. Every pickable value comes from the signed-in
// user's own data via the same endpoints the pages use, and nothing here writes anything: a change
// request still ends as a proposal the user has to Confirm.

export type GuideEntry = { key: string; label: string }
type Option = GuideEntry & { icon?: string; hint?: string }
type PickSource = 'category' | 'holding' | 'broker' | 'tag' | 'transaction' | 'country' | 'type'

export type GuideStep =
  | { kind: 'choice'; title: string; options: Option[] }
  | { kind: 'pick'; title: string; source: PickSource }
  | { kind: 'period'; title: string }
  | { kind: 'text'; title: string; placeholder: string; optional?: boolean }
  | { kind: 'file'; title: string }
  | { kind: 'send'; content: string; payload: string }
  | { kind: 'freeform' }

const BUCKETS: Option[] = [
  { key: 'summary', label: 'Portfolio summary', icon: '📊', hint: 'Net worth, movers, dues' },
  { key: 'holdings', label: 'Holdings summary', icon: '💼', hint: 'By category, broker, tag' },
  { key: 'transactions', label: 'Transactions summary', icon: '🧾', hint: 'By filter and period' },
  { key: 'change', label: 'Add, edit or delete', icon: '✏️', hint: 'Or import a statement' },
  { key: 'prefs', label: 'Preferences', icon: '⚙️', hint: 'Currency, profile, risk' },
  { key: 'other', label: 'Something else', icon: '💬', hint: 'Ask in your own words' },
]

const TXN_TYPES = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT', 'REPAY']

const lower = (s: string) => s.charAt(0).toLowerCase() + s.slice(1)
const intent = (tag: string, text: string) => `[Intent: ${tag}] ${text}`

function today() { return new Date().toISOString().slice(0, 10) }
function daysAgo(n: number) { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10) }
function financialYearStart() {
  const d = new Date()
  const year = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1
  return `${year}-04-01`
}

const PERIODS: Option[] = [
  { key: '|', label: 'All time' },
  { key: `${daysAgo(30)}|${today()}`, label: 'Last 30 days' },
  { key: `${daysAgo(90)}|${today()}`, label: 'Last 90 days' },
  { key: `${financialYearStart()}|${today()}`, label: 'This financial year' },
]

// The whole tree in one place: given the choices made so far, what's the next step?
export function resolveGuideStep(trail: GuideEntry[]): GuideStep {
  const [bucket, s2, s3, s4, s5] = trail
  if (!bucket) return { kind: 'choice', title: 'What would you like to do?', options: BUCKETS }

  switch (bucket.key) {
    case 'summary': {
      if (!s2) return {
        kind: 'choice', title: 'Which view?', options: [
          { key: 'overview', label: 'Net worth & allocation', icon: '🧭' },
          { key: 'movers', label: 'Biggest movers', icon: '📈' },
          { key: 'emis', label: 'EMIs & upcoming dues', icon: '📅' },
          { key: 'watchlist', label: 'My watchlist', icon: '👀' },
        ],
      }
      return send(`Show my ${lower(s2.label)}.`, intent(`Portfolio summary · ${s2.key}`, `Show my ${lower(s2.label)}.`))
    }

    case 'holdings': {
      if (!s2) return {
        kind: 'choice', title: 'Summarize holdings by…', options: [
          { key: 'all', label: 'Everything', icon: '🌐' },
          { key: 'category', label: 'Category', icon: '🗂️' },
          { key: 'broker', label: 'Broker', icon: '🏦' },
          { key: 'tag', label: 'Tag', icon: '🏷️' },
          { key: 'holding', label: 'One holding', icon: '🔎' },
        ],
      }
      if (s2.key === 'all') return send('Summarize all my holdings.', intent('Holdings summary · all', 'Summarize all my holdings.'))
      if (!s3) return { kind: 'pick', title: `Which ${lower(s2.label)}?`, source: s2.key as PickSource }
      const text = `Summarize my holdings for ${lower(s2.label)} "${s3.label}".`
      return send(text, intent(`Holdings summary · ${filterArg(s2.key)}=${s3.key}`, text))
    }

    case 'transactions': {
      if (!s2) return {
        kind: 'choice', title: 'Transactions for…', options: [
          { key: 'all', label: 'Everything', icon: '🌐' },
          { key: 'category', label: 'Category', icon: '🗂️' },
          { key: 'broker', label: 'Broker', icon: '🏦' },
          { key: 'tag', label: 'Tag', icon: '🏷️' },
          { key: 'holding', label: 'One holding', icon: '🔎' },
          { key: 'type', label: 'Transaction type', icon: '🔁' },
        ],
      }
      const scoped = s2.key !== 'all'
      if (scoped && !s3) return { kind: 'pick', title: `Which ${lower(s2.label)}?`, source: s2.key as PickSource }
      const period = scoped ? s4 : s3
      if (!period) return { kind: 'period', title: 'For which period?' }
      const [from, to] = period.key.split('|')
      const scope = scoped ? ` for ${lower(s2.label)} "${s3.label}"` : ''
      const when = from ? ` from ${from} to ${to}` : ' across all time'
      const text = `Summarize my transactions${scope}${when}.`
      const args = [scoped ? `${filterArg(s2.key)}=${s3.key}` : null, from ? `from=${from}` : null, to ? `to=${to}` : null].filter(Boolean).join(' ')
      return send(text, intent(`Transactions summary${args ? ` · ${args}` : ''}`, text))
    }

    case 'change': {
      if (!s2) return {
        kind: 'choice', title: 'What would you like to do?', options: [
          { key: 'add', label: 'Add something', icon: '➕' },
          { key: 'edit', label: 'Edit something', icon: '✏️' },
          { key: 'delete', label: 'Delete something', icon: '🗑️' },
          { key: 'import', label: 'Import a statement', icon: '📎', hint: 'PDF, XLSX or CSV' },
        ],
      }
      if (s2.key === 'import') return { kind: 'file', title: 'Attach your statement' }
      if (!s3) return {
        kind: 'choice', title: `${s2.label.replace(' something', '')} a…`, options: [
          { key: 'category', label: 'Category', icon: '🗂️' },
          { key: 'holding', label: 'Holding', icon: '💼' },
          { key: 'transaction', label: 'Transaction', icon: '🧾' },
        ],
      }
      if (s2.key === 'add') {
        if (s3.key === 'category') {
          if (!s4) return { kind: 'text', title: 'Describe the category', placeholder: 'e.g. "US Stocks", an asset' }
          return send(`Add a category: ${s4.label}`, intent('Change data · add category', `Add a category: ${s4.label}`))
        }
        const parent: PickSource = s3.key === 'holding' ? 'category' : 'holding'
        if (!s4) return { kind: 'pick', title: s3.key === 'holding' ? 'In which category?' : 'For which holding?', source: parent }
        if (!s5) return {
          kind: 'text', title: s3.key === 'holding' ? 'Describe the holding' : 'Describe the transaction',
          placeholder: s3.key === 'holding' ? 'e.g. 10 INFY on Zerodha, bought at ₹1,450' : 'e.g. bought 5 more on 12 Jan for ₹7,300',
        }
        const text = `Add a ${s3.key} to ${parent} "${s4.label}": ${s5.label}`
        return send(text, intent(`Change data · add ${s3.key} · ${parent}_id=${s4.key}`, text))
      }
      if (!s4) return { kind: 'pick', title: `Which ${s3.key}?`, source: s3.key as PickSource }
      if (s2.key === 'delete') {
        const text = `Delete the ${s3.key} "${s4.label}".`
        return send(text, intent(`Change data · delete ${s3.key} · id=${s4.key}`, text))
      }
      if (!s5) return { kind: 'text', title: 'What should change?', placeholder: 'e.g. rename it, fix the amount to ₹5,200' }
      const text = `Edit the ${s3.key} "${s4.label}": ${s5.label}`
      return send(text, intent(`Change data · edit ${s3.key} · id=${s4.key}`, text))
    }

    case 'prefs': {
      if (!s2) return {
        kind: 'choice', title: 'Which preference?', options: [
          { key: 'currency', label: 'Base currency', icon: '💱', hint: 'Follows your country' },
          { key: 'profile', label: 'Profile details', icon: '🪪', hint: 'Name, phone' },
          { key: 'notifications', label: 'Notifications', icon: '🔔' },
          { key: 'investor', label: 'Investor profile', icon: '🧑‍💼', hint: 'Age, income, tenure' },
          { key: 'risk', label: 'Reassess risk profile', icon: '🎯', hint: '5 quick questions' },
        ],
      }
      if (s2.key === 'currency') {
        if (!s3) return { kind: 'pick', title: 'Which country? Your base currency follows it.', source: 'country' }
        const text = `Change my country to ${s3.label} so my base currency updates.`
        return send(text, intent(`Preferences · country=${s3.key}`, text))
      }
      if (s2.key === 'risk') return send('Reassess my risk profile.', intent('Preferences · risk', 'Reassess my risk profile.'))
      if (!s3) return { kind: 'text', title: 'What would you like to change?', placeholder: 'Leave blank to see your current settings first', optional: true }
      const text = s3.label ? `Update my ${lower(s2.label)}: ${s3.label}` : `Show my ${lower(s2.label)} and help me update it.`
      return send(text, intent(`Preferences · ${s2.key}`, text))
    }

    default:
      return { kind: 'freeform' }
  }
}

function send(content: string, payload: string): GuideStep { return { kind: 'send', content, payload } }

// The get_holdings / get_transactions argument each filter maps onto.
function filterArg(key: string) {
  return { category: 'category_id', holding: 'holding_id', broker: 'broker', tag: 'tag', type: 'type' }[key] ?? key
}

type SourceData = { categories?: Category[]; holdings?: Holding[]; transactions?: Transaction[]; countries?: Country[] }

function optionsFor(source: PickSource, data: SourceData): Option[] | null {
  switch (source) {
    case 'category': return data.categories?.map(c => ({ key: c.id, label: c.name, hint: `${c.holdingCount} holding${c.holdingCount === 1 ? '' : 's'}` })) ?? null
    case 'holding': return data.holdings?.map(h => ({ key: h.id, label: h.name, hint: [h.broker, h.categoryName].filter(Boolean).join(' · ') })) ?? null
    case 'broker': return data.holdings ? [...new Set(data.holdings.map(h => h.broker).filter((b): b is string => !!b))].sort().map(b => ({ key: b, label: b })) : null
    case 'tag': return data.holdings ? [...new Set(data.holdings.flatMap(h => h.tags ?? []))].sort().map(t => ({ key: t, label: t })) : null
    case 'transaction': return data.transactions?.slice(0, 200).map(t => ({
      key: t.id, label: `${t.type} · ${t.holdingName}`, hint: `${t.date} · ${t.amount.toLocaleString()} ${t.currency}`,
    })) ?? null
    case 'country': return data.countries?.map(c => ({ key: c.code, label: c.name, hint: c.currency })) ?? null
    case 'type': return TXN_TYPES.map(t => ({ key: t, label: t.charAt(0) + t.slice(1).toLowerCase() }))
  }
}

export function GokuGuide({ goku, onFreeform }: Readonly<{ goku: Goku; onFreeform: () => void }>) {
  const [trail, setTrail] = useState<GuideEntry[]>([])
  const [data, setData] = useState<SourceData>({})
  const [loadError, setLoadError] = useState('')
  const [query, setQuery] = useState('')
  const [text, setText] = useState('')
  const [custom, setCustom] = useState<{ from: string; to: string } | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const step = resolveGuideStep(trail)
  const pickSource = step.kind === 'pick' ? step.source : null

  // Lazily fetch only what the current pick step needs, once per guide session.
  useEffect(() => {
    if (!pickSource) return
    const need: [keyof SourceData, string][] = []
    if (pickSource === 'category' && !data.categories) need.push(['categories', '/api/categories'])
    if (['holding', 'broker', 'tag'].includes(pickSource) && !data.holdings) need.push(['holdings', '/api/holdings'])
    if (pickSource === 'transaction' && !data.transactions) need.push(['transactions', '/api/transactions'])
    if (pickSource === 'country' && !data.countries) need.push(['countries', '/api/countries'])
    need.forEach(([key, path]) => {
      api<unknown[]>(path).then(rows => setData(d => ({ ...d, [key]: rows })))
        .catch(err => setLoadError(err instanceof Error ? err.message : 'Could not load your data'))
    })
  }, [pickSource, data])

  const clearInputs = () => { setQuery(''); setText(''); setCustom(null); setLoadError('') }

  // Sending happens right here, in the click, so one click is always exactly one message.
  const choose = (entry: GuideEntry) => {
    const nextTrail = [...trail, entry]
    const next = resolveGuideStep(nextTrail)
    clearInputs()
    if (next.kind === 'send') { setTrail([]); void goku.send({ content: next.content, payload: next.payload }) }
    else if (next.kind === 'freeform') { setTrail([]); onFreeform() }
    else setTrail(nextTrail)
  }
  const back = () => { setTrail(t => t.slice(0, -1)); clearInputs() }

  const importFile = async (files: FileList | null) => {
    const file = files?.[0]
    if (fileRef.current) fileRef.current.value = ''
    if (!file) return
    const upload = await goku.attachFile(file)
    if (!upload) return
    const content = `Import my statement: ${upload.filename}`
    setTrail([])
    void goku.send({ content, payload: intent('Change data · import statement', content), attachment: upload })
  }

  if (step.kind === 'send' || step.kind === 'freeform') return null

  return <div className="goku-guide">
    <div className="goku-guide-head">
      {trail.length > 0 && <button type="button" className="goku-guide-back" onClick={back} aria-label="Back">‹</button>}
      <div className="goku-guide-crumbs">
        {trail.length === 0 ? <span>Hi! Pick a topic to get started</span> : trail.map((t, i) => <span key={i}>{t.label}</span>)}
      </div>
      <span className="goku-guide-step">Step {trail.length + 1}</span>
    </div>
    <p className="goku-guide-title">{step.title}</p>

    {step.kind === 'choice' && <div className="goku-guide-options">
      {step.options.map(o => <button key={o.key} type="button" className="goku-guide-option" onClick={() => choose(o)}>
        {o.icon && <span className="goku-guide-icon" aria-hidden>{o.icon}</span>}
        <span><b>{o.label}</b>{o.hint && <small>{o.hint}</small>}</span>
      </button>)}
    </div>}

    {step.kind === 'pick' && (() => {
      const options = optionsFor(step.source, data)
      if (loadError) return <p className="hint goku-admin-error">{loadError}</p>
      if (!options) return <p className="hint">Loading…</p>
      if (options.length === 0) return <p className="hint">Nothing here yet — go back and pick another option.</p>
      const q = query.trim().toLowerCase()
      const shown = options.filter(o => !q || o.label.toLowerCase().includes(q) || o.hint?.toLowerCase().includes(q))
      return <>
        {options.length > 6 && <input className="goku-guide-search" value={query} placeholder="Search…" autoFocus onChange={e => setQuery(e.target.value)} />}
        <div className="goku-guide-list">
          {shown.map(o => <button key={o.key} type="button" onClick={() => choose(o)}>
            <b>{o.label}</b>{o.hint && <small>{o.hint}</small>}
          </button>)}
          {shown.length === 0 && <p className="hint">No match.</p>}
        </div>
      </>
    })()}

    {step.kind === 'period' && <div className="goku-guide-options">
      {PERIODS.map(p => <button key={p.label} type="button" className="goku-guide-option compact" onClick={() => choose(p)}><b>{p.label}</b></button>)}
      {!custom
        ? <button type="button" className="goku-guide-option compact" onClick={() => setCustom({ from: daysAgo(30), to: today() })}><b>Custom range…</b></button>
        : <div className="goku-guide-range">
            <input type="date" value={custom.from} max={custom.to} onChange={e => setCustom({ ...custom, from: e.target.value })} />
            <span>to</span>
            <input type="date" value={custom.to} min={custom.from} max={today()} onChange={e => setCustom({ ...custom, to: e.target.value })} />
            <button type="button" className="primary" disabled={!custom.from || !custom.to || custom.from > custom.to}
              onClick={() => choose({ key: `${custom.from}|${custom.to}`, label: `${custom.from} – ${custom.to}` })}>Go</button>
          </div>}
    </div>}

    {step.kind === 'text' && <form className="goku-guide-text" onSubmit={e => { e.preventDefault(); if (text.trim() || step.optional) choose({ key: 'text', label: text.trim() }) }}>
      <textarea rows={2} value={text} placeholder={step.placeholder} autoFocus maxLength={600} onChange={e => setText(e.target.value)} />
      <button type="submit" className="primary" disabled={!text.trim() && !step.optional}>{text.trim() || !step.optional ? 'Continue' : 'Skip'}</button>
    </form>}

    {step.kind === 'file' && <div className="goku-guide-file">
      <input ref={fileRef} type="file" accept=".pdf,.xlsx,.xls,.csv,.tsv,.xml,.txt" hidden onChange={e => void importFile(e.target.files)} />
      <button type="button" className="primary" disabled={goku.attaching} onClick={() => fileRef.current?.click()}>
        {goku.attaching ? 'Uploading…' : '📎 Choose a file'}
      </button>
      <small>A CAS or a broker's holding/transaction statement. Goku reads it and shows you what it found — nothing is saved until you confirm each change.</small>
    </div>}

    <button type="button" className="goku-guide-freeform" onClick={() => { setTrail([]); onFreeform() }}>Or type your own question ↓</button>
  </div>
}
