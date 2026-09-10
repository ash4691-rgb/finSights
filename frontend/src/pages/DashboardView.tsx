import { LayoutZone } from '../layout'
import { money, percent, label } from '../util'
import type { PageDataSource } from '../widgets'
import type { Dashboard, Holding, Breakdown } from '../types'

export function DashboardView({ dashboard, holdings, onManage, layoutEditing, layoutNonce }: {
  dashboard: Dashboard; holdings: Holding[]; onManage: () => void; layoutEditing: boolean; layoutNonce: number
}) {
  const kpi = (key: string, title: string, value: number, note: string) => [key, <article className="metric-card" key={key}>
    <p>{title}</p><strong className={key === 'pl' && value < 0 ? 'negative' : ''}>{money(value)}</strong><small>{note}</small>
  </article>] as const
  const kpis = Object.fromEntries([
    kpi('netWorth', 'Net worth', dashboard.netWorth, 'Assets less liabilities'),
    kpi('assets', 'Total assets', dashboard.totalAssets, `${holdings.filter(h => h.kind === 'ASSET').length} active holdings`),
    kpi('liabilities', 'Total liabilities', dashboard.totalLiabilities, 'Outstanding obligations'),
    kpi('pl', 'Portfolio P/L', dashboard.portfolioProfitLoss, `${dashboard.investedAssets ? percent((dashboard.portfolioProfitLoss / dashboard.investedAssets) * 100) : '0.0%'} on invested assets`),
  ])

  const dashboardDataSource: PageDataSource = {
    attributes: [
      { key: 'category', label: 'Category', kind: 'dimension' },
      { key: 'broker', label: 'Broker', kind: 'dimension' },
      { key: 'tag', label: 'Tag', kind: 'dimension' },
      { key: 'value', label: 'Current value', kind: 'measure' },
      { key: 'invested', label: 'Invested', kind: 'measure' },
      { key: 'profitLoss', label: 'P/L', kind: 'measure' },
      { key: 'netWorth', label: 'Net worth', kind: 'measure' },
      { key: 'totalAssets', label: 'Total assets', kind: 'measure' },
      { key: 'totalLiabilities', label: 'Total liabilities', kind: 'measure' },
      { key: 'totalProfitLoss', label: 'Portfolio P/L', kind: 'measure' },
    ],
    resolve(w) {
      if (w.subType === 'counter') {
        const totals: Record<string, number> = {
          netWorth: dashboard.netWorth, totalAssets: dashboard.totalAssets,
          totalLiabilities: dashboard.totalLiabilities, totalProfitLoss: dashboard.portfolioProfitLoss,
        }
        return { kind: 'scalar', value: totals[w.query.measure ?? 'netWorth'] ?? dashboard.netWorth }
      }
      const byDimension: Record<string, Breakdown[]> = { category: dashboard.byCategory, broker: dashboard.byBroker, tag: dashboard.byTag }
      const rows = byDimension[w.query.dimension ?? 'category'] ?? dashboard.byCategory
      const m = w.query.measure ?? 'value'
      const val = (b: Breakdown) => m === 'invested' ? b.investedValue : m === 'profitLoss' ? b.profitLoss : b.value
      return { kind: 'breakdown', rows: rows.map(r => ({ label: label(r.label), value: val(r) })) }
    },
  }

  const zone = { editing: layoutEditing, nonce: layoutNonce }
  return <LayoutZone zoneKey="dashboard/page" {...zone} defaults={[{ key: 'hero', span: 12 }, { key: 'widgets', span: 12 }, { key: 'kpis', span: 12 }, { key: 'breakdowns', span: 12 }]} render={{
    hero: <section className="hero"><div><p>Current portfolio value</p><h2>{money(dashboard.netWorth)}</h2><span className={dashboard.portfolioProfitLoss >= 0 ? 'positive' : 'negative'}>{dashboard.portfolioProfitLoss >= 0 ? '↑' : '↓'} {money(Math.abs(dashboard.portfolioProfitLoss))} total gain/loss</span></div><button className="outline" onClick={onManage}>Manage categories →</button></section>,
    widgets: <LayoutZone zoneKey="dashboard/widgets" {...zone} dataSource={dashboardDataSource} />,
    kpis: <LayoutZone zoneKey="dashboard/kpis" {...zone}
      defaults={['netWorth', 'assets', 'liabilities', 'pl'].map(key => ({ key, span: 3 }))} render={kpis} />,
    breakdowns: <LayoutZone zoneKey="dashboard/breakdowns" {...zone}
      defaults={['byCategory', 'byBroker', 'pulse', 'byTag'].map(key => ({ key, span: 6 }))} render={{
        byCategory: <BreakdownCard title="Allocation by category" items={dashboard.byCategory} total={dashboard.totalAssets} />,
        byBroker: <BreakdownCard title="Value by broker" items={dashboard.byBroker} total={dashboard.totalAssets} />,
        pulse: <article className="panel recent"><div className="panel-heading"><h3>Portfolio pulse</h3><span>Live calculation</span></div><div className="pulse-row"><span>Liquid within 7 days</span><strong>{money(holdings.filter(h => h.liquidWithinSevenDays).reduce((sum, h) => sum + h.currentValue, 0))}</strong></div><div className="pulse-row"><span>Blocked / NPA</span><strong>{money(holdings.filter(h => h.blocked).reduce((sum, h) => sum + h.currentValue, 0))}</strong></div><div className="pulse-row"><span>Fixed-rate instruments</span><strong>{holdings.filter(h => h.valuationMethod === 'FIXED_RATE').length}</strong></div><p className="hint">Daily portfolio snapshots and broker reconciliation are the next integration layer.</p></article>,
        byTag: <BreakdownCard title="Value by tag" items={dashboard.byTag} total={dashboard.totalAssets} />,
      }} />,
  }} />
}

export function BreakdownCard({ title, items, total }: { title: string; items: Breakdown[]; total: number }) {
  return <article className="panel"><div className="panel-heading"><h3>{title}</h3><span>{items.length} group{items.length === 1 ? '' : 's'}</span></div><div className="breakdown-list">{items.slice(0, 6).map(item => <div className="breakdown" key={item.label}><div className="breakdown-copy"><div><span title={label(item.label)}>{label(item.label)}</span><strong>{money(item.value)}</strong></div><div className="bar"><i style={{ width: `${Math.min(100, total ? (item.value / total) * 100 : 0)}%` }} /></div></div><b className={item.profitLoss >= 0 ? 'positive' : 'negative'}>{item.profitLoss >= 0 ? '+' : ''}{money(item.profitLoss)}</b></div>)}{!items.length && <p className="hint">Nothing to group yet.</p>}</div></article>
}
