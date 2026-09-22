import { Fragment, useEffect, useRef, useState } from 'react'
import type { FormEvent, MouseEvent as ReactMouseEvent } from 'react'
import { api } from '../api'
import { LayoutZone } from '../layout'
import { money, percent, label, since, ago, numeric, toggleLabel, periodLabels, periodFields, numberLocale } from '../util'
import { Field, SymbolSearchInput, InfoTip, useEscToClose } from '../ui'
import { seriesKeysOf } from '../widgets'
import type { PageDataSource } from '../widgets'
import type { Insights, Settings, Dashboard, Breakdown, ActionItem, TopMover, MovementThresholds, WatchlistEntry, PortfolioTimeline, TimelineWeek, PeriodKey, MarketQuote, ChartRange, MarketHistory } from '../types'

// ---------------------------------------------------------------------------

// Insights is deliberately not a second Overview — that breakdown/KPI content lives on the
// Dashboard. This page's own job is Hot Picks (market-linked holdings + watchlist symbols moving
// beyond a configured threshold) and data-quality checks.
export function InsightsView({ displayCurrency, dataVersion, settings, dashboard, reload, onOpen, layoutEditing, layoutNonce }: {
  displayCurrency: string; dataVersion: number; settings: Settings; dashboard: Dashboard; reload: () => Promise<void>; onOpen: (id: string) => void
  layoutEditing: boolean; layoutNonce: number
}) {
  const [data, setData] = useState<Insights | null>(null)
  const [error, setError] = useState('')
  const [topMovers, setTopMovers] = useState<TopMover[] | null>(null)
  const [watchlist, setWatchlist] = useState<WatchlistEntry[]>([])
  const [thresholds, setThresholds] = useState(() => thresholdForm(null))
  const [savingThresholds, setSavingThresholds] = useState(false)
  const [addingWatch, setAddingWatch] = useState(false)
  const [editingWatch, setEditingWatch] = useState<WatchlistEntry | null>(null)
  const [viewingChart, setViewingChart] = useState<{ symbol: string; name: string; currency?: string; triggered?: TopMover['triggered'] } | null>(null)
  const [watchlistOpen, setWatchlistOpen] = useState(false)
  const [timeline, setTimeline] = useState<PortfolioTimeline | null>(null)
  const [capturing, setCapturing] = useState(false)

  useEffect(() => { api<Insights>(`/api/insights?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message)) }, [displayCurrency, dataVersion])
  const loadTimeline = () => api<PortfolioTimeline>(`/api/insights/timeline?currency=${displayCurrency}`).then(setTimeline).catch(() => setTimeline({ weeks: [], capturedToday: false }))
  useEffect(() => { void loadTimeline() }, [displayCurrency, dataVersion])
  const captureNow = async () => {
    setCapturing(true)
    try { setTimeline(await api<PortfolioTimeline>(`/api/insights/timeline/capture?currency=${displayCurrency}`, { method: 'POST' })) }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not capture a snapshot') }
    finally { setCapturing(false) }
  }
  const loadTopMovers = () => api<TopMover[]>(`/api/insights/top-movers?currency=${displayCurrency}`).then(setTopMovers).catch(() => setTopMovers([]))
  const loadWatchlist = () => api<WatchlistEntry[]>(`/api/watchlist?currency=${displayCurrency}`).then(setWatchlist).catch(() => setWatchlist([]))
  const loadThresholds = () => api<MovementThresholds>('/api/insights/thresholds').then(t => setThresholds(thresholdForm(t))).catch(() => { /* leave defaults */ })
  useEffect(() => { void loadTopMovers() }, [displayCurrency, dataVersion])
  useEffect(() => { void loadWatchlist() }, [displayCurrency, dataVersion])
  useEffect(() => { void loadThresholds() }, [dataVersion])

  const saveThresholds = async () => {
    setSavingThresholds(true)
    try {
      await api('/api/insights/thresholds', { method: 'PUT', body: JSON.stringify({
        dailyPercent: numOrNull(thresholds.DAILY), weeklyPercent: numOrNull(thresholds.WEEKLY),
        monthlyPercent: numOrNull(thresholds.MONTHLY), quarterlyPercent: numOrNull(thresholds.QUARTERLY),
        halfYearlyPercent: numOrNull(thresholds.HALF_YEARLY), yearlyPercent: numOrNull(thresholds.YEARLY),
      }) })
      await loadTopMovers()
    } finally { setSavingThresholds(false) }
  }
  const removeWatch = async (item: WatchlistEntry) => {
    if (!confirm(`Remove ${item.name} from the watchlist?`)) return
    await api(`/api/watchlist/${item.id}`, { method: 'DELETE' }); await loadWatchlist(); await loadTopMovers()
  }

  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading insights…</p>
  const thresholdsSet = Object.values(thresholds).filter(value => value.trim() !== '').length

  const isPendingAction = (kind: string) =>
    kind === 'EMI_DUE' || kind === 'EMI_OVERDUE' || kind === 'INTEREST_DUE' || kind === 'INTEREST_OVERDUE'
  const pendingActions = data.actions.filter(a => isPendingAction(a.kind))
  const radarItems = data.actions.filter(a => !isPendingAction(a.kind))
  const dismissAction = async (key: string, status: 'DONE' | 'DEFERRED' | 'DELETED') => {
    try { await api('/api/insights/actions', { method: 'POST', body: JSON.stringify({ key, status }) }); await reload() }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not update that item') }
  }
  const renderAction = (a: ActionItem, i: number) => {
    const isEmi = a.kind === 'EMI_DUE' || a.kind === 'EMI_OVERDUE'
    const isInterest = a.kind === 'INTEREST_DUE' || a.kind === 'INTEREST_OVERDUE'
    return <div key={`${a.key}-${i}`} className={`warning ${a.severity.toLowerCase()}`}>
      <b>{a.severity}</b>
      <button className="warning-body" onClick={() => a.holdingId && onOpen(a.holdingId)}>
        <strong>{a.title}</strong><span>{a.detail}</span>
      </button>
      {(isEmi || isInterest) && a.holdingId && a.period && <button className="warning-action" onClick={async e => {
        e.stopPropagation()
        const url = isEmi
          ? `/api/emis/${a.holdingId}/pay?dueDate=${a.period}`
          : `/api/interest-payouts/${a.holdingId}/confirm?dueDate=${a.period}`
        try { await api(url, { method: 'POST' }); await reload() }
        catch (err) { setError(err instanceof Error ? err.message : 'Could not record that') }
      }}>{isEmi ? 'Log repayment' : 'Log interest'}</button>}
      <div className="warning-tools">
        <button className="warning-tool done" title="Mark done" aria-label="Mark done"
          onClick={e => { e.stopPropagation(); void dismissAction(a.key, 'DONE') }}>✓</button>
        <button className="warning-tool defer" title="Snooze for a week" aria-label="Snooze for a week"
          onClick={e => { e.stopPropagation(); void dismissAction(a.key, 'DEFERRED') }}>⏰</button>
        <button className="warning-tool delete" title="Dismiss" aria-label="Dismiss"
          onClick={e => { e.stopPropagation(); void dismissAction(a.key, 'DELETED') }}>🗑</button>
      </div>
    </div>
  }

  const insightsDataSource: PageDataSource = {
    attributes: [
      { key: 'category', label: 'Category', kind: 'dimension' },
      { key: 'broker', label: 'Broker', kind: 'dimension' },
      { key: 'tag', label: 'Tag', kind: 'dimension' },
      { key: 'currency', label: 'Currency', kind: 'dimension' },
      { key: 'liquidity', label: 'Liquidity', kind: 'dimension' },
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
      const byDimension: Record<string, Breakdown[]> = { category: data.byCategory, broker: data.byBroker, tag: data.byTag, currency: data.byCurrency, liquidity: data.byLiquidity }
      const rows = byDimension[w.query.dimension ?? 'category'] ?? data.byCategory
      const m = w.query.measure ?? 'value'
      const val = (b: Breakdown) => m === 'invested' ? b.investedValue : m === 'profitLoss' ? b.profitLoss : b.value
      return { kind: 'breakdown', rows: rows.map(r => ({ label: label(r.label), value: val(r) })) }
    },
  }

  const zone = { editing: layoutEditing, nonce: layoutNonce }
  return <>
    <LayoutZone zoneKey="insights/page" {...zone} defaults={[{ key: 'widgets', span: 12 }, { key: 'actions', span: 12 }, { key: 'topMovers', span: 12 }, { key: 'timeline', span: 12 }]} render={{
    widgets: <LayoutZone zoneKey="insights/widgets" {...zone} dataSource={insightsDataSource} />,
    actions: <section className="panel data-quality">
      <div className="panel-heading"><h3>Action centre <InfoTip text="Things that need a decision from you — EMIs and interest due, matured deposits, missing values — split into what's due now and what's worth a second look." /></h3><span>{data.actions.length} item{data.actions.length === 1 ? '' : 's'}</span></div>
      <LayoutZone zoneKey="insights/actions" {...zone} defaults={[{ key: 'pending', span: 6 }, { key: 'radar', span: 6 }]} render={{
        pending: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">⚡</span><h4>Pending actions <InfoTip text="EMIs and interest payouts due or overdue right now — log them here or from the holding itself." /></h4><span className="action-col-count">{pendingActions.length}</span></div>
          {pendingActions.length
            ? <div className="warning-list action-col-list">{pendingActions.map(renderAction)}</div>
            : <p className="hint">Nothing to log right now — you're caught up.</p>}
        </div>,
        radar: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">🔭</span><h4>On your radar <InfoTip text="Lower-urgency flags — matured fixed deposits, holdings missing a current value — worth a look when you get to it." /></h4><span className="action-col-count">{radarItems.length}</span></div>
          {radarItems.length
            ? <div className="warning-list action-col-list">{radarItems.map(renderAction)}</div>
            : <p className="hint">Nothing needs a second look right now.</p>}
        </div>,
      }} />
    </section>,

    topMovers: <section className="panel">
      <div className="panel-heading hot-picks-heading">
        <h3>🔥 Hot Picks <InfoTip text="Market-linked holdings and watchlist symbols whose price has moved past the % you set here for that lookback window, checked daily through yearly. Click any entry for its price history and which windows it broke." /></h3>
        <div className="threshold-inline">
          <span className="threshold-inline-label">Thresholds<br />(%)</span>
          {periodFields.map(([key, text]) => <label key={key} className="threshold-inline-field" title={`${text} threshold`}>
            <span>{text}</span>
            <input type="number" min="0" step="0.1" placeholder="—" value={thresholds[key]} disabled={savingThresholds}
              onChange={e => setThresholds(current => ({ ...current, [key]: e.target.value }))}
              onBlur={() => void saveThresholds()} />
          </label>)}
        </div>
      </div>
      <LayoutZone zoneKey="insights/topmovers" {...zone} defaults={[{ key: 'movers', span: 12 }, { key: 'watchlist', span: 12 }]} render={{
        movers: <div className="action-col">
          {topMovers === null ? <p className="hint">Loading hot picks…</p> : topMovers.length ? <div className="top-mover-list">
            {topMovers.map(pick => <button key={`${pick.subjectType}-${pick.id}`} className={`top-mover${pick.tickerSymbol ? '' : ' static'}`}
                onClick={() => pick.tickerSymbol && setViewingChart({ symbol: pick.tickerSymbol, name: pick.name, currency: pick.currency, triggered: pick.triggered })}>
              <div className="top-mover-name"><strong>{pick.name}</strong><small>{pick.categoryName || pick.tickerSymbol || 'Watchlist'}</small></div>
              <div className="top-mover-value">{pick.currency ? money(pick.currentValue ?? 0, pick.currency) : (pick.currentValue ?? 0).toLocaleString(numberLocale)}</div>
              <div className="top-mover-badges">{pick.triggered.map(t => <span key={t.period} className={`mover-badge ${t.percent >= 0 ? 'positive' : 'negative'}`}>{periodLabels[t.period]} {t.percent >= 0 ? '+' : ''}{t.percent.toFixed(2)}%</span>)}</div>
            </button>)}
          </div> : thresholdsSet === 0
            ? <p className="hint">No thresholds set yet — set one above to choose how far a market-linked holding or watchlist symbol has to move before it lands here.</p>
            : <p className="hint">Nothing is outside your configured thresholds right now.</p>}
        </div>,

        watchlist: <div className="action-col">
          <button className="action-col-head action-col-toggle" onClick={() => setWatchlistOpen(open => !open)} aria-expanded={watchlistOpen}>
            <span className="action-col-icon">👁</span><h4>Watchlist</h4><span className="action-col-count">{watchlist.length}</span>
            <span className="action-col-caret">{toggleLabel(watchlistOpen)}</span>
          </button>
          {watchlistOpen && <>
            <div className="watchlist-actions">
              <span className="watchlist-count">{watchlist.length ? `${watchlist.length} symbol${watchlist.length === 1 ? '' : 's'} tracked` : 'No symbols yet'}</span>
              <button className="outline compact" onClick={() => setAddingWatch(true)}>+ Add to watchlist</button>
            </div>
            {watchlist.length ? <div className="table-panel"><table>
              <thead><tr><th>Name</th><th>Ticker</th><th /></tr></thead>
              <tbody>{watchlist.map(item => {
                const moverEntry = topMovers?.find(p => p.subjectType === 'WATCHLIST' && p.id === item.id)
                return <tr key={item.id} className={item.tickerSymbol ? 'clickable-row' : ''}
                    onClick={() => item.tickerSymbol && setViewingChart({ symbol: item.tickerSymbol, name: item.name, currency: item.currency, triggered: moverEntry?.triggered })}>
                  <td><div className="name-cell"><strong title={item.name}>{item.name}</strong>{item.notes && <InfoTip text={item.notes} />}</div></td>
                  <td>{item.tickerSymbol || '—'}</td>
                  <td className="actions actions-vertical">
                    <button className="primary-link" onClick={e => { e.stopPropagation(); setEditingWatch(item) }}>Edit</button>
                    <button className="danger-link" onClick={e => { e.stopPropagation(); void removeWatch(item) }}>Delete</button>
                  </td>
                </tr>
              })}</tbody>
            </table></div> : <p className="hint">Track a symbol you don't hold — like an index or a stock you're watching — to get it into Hot Picks too.</p>}
          </>}
        </div>,
      }} />
    </section>,

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
      <LayoutZone zoneKey="insights/timeline" {...zone} defaults={[{ key: 'networth', span: 12 }, { key: 'history', span: 12 }]} render={{
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
    {(addingWatch || editingWatch) && <WatchlistModal item={editingWatch}
      onClose={() => { setAddingWatch(false); setEditingWatch(null) }}
      onSaved={() => { setAddingWatch(false); setEditingWatch(null); void loadWatchlist(); void loadTopMovers() }} />}
    {viewingChart && <ViewMoverItemModal symbol={viewingChart.symbol} name={viewingChart.name} currency={viewingChart.currency} triggered={viewingChart.triggered}
      onClose={() => setViewingChart(null)} />}
  </>
}

// Shared by every hand-rolled inline-SVG line chart (NetWorthChart, PriceHistoryChart): tracks
// the pointer over the plot, snaps to the nearest data point by x, and returns enough to render
// a guide line + dot in the SVG and a fixed-positioned tooltip outside it (so it's never clipped
// by a scrolling ancestor — same technique as InfoTip). `points` must already be projected into
// the chart's own SVG viewBox coordinates.
function useChartHover(points: { x: number; y: number }[], W: number) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [index, setIndex] = useState<number | null>(null)
  const [tip, setTip] = useState<{ left: number; top: number } | null>(null)

  useEffect(() => { setIndex(null); setTip(null) }, [points.length])

  const onMove = (e: ReactMouseEvent<SVGSVGElement>) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect || !points.length) return
    const svgX = (e.clientX - rect.left) / rect.width * W
    let nearest = 0, nearestDist = Infinity
    points.forEach((p, i) => { const d = Math.abs(p.x - svgX); if (d < nearestDist) { nearestDist = d; nearest = i } })
    const vw = window.innerWidth || document.documentElement.clientWidth || 1024
    const screenX = rect.left + (points[nearest].x / W) * rect.width
    setIndex(nearest)
    setTip({ left: Math.min(Math.max(screenX, 90), Math.max(vw - 90, 90)), top: rect.top - 10 })
  }
  const onLeave = () => { setIndex(null); setTip(null) }
  return { svgRef, onMove, onLeave, index, tip }
}

// A lightweight inline-SVG line chart of net worth across the snapshot weeks (oldest → newest).
export function NetWorthChart({ weeks }: { weeks: TimelineWeek[] }) {
  const W = 680, H = 150, padX = 10, padTop = 12, padBot = 16
  const values = weeks.map(w => w.netWorth)
  const min = Math.min(...values), max = Math.max(...values)
  const span = max - min || Math.abs(max) || 1
  const x = (i: number) => padX + (i / (weeks.length - 1)) * (W - padX * 2)
  const y = (v: number) => padTop + (1 - (v - min) / span) * (H - padTop - padBot)
  const points = weeks.map((w, i) => ({ x: x(i), y: y(w.netWorth) }))
  const { svgRef, onMove, onLeave, index, tip } = useChartHover(points, W)
  if (weeks.length < 2) return null
  const line = weeks.map((w, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(1)} ${y(w.netWorth).toFixed(1)}`).join(' ')
  const area = `${line} L ${x(weeks.length - 1).toFixed(1)} ${(H - padBot).toFixed(1)} L ${x(0).toFixed(1)} ${(H - padBot).toFixed(1)} Z`
  const growth = weeks[weeks.length - 1].netWorth - weeks[0].netWorth
  const zeroY = min < 0 && max > 0 ? y(0) : null
  const hovered = index != null ? weeks[index] : null
  return <div className="networth-chart">
    <div className="networth-caption">
      <span>Net worth</span>
      <strong className={growth >= 0 ? 'positive' : 'negative'}>{growth >= 0 ? '▲' : '▼'} {money(Math.abs(growth))} over {weeks.length} weeks</strong>
    </div>
    <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="networth-svg" role="img" aria-label="Net worth over time"
        onMouseMove={onMove} onMouseLeave={onLeave}>
      {zeroY != null && <line x1={padX} x2={W - padX} y1={zeroY} y2={zeroY} className="networth-zero" vectorEffect="non-scaling-stroke" />}
      <path d={area} className="networth-area" />
      <path d={line} className="networth-line" vectorEffect="non-scaling-stroke" />
      {hovered && <g>
        <line x1={points[index!].x} x2={points[index!].x} y1={padTop} y2={H - padBot} className="chart-hover-line" vectorEffect="non-scaling-stroke" />
        <circle cx={points[index!].x} cy={points[index!].y} r="4" className="chart-hover-dot" />
      </g>}
    </svg>
    <div className="networth-axis"><span>{since(weeks[0].weekOf)}</span><span>{since(weeks[weeks.length - 1].weekOf)}</span></div>
    {hovered && tip && <div className="chart-tooltip" style={{ left: tip.left, top: tip.top }}>
      <strong>{money(hovered.netWorth)}</strong><span>{since(hovered.weekOf)}</span>
    </div>}
  </div>
}

// Weekly portfolio history. Newest week first; each row expands to the per-category split.
export function TimelineTable({ weeks }: { weeks: TimelineWeek[] }) {
  const [openWeek, setOpenWeek] = useState<string | null>(null)
  const ordered = [...weeks].reverse()
  const delta = (a: number, b?: number) => (b == null ? null : a - b)
  return <div className="table-panel"><table className="timeline-table">
    <thead><tr><th>Week of</th><th>Invested</th><th>Current</th><th>Unrealised P/L</th><th>Net worth</th></tr></thead>
    <tbody>{ordered.map((w, i) => {
      const prev = ordered[i + 1]
      const pl = w.current - w.invested
      const nwMove = delta(w.netWorth, prev?.netWorth)
      const isOpen = openWeek === w.weekOf
      return <Fragment key={w.weekOf}>
        <tr className="clickable-row" onClick={() => setOpenWeek(isOpen ? null : w.weekOf)}>
          <td><strong>{since(w.weekOf)}</strong> <span className="row-caret">{isOpen ? '▴' : '▾'}</span></td>
          <td>{money(w.invested)}</td>
          <td>{money(w.current)}</td>
          <td className={pl >= 0 ? 'positive' : 'negative'}>{pl >= 0 ? '+' : ''}{money(pl)}</td>
          <td>{money(w.netWorth)}{nwMove != null && nwMove !== 0 &&
            <small className={nwMove > 0 ? 'positive' : 'negative'}> {nwMove > 0 ? '▲' : '▼'} {money(Math.abs(nwMove))}</small>}</td>
        </tr>
        {isOpen && <tr className="timeline-detail-row"><td colSpan={5}>
          <table className="timeline-detail"><tbody>
            {w.categories.map(c => {
              const cpl = c.current - c.invested
              const liability = c.kind === 'LIABILITY'
              return <tr key={c.categoryId}>
                <td>{c.categoryName}{liability && <em className="pill-liability">liability</em>}</td>
                <td>{liability ? '—' : money(c.invested)}</td>
                <td>{money(c.current)}</td>
                <td className={liability ? '' : cpl >= 0 ? 'positive' : 'negative'}>
                  {liability ? '' : `${cpl >= 0 ? '+' : ''}${money(cpl)}`}</td>
              </tr>
            })}
          </tbody></table>
        </td></tr>}
      </Fragment>
    })}</tbody>
  </table></div>
}

export function thresholdForm(thresholds: MovementThresholds | null): Record<PeriodKey, string> {
  const value = (v?: number) => v != null ? String(v) : ''
  return {
    DAILY: value(thresholds?.dailyPercent),
    WEEKLY: value(thresholds?.weeklyPercent),
    MONTHLY: value(thresholds?.monthlyPercent),
    QUARTERLY: value(thresholds?.quarterlyPercent),
    HALF_YEARLY: value(thresholds?.halfYearlyPercent),
    YEARLY: value(thresholds?.yearlyPercent),
  }
}
export const numOrNull = (value: string) => value === '' ? null : Number(value)

export function WatchlistModal({ item, onClose, onSaved }: { item: WatchlistEntry | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => ({
    name: item?.name ?? '', tickerSymbol: item?.tickerSymbol ?? '', notes: item?.notes ?? '',
    price: item?.currentValue != null ? String(item.currentValue) : '', currency: item?.currency ?? '',
  }))
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const set = (key: string, value: string) => { setDirty(true); setForm(current => ({ ...current, [key]: value })) }
  useEscToClose(onClose, dirty)
  // Name, price, and currency always come from the ticker's live quote — a market-linked entry, not free text.
  useEffect(() => {
    const symbol = form.tickerSymbol.trim()
    if (!symbol) return
    const timer = setTimeout(() => {
      api<MarketQuote>(`/api/market/quote?symbol=${encodeURIComponent(symbol)}`)
        .then(q => setForm(current => current.tickerSymbol.trim().toUpperCase() !== symbol.toUpperCase() ? current
          : { ...current, name: q.name || current.name, price: q.price ? String(q.price) : current.price, currency: q.currency || current.currency }))
        .catch(() => { /* leave fields as-is */ })
    }, 400)
    return () => clearTimeout(timer)
  }, [form.tickerSymbol])
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    try {
      if (item) {
        await api(`/api/watchlist/${item.id}`, { method: 'PUT', body: JSON.stringify({
          name: form.name, tickerSymbol: form.tickerSymbol || null, currency: form.currency || null, notes: form.notes || null,
        }) })
        if (form.price !== '') {
          await api(`/api/watchlist/${item.id}/price`, { method: 'POST', body: JSON.stringify({ price: Number(form.price) }) })
        }
      } else {
        await api('/api/watchlist', { method: 'POST', body: JSON.stringify({
          name: form.name, tickerSymbol: form.tickerSymbol || null, currency: form.currency || null, notes: form.notes || null, price: Number(form.price),
        }) })
      }
      onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save watchlist item') } finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section className="modal narrow"><div className="modal-header">
    <div><p className="eyebrow">{item ? 'EDIT WATCHLIST ITEM' : 'ADD TO WATCHLIST'}</p><h2>{item ? item.name : 'Track a symbol'}</h2></div>
    <button className="close" onClick={onClose}>×</button>
  </div>
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Ticker" required wide><SymbolSearchInput value={form.tickerSymbol} onChange={v => set('tickerSymbol', v)} /></Field>
        <Field label="Name" wide><input disabled maxLength={128} value={form.name} placeholder="Filled from the ticker" /></Field>
        <div className="field-pair">
          <Field label="Currency"><input disabled value={form.currency} placeholder="Filled from the ticker" /></Field>
          <Field label="Current price"><input disabled type="number" value={form.price} placeholder="Filled from the ticker" /></Field>
        </div>
        <Field label="Notes" wide><textarea maxLength={1024} value={form.notes} onChange={e => set('notes', e.target.value)} placeholder="Optional notes" /></Field>
      </div>
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || !form.tickerSymbol.trim() || !form.name.trim() || !form.price.trim() || !form.currency.trim()}>{saving ? 'Saving…' : item ? 'Save changes' : 'Add to watchlist'}</button></div>
    </form>
  </section></div>
}

const chartRanges: ChartRange[] = ['1D', '1W', '1M', '3M', '6M', '1Y']

// ViewMoverItem: a symbol's price history over a chosen window (up to 1 year), for anything
// market-linked — a Hot Picks entry or a watchlist item alike. Backed by /api/market/history,
// which reads straight from the same Yahoo Finance feed as live quotes.
export function ViewMoverItemModal({ symbol, name, currency, triggered, onClose }: {
  symbol: string; name: string; currency?: string; triggered?: TopMover['triggered']; onClose: () => void
}) {
  const [range, setRange] = useState<ChartRange>('1M')
  const [history, setHistory] = useState<MarketHistory | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEscToClose(onClose)

  useEffect(() => {
    setLoading(true); setError('')
    api<MarketHistory>(`/api/market/history?symbol=${encodeURIComponent(symbol)}&range=${range}`)
      .then(setHistory)
      .catch(() => { setHistory(null); setError('No price history available for this symbol yet.') })
      .finally(() => setLoading(false))
  }, [symbol, range])

  return <div className="modal-backdrop"><section className="modal"><div className="modal-header">
    <div><p className="eyebrow">PRICE HISTORY</p><h2>{name}</h2></div>
    <button className="close" onClick={onClose}>×</button>
  </div>
    {triggered && triggered.length > 0 && <div className="view-mover-thresholds">
      <span className="hint">Currently past threshold:</span>
      <div className="top-mover-badges">{triggered.map(t => <span key={t.period} className={`mover-badge ${t.percent >= 0 ? 'positive' : 'negative'}`}>
        {periodLabels[t.period]} {t.percent >= 0 ? '+' : ''}{t.percent.toFixed(2)}% <small>(threshold {t.thresholdPercent}%)</small>
      </span>)}</div>
    </div>}
    <div className="format-tabs">
      {chartRanges.map(r => <button key={r} type="button" className={r === range ? 'active' : ''} onClick={() => setRange(r)}>{r}</button>)}
    </div>
    {loading ? <p className="hint">Loading price history…</p>
      : error || !history || history.points.length < 2 ? <p className="hint">{error || 'Not enough price history for this window yet.'}</p>
      : <PriceHistoryChart points={history.points} currency={history.currency || currency} />}
  </section></div>
}

// Date-only (`since`) reads fine for the chart's fixed start/end axis labels, but a hovered point
// on the 1D/1W ranges needs its time of day too, or every point in that window looks identical.
const pointTimestamp = (value: string) => new Date(value).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })

function PriceHistoryChart({ points, currency }: { points: { timestamp: string; price: number }[]; currency?: string }) {
  const W = 680, H = 220, padX = 10, padTop = 12, padBot = 16
  const values = points.map(p => p.price)
  const min = Math.min(...values), max = Math.max(...values)
  const span = max - min || Math.abs(max) || 1
  const x = (i: number) => padX + (i / (points.length - 1)) * (W - padX * 2)
  const y = (v: number) => padTop + (1 - (v - min) / span) * (H - padTop - padBot)
  const chartPoints = points.map((p, i) => ({ x: x(i), y: y(p.price) }))
  const { svgRef, onMove, onLeave, index, tip } = useChartHover(chartPoints, W)
  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(1)} ${y(p.price).toFixed(1)}`).join(' ')
  const area = `${line} L ${x(points.length - 1).toFixed(1)} ${(H - padBot).toFixed(1)} L ${x(0).toFixed(1)} ${(H - padBot).toFixed(1)} Z`
  const first = points[0].price, last = points[points.length - 1].price
  const change = last - first
  const changePercent = first !== 0 ? (change / first) * 100 : 0
  const fmt = (v: number) => currency ? money(v, currency) : v.toLocaleString(numberLocale)
  const hovered = index != null ? points[index] : null
  return <div className="networth-chart">
    <div className="networth-caption">
      <span>{fmt(last)}</span>
      <strong className={change >= 0 ? 'positive' : 'negative'}>{change >= 0 ? '▲' : '▼'} {fmt(Math.abs(change))} ({changePercent >= 0 ? '+' : ''}{changePercent.toFixed(2)}%)</strong>
    </div>
    <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="networth-svg" role="img" aria-label="Price history"
        onMouseMove={onMove} onMouseLeave={onLeave}>
      <path d={area} className="networth-area" />
      <path d={line} className="networth-line" vectorEffect="non-scaling-stroke" />
      {hovered && <g>
        <line x1={chartPoints[index!].x} x2={chartPoints[index!].x} y1={padTop} y2={H - padBot} className="chart-hover-line" vectorEffect="non-scaling-stroke" />
        <circle cx={chartPoints[index!].x} cy={chartPoints[index!].y} r="4" className="chart-hover-dot" />
      </g>}
    </svg>
    <div className="networth-axis"><span>{since(points[0].timestamp)}</span><span>{since(points[points.length - 1].timestamp)}</span></div>
    {hovered && tip && <div className="chart-tooltip" style={{ left: tip.left, top: tip.top }}>
      <strong>{fmt(hovered.price)}</strong><span>{pointTimestamp(hovered.timestamp)}</span>
    </div>}
  </div>
}
