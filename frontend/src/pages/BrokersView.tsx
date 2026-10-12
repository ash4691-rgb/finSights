import { useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { money, since, ago, label } from '../util'
import { Field } from '../ui'
import type { Brokers, Source } from '../types'

export type BrokerNotice = { broker: string; status: 'connected' | 'failed' }

// Static (not editable-layout) on purpose: automated broker sync itself isn't live yet for most
// sources — see the "Connect a source" panel below — so building out per-widget layout editing
// for this page ahead of the underlying feature isn't worth the complexity. Revisit once sync
// actually ships for more brokers.
export function BrokersView({ displayCurrency, dataVersion, notice, onDismissNotice }: {
  displayCurrency: string; dataVersion: number; notice?: BrokerNotice | null; onDismissNotice?: () => void
}) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  const [connecting, setConnecting] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [reloadNonce, setReloadNonce] = useState(0)
  // The paste-your-own-key flow (CoinSwitch, INDmoney) — unlike Kite's redirect, it's a small
  // inline form under the source row rather than a full-page navigation. keyFormFor tracks which
  // source (if any) currently has that form open; its own error is kept separate from
  // actionError so a failed key-form submit doesn't look like it came from Connect/Disconnect.
  const [keyFormFor, setKeyFormFor] = useState<string | null>(null)
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiSecretInput, setApiSecretInput] = useState('')
  const [keyFormError, setKeyFormError] = useState('')
  useEffect(() => {
    api<Brokers>(`/api/brokers?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message))
  }, [displayCurrency, dataVersion, reloadNonce])

  const connect = (key: string) => {
    setActionError(''); setConnecting(key)
    api<{ url: string }>(`/api/brokers/${key}/connect`)
      .then(({ url }) => { window.location.href = url })
      .catch(e => { setActionError(e instanceof ApiError ? e.message : 'Could not start the connection.'); setConnecting(null) })
  }
  const disconnect = (key: string) => {
    setActionError(''); setConnecting(key)
    api<void>(`/api/brokers/${key}/connect`, { method: 'DELETE' })
      .then(() => setReloadNonce(n => n + 1))
      .catch(e => setActionError(e instanceof ApiError ? e.message : 'Could not disconnect.'))
      .finally(() => setConnecting(null))
  }
  const openKeyForm = (key: string) => { setKeyFormError(''); setApiKeyInput(''); setApiSecretInput(''); setKeyFormFor(key) }
  const closeKeyForm = () => { setKeyFormFor(null); setApiKeyInput(''); setApiSecretInput(''); setKeyFormError('') }
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
      {actionError && <p className="form-error">{actionError}</p>}
      <div className="source-list">{data.sources.map(s => <SourceRow key={s.key} source={s}
        connecting={connecting === s.key} onConnect={() => connect(s.key)} onDisconnect={() => disconnect(s.key)}
        keyFormOpen={keyFormFor === s.key} onOpenKeyForm={() => openKeyForm(s.key)} onCloseKeyForm={closeKeyForm}
        apiKeyInput={apiKeyInput} setApiKeyInput={setApiKeyInput} apiSecretInput={apiSecretInput} setApiSecretInput={setApiSecretInput}
        keyFormError={keyFormError} onSubmitKeyForm={needsSecret => submitKeyForm(s.key, needsSecret)} />)}</div>
    </section>
  </>
}

function SourceRow({ source: s, connecting, onConnect, onDisconnect, keyFormOpen, onOpenKeyForm, onCloseKeyForm,
  apiKeyInput, setApiKeyInput, apiSecretInput, setApiSecretInput, keyFormError, onSubmitKeyForm }: {
  source: Source; connecting: boolean; onConnect: () => void; onDisconnect: () => void
  keyFormOpen: boolean; onOpenKeyForm: () => void; onCloseKeyForm: () => void
  apiKeyInput: string; setApiKeyInput: (v: string) => void; apiSecretInput: string; setApiSecretInput: (v: string) => void
  keyFormError: string; onSubmitKeyForm: (needsSecret: boolean) => void
}) {
  const needsSecret = s.connectFlow === 'API_KEY_SECRET'
  const usesKeyForm = s.connectFlow === 'API_KEY_SECRET' || s.connectFlow === 'API_KEY_ONLY'
  return <div className="source">
    <div><strong>{s.name}</strong><small>{s.description}</small><div className="tag-row">{s.capabilities.map(c => <em key={c}>{c}</em>)}</div></div>
    <div className="source-action">
      <span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span>
      {s.connected && s.lastSyncedAt && <small>Last synced {ago(s.lastSyncedAt)}</small>}
      {s.connectable && !s.connected && !usesKeyForm &&
        <button className="outline" disabled={connecting} onClick={onConnect}>{connecting ? 'Connecting…' : 'Connect'}</button>}
      {s.connectable && !s.connected && usesKeyForm && !keyFormOpen &&
        <button className="outline" onClick={onOpenKeyForm}>Connect</button>}
      {s.connectable && s.connected && s.needsReauth &&
        <button className="outline" disabled={connecting} onClick={onConnect}>{connecting ? 'Connecting…' : 'Reconnect'}</button>}
      {s.connectable && s.connected &&
        <button className="outline" disabled={connecting} onClick={onDisconnect}>{connecting ? 'Disconnecting…' : 'Disconnect'}</button>}
      {s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">API docs ↗</a>}
      {keyFormOpen && <form className="broker-key-form" onSubmit={e => { e.preventDefault(); onSubmitKeyForm(needsSecret) }}>
        <Field label={needsSecret ? 'API key' : 'API token'}>
          <input type="text" autoFocus value={apiKeyInput} onChange={e => setApiKeyInput(e.target.value)} />
        </Field>
        {needsSecret && <Field label="API secret">
          <input type="password" value={apiSecretInput} onChange={e => setApiSecretInput(e.target.value)} />
        </Field>}
        {keyFormError && <p className="form-error">{keyFormError}</p>}
        <div className="broker-key-form-actions">
          <button type="button" className="outline" onClick={onCloseKeyForm}>Cancel</button>
          <button type="submit" disabled={connecting}>{connecting ? 'Connecting…' : 'Save'}</button>
        </div>
      </form>}
    </div>
  </div>
}
