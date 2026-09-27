import { useEffect, useState } from 'react'
import { api } from '../api'
import { money, since, label } from '../util'
import type { Brokers } from '../types'

// Static (not editable-layout) on purpose: automated broker sync itself isn't live yet — see the
// "Connect a source" panel below — so building out per-widget layout editing for this page ahead
// of the underlying feature isn't worth the complexity. Revisit once sync actually ships.
export function BrokersView({ displayCurrency, dataVersion }: { displayCurrency: string; dataVersion: number }) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<Brokers>(`/api/brokers?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message)) }, [displayCurrency, dataVersion])
  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading brokers…</p>

  const totalValue = data.brokers.reduce((sum, b) => sum + b.currentValue, 0)

  return <>
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
      <div className="panel-heading"><h3>Connect a source</h3><span>Automated sync — Phase 3</span></div>
      <p className="hint">Automated sync isn't live yet — bulk-load transactions from a broker's CSV or XML statement on the Transactions page.</p>
      <div className="source-list">{data.sources.map(s => <div className="source" key={s.key}>
        <div><strong>{s.name}</strong><small>{s.description}</small><div className="tag-row">{s.capabilities.map(c => <em key={c}>{c}</em>)}</div></div>
        <div className="source-action"><span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span>{s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">API docs ↗</a>}</div>
      </div>)}</div>
    </section>
  </>
}
