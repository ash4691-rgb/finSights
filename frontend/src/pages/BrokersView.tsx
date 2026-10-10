import { useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { money, since, ago, label } from '../util'
import type { Brokers } from '../types'

export type BrokerNotice = { broker: string; status: 'connected' | 'failed' }

// Static (not editable-layout) on purpose: automated broker sync itself isn't live yet for most
// sources — see the "Connect a source" panel below — so building out per-widget layout editing
// for this page ahead of the underlying feature isn't worth the complexity. Revisit once sync
// actually ships for more than Kite.
export function BrokersView({ displayCurrency, dataVersion, notice, onDismissNotice }: {
  displayCurrency: string; dataVersion: number; notice?: BrokerNotice | null; onDismissNotice?: () => void
}) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  const [connecting, setConnecting] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [reloadNonce, setReloadNonce] = useState(0)
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
      <p className="hint">Connect Zerodha Kite for automated daily holdings sync, or bulk-load transactions from a broker's CSV/XML statement on the Transactions page for everything else.</p>
      {actionError && <p className="form-error">{actionError}</p>}
      <div className="source-list">{data.sources.map(s => <div className="source" key={s.key}>
        <div><strong>{s.name}</strong><small>{s.description}</small><div className="tag-row">{s.capabilities.map(c => <em key={c}>{c}</em>)}</div></div>
        <div className="source-action">
          <span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span>
          {s.connected && s.lastSyncedAt && <small>Last synced {ago(s.lastSyncedAt)}</small>}
          {s.connectable && !s.connected && <button className="outline" disabled={connecting === s.key} onClick={() => connect(s.key)}>{connecting === s.key ? 'Connecting…' : 'Connect'}</button>}
          {s.connectable && s.connected && s.needsReauth && <button className="outline" disabled={connecting === s.key} onClick={() => connect(s.key)}>{connecting === s.key ? 'Connecting…' : 'Reconnect'}</button>}
          {s.connectable && s.connected && <button className="outline" disabled={connecting === s.key} onClick={() => disconnect(s.key)}>{connecting === s.key ? 'Disconnecting…' : 'Disconnect'}</button>}
          {s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">API docs ↗</a>}
        </div>
      </div>)}</div>
    </section>
  </>
}
