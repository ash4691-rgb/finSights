import { useEffect, useState } from 'react'
import { api } from '../api'
import { LayoutZone } from '../layout'
import { NetWorthChart, TimelineTable } from '../charts'
import { money, percent, label, ago } from '../util'
import { InfoTip } from '../ui'
import { seriesKeysOf } from '../widgets'
import type { PageDataSource } from '../widgets'
import type { Dashboard, Holding, Breakdown, PortfolioTimeline, TimelineWeek } from '../types'

export function DashboardView({ dashboard, holdings, onManage, displayCurrency, dataVersion, layoutEditing, layoutNonce }: {
  dashboard: Dashboard; holdings: Holding[]; onManage: () => void; displayCurrency: string; dataVersion: number
  layoutEditing: boolean; layoutNonce: number
}) {
  const [timeline, setTimeline] = useState<PortfolioTimeline | null>(null)
  const [capturing, setCapturing] = useState(false)
  const [timelineError, setTimelineError] = useState('')
  const loadTimeline = () => api<PortfolioTimeline>(`/api/insights/timeline?currency=${displayCurrency}`).then(setTimeline).catch(() => setTimeline({ weeks: [], capturedToday: false }))
  useEffect(() => { void loadTimeline() }, [displayCurrency, dataVersion])
  const captureNow = async () => {
    setCapturing(true)
    try { setTimeline(await api<PortfolioTimeline>(`/api/insights/timeline/capture?currency=${displayCurrency}`, { method: 'POST' })) }
    catch (e) { setTimelineError(e instanceof Error ? e.message : 'Could not capture a snapshot') }
    finally { setCapturing(false) }
  }
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
      { key: 'netWorth', label: 'Net worth', kind: 'series' },
      { key: 'invested', label: 'Invested', kind: 'series' },
      { key: 'current', label: 'Current value', kind: 'series' },
      { key: 'liabilities', label: 'Liabilities', kind: 'series' },
    ],
    resolve(w) {
      if (w.subType === 'counter') {
        const totals: Record<string, number> = {
          netWorth: dashboard.netWorth, totalAssets: dashboard.totalAssets,
          totalLiabilities: dashboard.totalLiabilities, totalProfitLoss: dashboard.portfolioProfitLoss,
        }
        return { kind: 'scalar', value: totals[w.query.measure ?? 'netWorth'] ?? dashboard.netWorth }
      }
      if (w.subType === '2d-graph') {
        const weeks = timeline?.weeks ?? []
        const fieldFor: Record<string, (week: TimelineWeek) => number> = {
          netWorth: week => week.netWorth, invested: week => week.invested, current: week => week.current, liabilities: week => week.liabilities,
        }
        const labelFor: Record<string, string> = { netWorth: 'Net worth', invested: 'Invested', current: 'Current value', liabilities: 'Liabilities' }
        return { kind: 'series', series: seriesKeysOf(w.query).map(key => ({
          label: labelFor[key] ?? key,
          points: weeks.map(week => ({ t: week.weekOf, v: (fieldFor[key] ?? fieldFor.netWorth)(week) })),
        })) }
      }
      const byDimension: Record<string, Breakdown[]> = { category: dashboard.byCategory, broker: dashboard.byBroker, tag: dashboard.byTag }
      const rows = byDimension[w.query.dimension ?? 'category'] ?? dashboard.byCategory
      const m = w.query.measure ?? 'value'
      const val = (b: Breakdown) => m === 'invested' ? b.investedValue : m === 'profitLoss' ? b.profitLoss : b.value
      return { kind: 'breakdown', rows: rows.map(r => ({ label: label(r.label), value: val(r) })) }
    },
  }

  const zone = { editing: layoutEditing, nonce: layoutNonce }
  return <LayoutZone zoneKey="dashboard/page" {...zone} defaults={[{ key: 'hero', span: 12 }, { key: 'widgets', span: 12 }, { key: 'kpis', span: 12 }, { key: 'breakdowns', span: 12 }, { key: 'timeline', span: 12 }]} render={{
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
    timeline: <section className="panel">
      <div className="panel-heading">
        <h3>Portfolio timeline <InfoTip text="Net worth and per-category invested/current value, captured every Monday, so you can see how the portfolio actually moved week over week." /></h3>
        <button className="outline compact" onClick={() => void captureNow()}
          disabled={capturing || (timeline?.capturedToday ?? false)}
          title={timeline?.capturedToday ? 'Already captured today' : 'Record this week’s values now'}>
          {capturing ? 'Capturing…' : timeline?.capturedToday ? 'Captured today' : 'Capture snapshot now'}
        </button>
      </div>
      <p className="hint">Net worth week by week — each category's invested and current value is snapshotted every Monday.
        {timeline?.lastCapturedAt && ` Last snapshot ${ago(timeline.lastCapturedAt)}.`}</p>
      {timelineError && <p className="form-error">{timelineError}</p>}
      <LayoutZone zoneKey="dashboard/timeline" {...zone} defaults={[{ key: 'networth', span: 12 }, { key: 'history', span: 12 }]} render={{
        networth: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">📈</span><h4>Net worth</h4></div>
          {!timeline ? <p className="hint">Loading timeline…</p>
            : timeline.weeks.length < 2
              ? <p className="hint">Not enough history yet — check back after a couple of weekly snapshots.</p>
              : <NetWorthChart weeks={timeline.weeks} />}
        </div>,

        history: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">📋</span><h4>Weekly history</h4><span className="action-col-count">{timeline?.weeks.length ?? 0}</span></div>
          {!timeline ? <p className="hint">Loading timeline…</p>
            : timeline.weeks.length === 0
              ? <p className="hint">No snapshots yet — this week's is being recorded now. Come back next week to see how things moved.</p>
              : <div className="timeline-panel"><TimelineTable weeks={timeline.weeks} /></div>}
        </div>,
      }} />
    </section>,
  }} />
}

export function BreakdownCard({ title, items, total }: { title: string; items: Breakdown[]; total: number }) {
  return <article className="panel"><div className="panel-heading"><h3>{title}</h3><span>{items.length} group{items.length === 1 ? '' : 's'}</span></div><div className="breakdown-list">{items.slice(0, 6).map(item => <div className="breakdown" key={item.label}><div className="breakdown-copy"><div><span title={label(item.label)}>{label(item.label)}</span><strong>{money(item.value)}</strong></div><div className="bar"><i style={{ width: `${Math.min(100, total ? (item.value / total) * 100 : 0)}%` }} /></div></div><b className={item.profitLoss >= 0 ? 'positive' : 'negative'}>{item.profitLoss >= 0 ? '+' : ''}{money(item.profitLoss)}</b></div>)}{!items.length && <p className="hint">Nothing to group yet.</p>}</div></article>
}
