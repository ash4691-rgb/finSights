import { useEffect, useState } from 'react'
import { api } from '../api'
import { LayoutZone } from '../layout'
import { money, since, label } from '../util'
import type { PageDataSource } from '../widgets'
import type { Brokers, BrokerGroup } from '../types'

export function BrokersView({ displayCurrency, dataVersion, layoutEditing, layoutNonce }: {
  displayCurrency: string; dataVersion: number; layoutEditing: boolean; layoutNonce: number
}) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<Brokers>(`/api/brokers?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message)) }, [displayCurrency, dataVersion])
  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading brokers…</p>
  const zone = { editing: layoutEditing, nonce: layoutNonce }

  const brokersDataSource: PageDataSource = {
    attributes: [
      { key: 'broker', label: 'Broker', kind: 'dimension' },
      { key: 'currentValue', label: 'Current value', kind: 'measure' },
      { key: 'investedValue', label: 'Invested', kind: 'measure' },
      { key: 'profitLoss', label: 'P/L', kind: 'measure' },
      { key: 'holdingCount', label: 'Holdings', kind: 'measure' },
    ],
    resolve(w) {
      if (w.subType === '2d-graph') return { kind: 'series', series: [] }
      const m = w.query.measure ?? 'currentValue'
      const val = (b: BrokerGroup) => Number((b as unknown as Record<string, number>)[m] ?? 0)
      if (w.subType === 'counter') return { kind: 'scalar', value: data.brokers.reduce((sum, b) => sum + val(b), 0) }
      return { kind: 'breakdown', rows: data.brokers.map(b => ({ label: b.name, value: val(b) })) }
    },
  }
  const brokerCards = Object.fromEntries(data.brokers.map(b => [b.name, <article className="panel broker-card" key={b.name}>
    <div className="panel-heading"><h3>{b.name}</h3><span>{b.holdingCount} holding{b.holdingCount === 1 ? '' : 's'}</span></div>
    <strong className="broker-value">{money(b.currentValue)}</strong>
    <div className="broker-meta"><span>Invested {money(b.investedValue)}</span><span className={b.profitLoss >= 0 ? 'positive' : 'negative'}>{b.profitLoss >= 0 ? '+' : ''}{money(b.profitLoss)}</span></div>
    <div className="tag-row">{b.categories.map(c => <em key={c}>{c}</em>)}</div>
    <p className="hint">Last change {since(b.lastUpdated)}{b.currencies.length > 1 ? ` · ${b.currencies.join(', ')}` : ''}</p>
  </article>]))
  return <LayoutZone zoneKey="brokers/page" {...zone} defaults={[{ key: 'widgets', span: 12 }, { key: 'brokerGrid', span: 12 }, { key: 'connect', span: 12 }]} render={{
    widgets: <LayoutZone zoneKey="brokers/widgets" {...zone} dataSource={brokersDataSource} />,
    brokerGrid: data.brokers.length
      ? <LayoutZone zoneKey="brokers/grid" {...zone} defaults={data.brokers.map(b => ({ key: b.name, span: 4 }))} render={brokerCards} />
      : <p className="hint">Assign holdings to a broker to see them grouped here.</p>,
    connect: <section className="panel">
      <div className="panel-heading"><h3>Connect a source</h3><span>Automated sync — Phase 3</span></div>
      <p className="hint">Automated sync isn't live yet — bulk-load transactions from a broker's CSV or XML statement on the Transactions page.</p>
      <div className="source-list">{data.sources.map(s => <div className="source" key={s.key}>
        <div><strong>{s.name}</strong><small>{s.description}</small><div className="tag-row">{s.capabilities.map(c => <em key={c}>{c}</em>)}</div></div>
        <div className="source-action"><span className={`status ${s.status.toLowerCase()}`}>{label(s.status)}</span>{s.docsUrl && <a href={s.docsUrl} target="_blank" rel="noreferrer">API docs ↗</a>}</div>
      </div>)}</div>
    </section>,
  }} />
}
