import { useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { money, since, ago, label } from '../util'
import { Field } from '../ui'
import type { Brokers, Source } from '../types'

export type BrokerNotice = { broker: string; status: 'connected' | 'failed' }

type SyncNowResult = { synced: boolean; lastSyncedAt?: string; lastSyncError?: string }

// First letter of each of the first two words, or the first two letters of a one-word name —
// the fallback for a broker logo that fails to load (or one not in BROKER_DOMAINS at all).
const initials = (name: string) => {
  const words = name.trim().split(/\s+/)
  return (words.length > 1 ? words[0][0] + words[1][0] : name.slice(0, 2)).toUpperCase()
}

// Each broker's own domain, used only to fetch its favicon — no logo assets to source/bundle/
// keep in sync ourselves. Google's favicon service degrades predictably (a generic globe icon)
// rather than erroring outright, but BrokerIcon still falls back to initials on any load failure.
const BROKER_DOMAINS: Record<string, string> = {
  kite: 'zerodha.com', coinswitch: 'coinswitch.co', indmoney: 'indmoney.com', groww: 'groww.in', epfo: 'epfindia.gov.in',
}

function BrokerIcon({ source }: { source: Source }) {
  const [failed, setFailed] = useState(false)
  const domain = BROKER_DOMAINS[source.key]
  if (!domain || failed) return <span className="broker-tile-icon">{initials(source.name)}</span>
  return <img className="broker-tile-icon broker-tile-logo" alt=""
    src={`https://www.google.com/s2/favicons?domain=${domain}&sz=64`} onError={() => setFailed(true)} />
}

const connectSteps = (s: Source): string[] => {
  if (s.connectFlow === 'REDIRECT') {
    return [`Click Connect below.`, `Log in to your ${s.name} account when redirected.`, `You'll land back here once it's linked.`]
  }
  if (s.connectFlow === 'UAN_PASSWORD') {
    return [`Click Connect below and enter your UAN and your EPFO member-portal password — the same ones you'd use to log in yourself.`,
      `FinSights attempts to verify it works before saving it. EPFO has no public API, and its login page may require solving a CAPTCHA this can't do — if verification fails, that's the most likely reason, not necessarily wrong credentials.`]
  }
  const credential = s.connectFlow === 'API_KEY_SECRET' ? 'an API key and secret' : 'an API token'
  return [`Open ${s.name}'s API page (link below) and generate ${credential} from your own account — no developer account needed.`,
    `Click Connect below and paste it in.`, `FinSights verifies it works before saving it.`]
}

// Static (not editable-layout) on purpose: automated broker sync itself isn't live yet for most
// sources — see the "Connect a source" panel below — so building out per-widget layout editing
// for this page ahead of the underlying feature isn't worth the complexity. Revisit once sync
// actually ships for more brokers.
export function BrokersView({ displayCurrency, dataVersion, notice, onDismissNotice }: {
  displayCurrency: string; dataVersion: number; notice?: BrokerNotice | null; onDismissNotice?: () => void
}) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  // Which source's tile is currently expanded into its full detail panel — at most one at a time.
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const [connecting, setConnecting] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [reloadNonce, setReloadNonce] = useState(0)
  // The paste-your-own-key flow (CoinSwitch, INDmoney) — a small inline form inside the expanded
  // detail panel rather than a full-page navigation. keyFormOpenFor tracks which source (if any)
  // currently has it open.
  const [keyFormOpenFor, setKeyFormOpenFor] = useState<string | null>(null)
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiSecretInput, setApiSecretInput] = useState('')
  const [keyFormError, setKeyFormError] = useState('')
  // Feedback from the last "Sync now" click — ephemeral, cleared whenever a tile is
  // collapsed/expanded so it never looks like it belongs to a different source.
  const [syncResult, setSyncResult] = useState<{ key: string; message: string } | null>(null)

  useEffect(() => {
    api<Brokers>(`/api/brokers?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message))
  }, [displayCurrency, dataVersion, reloadNonce])

  const toggleExpanded = (key: string) => {
    setActionError(''); setKeyFormOpenFor(null); setKeyFormError(''); setSyncResult(null)
    setExpandedKey(current => current === key ? null : key)
  }
  const connect = (key: string) => {
    setActionError(''); setConnecting(key)
    api<{ url: string }>(`/api/brokers/${key}/connect`)
      .then(({ url }) => { window.location.href = url })
      .catch(e => { setActionError(e instanceof ApiError ? e.message : 'Could not start the connection.'); setConnecting(null) })
  }
  const disconnect = (key: string) => {
    setActionError(''); setConnecting(key)
    api<void>(`/api/brokers/${key}/connect`, { method: 'DELETE' })
      .then(() => { setExpandedKey(null); setReloadNonce(n => n + 1) })
      .catch(e => setActionError(e instanceof ApiError ? e.message : 'Could not disconnect.'))
      .finally(() => setConnecting(null))
  }
  const syncNow = (key: string) => {
    setActionError(''); setSyncResult(null); setConnecting(key)
    api<SyncNowResult>(`/api/brokers/${key}/sync-now`, { method: 'POST' })
      .then(result => {
        setSyncResult({ key, message: result.synced ? 'Synced just now.' : (result.lastSyncError ?? "Couldn't reach it this time — try again shortly.") })
        setReloadNonce(n => n + 1)
      })
      .catch(e => setActionError(e instanceof ApiError ? e.message : 'Could not sync.'))
      .finally(() => setConnecting(null))
  }
  const openKeyForm = (key: string) => { setKeyFormError(''); setApiKeyInput(''); setApiSecretInput(''); setKeyFormOpenFor(key) }
  const closeKeyForm = () => { setKeyFormOpenFor(null); setApiKeyInput(''); setApiSecretInput(''); setKeyFormError('') }
  const submitKeyForm = (key: string, needsSecret: boolean) => {
    if (!apiKeyInput.trim() || (needsSecret && !apiSecretInput.trim())) return
    setKeyFormError(''); setConnecting(key)
    api<void>(`/api/brokers/${key}/connect-with-key`, {
      method: 'POST',
      body: JSON.stringify({ apiKey: apiKeyInput.trim(), apiSecret: needsSecret ? apiSecretInput.trim() : undefined }),
    })
      .then(() => { closeKeyForm(); setReloadNonce(n => n + 1) })
      .catch(e => setKeyFormError(e instanceof ApiError ? e.message : 'Could not connect.'))
      .finally(() => setConnecting(null))
  }

  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading brokers…</p>

  const totalValue = data.brokers.reduce((sum, b) => sum + b.currentValue, 0)
  const query = search.trim().toLowerCase()
  // Array.prototype.sort is stable, so ties (connected-vs-connected, not-vs-not) keep their
  // original SOURCES order — this only ever pulls connected tiles to the front.
  const sources = data.sources
    .filter(s => !query || s.name.toLowerCase().includes(query))
    .slice()
    .sort((a, b) => Number(b.connected) - Number(a.connected))

  return <>
    {notice && <div className={`broker-notice ${notice.status}`}>
      <span>{notice.status === 'connected' ? `Connected to ${label(notice.broker)}. Holdings will sync automatically going forward.` : `Couldn't connect to ${label(notice.broker)}. Please try again.`}</span>
      {onDismissNotice && <button className="link-button" onClick={onDismissNotice}>Dismiss</button>}
    </div>}
    <section className="panel">
      <div className="panel-heading"><h3>Overview</h3></div>
      <div className="broker-meta"><span>Total portfolio value</span><strong>{money(totalValue)}</strong></div>
    </section>
    {data.brokers.length
      ? <div className="broker-grid">
          {data.brokers.map(b => <article className="panel broker-card" key={b.name}>
            <div className="panel-heading"><h3>{b.name}</h3><span>{b.holdingCount} holding{b.holdingCount === 1 ? '' : 's'}</span></div>
            <strong className="broker-value">{money(b.currentValue)}</strong>
            <div className="broker-meta"><span>Invested {money(b.investedValue)}</span><span className={b.profitLoss >= 0 ? 'positive' : 'negative'}>{b.profitLoss >= 0 ? '+' : ''}{money(b.profitLoss)}</span></div>
            <div className="tag-row">{b.categories.map(c => <em key={c}>{c}</em>)}</div>
            <p className="hint">Last change {since(b.lastUpdated)}{b.currencies.length > 1 ? ` · ${b.currencies.join(', ')}` : ''}</p>
          </article>)}
        </div>
      : <p className="hint">Assign holdings to a broker to see them grouped here.</p>}
    <section className="panel">
      <div className="panel-heading"><h3>Connect a source</h3><span>Automated sync</span></div>
      <p className="hint">Connect Zerodha Kite, CoinSwitch, or INDmoney for automated daily holdings sync, or bulk-load transactions from a broker's CSV/XML statement on the Transactions page for everything else.</p>
      <input className="broker-search" type="search" placeholder="Search brokers…" value={search} onChange={e => setSearch(e.target.value)} />
      {actionError && <p className="form-error">{actionError}</p>}
      {sources.length === 0 && <p className="hint">No sources match "{search}".</p>}
      <div className="broker-tile-grid">
        {sources.map(s => expandedKey === s.key
          ? <SourceDetail key={s.key} source={s} onCollapse={() => toggleExpanded(s.key)}
              connecting={connecting === s.key} onConnect={() => connect(s.key)} onDisconnect={() => disconnect(s.key)} onSyncNow={() => syncNow(s.key)}
              syncMessage={syncResult?.key === s.key ? syncResult.message : null}
              keyFormOpen={keyFormOpenFor === s.key} onOpenKeyForm={() => openKeyForm(s.key)} onCloseKeyForm={closeKeyForm}
              apiKeyInput={apiKeyInput} setApiKeyInput={setApiKeyInput} apiSecretInput={apiSecretInput} setApiSecretInput={setApiSecretInput}
              keyFormError={keyFormError} onSubmitKeyForm={needsSecret => submitKeyForm(s.key, needsSecret)} />
          : <SourceTile key={s.key} source={s} onClick={() => toggleExpanded(s.key)} />)}
      </div>
    </section>
  </>
}

function SourceTile({ source: s, onClick }: { source: Source; onClick: () => void }) {
  return <button className={`broker-tile ${s.needsReauth ? 'needs-reauth' : s.connected ? 'connected' : ''}`} onClick={onClick}>
    <BrokerIcon source={s} />
    <strong>{s.name}</strong>
    <span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span>
  </button>
}

function SourceDetail({ source: s, onCollapse, connecting, onConnect, onDisconnect, onSyncNow, syncMessage,
  keyFormOpen, onOpenKeyForm, onCloseKeyForm, apiKeyInput, setApiKeyInput, apiSecretInput, setApiSecretInput,
  keyFormError, onSubmitKeyForm }: {
  source: Source; onCollapse: () => void
  connecting: boolean; onConnect: () => void; onDisconnect: () => void; onSyncNow: () => void; syncMessage: string | null
  keyFormOpen: boolean; onOpenKeyForm: () => void; onCloseKeyForm: () => void
  apiKeyInput: string; setApiKeyInput: (v: string) => void; apiSecretInput: string; setApiSecretInput: (v: string) => void
  keyFormError: string; onSubmitKeyForm: (needsSecret: boolean) => void
}) {
  const isUanPassword = s.connectFlow === 'UAN_PASSWORD'
  const needsSecret = s.connectFlow === 'API_KEY_SECRET' || isUanPassword
  const usesKeyForm = needsSecret || s.connectFlow === 'API_KEY_ONLY'
  const firstFieldLabel = isUanPassword ? 'UAN' : needsSecret ? 'API key' : 'API token'
  const secondFieldLabel = isUanPassword ? 'Password' : 'API secret'
  return <div className={`broker-detail ${s.needsReauth ? 'needs-reauth' : s.connected ? 'connected' : ''}`}>
    <div className="broker-detail-heading">
      <BrokerIcon source={s} />
      <div><strong>{s.name}</strong><span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span></div>
      <button className="link-button" onClick={onCollapse}>Collapse ▴</button>
    </div>
    <p className="hint">{s.description}</p>
    <div className="tag-row">{s.capabilities.map(c => <em key={c}>{c}</em>)}</div>

    {!s.connectable && <p className="hint">Not connectable yet — bulk-load from a statement on the Transactions page instead.</p>}

    {s.connectable && !s.connected && <>
      <ol className="broker-steps">{connectSteps(s).map((step, i) => <li key={i}>{step}</li>)}</ol>
      <div className="button-row">
        {!usesKeyForm &&
          <button className="outline" disabled={connecting} onClick={onConnect}>{connecting ? 'Connecting…' : 'Connect'}</button>}
        {usesKeyForm && !keyFormOpen &&
          <button className="outline" onClick={onOpenKeyForm}>Connect</button>}
        {s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">{isUanPassword ? `Open ${s.name}'s member portal` : `Open ${s.name}'s API page`} ↗</a>}
      </div>
      {usesKeyForm && keyFormOpen && <form className="broker-key-form" onSubmit={e => { e.preventDefault(); onSubmitKeyForm(needsSecret) }}>
        <Field label={firstFieldLabel}>
          <input type="text" autoFocus value={apiKeyInput} onChange={e => setApiKeyInput(e.target.value)} />
        </Field>
        {needsSecret && <Field label={secondFieldLabel}>
          <input type="password" value={apiSecretInput} onChange={e => setApiSecretInput(e.target.value)} />
        </Field>}
        {keyFormError && <p className="form-error">{keyFormError}</p>}
        <div className="broker-key-form-actions">
          <button type="submit" disabled={connecting}>{connecting ? 'Connecting…' : 'Save'}</button>
          <button type="button" className="outline" onClick={onCloseKeyForm}>Cancel</button>
        </div>
      </form>}
    </>}

    {s.connectable && s.connected && <div className="broker-detail-actions">
      {s.lastSyncedAt && <small>Last synced {ago(s.lastSyncedAt)}</small>}
      {syncMessage && <p className="hint">{syncMessage}</p>}
      <div className="button-row">
        {s.needsReauth &&
          <button className="outline" disabled={connecting} onClick={onConnect}>{connecting ? 'Connecting…' : 'Reconnect'}</button>}
        <button className="outline" disabled={connecting} onClick={onSyncNow}>{connecting ? 'Syncing…' : 'Sync now'}</button>
        <button className="outline" disabled={connecting} onClick={onDisconnect}>{connecting ? 'Disconnecting…' : 'Disconnect'}</button>
        {s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">{isUanPassword ? 'Member portal' : 'API docs'} ↗</a>}
      </div>
    </div>}
  </div>
}
