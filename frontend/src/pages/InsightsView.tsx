import { Fragment, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { LayoutZone } from '../layout'
import { money, percent, label, since, ago, numeric, periodLabels, periodFields, numberLocale } from '../util'
import { Field, SymbolSearchInput, InfoTip } from '../ui'
import type { Insights, Settings, ActionItem, TopMover, MovementThresholds, WatchlistEntry, PortfolioTimeline, TimelineWeek, PeriodKey, MarketQuote } from '../types'

// ---------------------------------------------------------------------------

// Insights is deliberately not a second Overview: the shared breakdown/movers data that also
// appears on the Dashboard lives in the collapsed "Portfolio overview" section at the bottom.
// This page's own job is Hot Picks (market-linked holdings + watchlist symbols moving beyond a
// configured up/down threshold) and data-quality checks.
export function InsightsView({ displayCurrency, dataVersion, settings, reload, onOpen, layoutEditing, layoutNonce }: {
  displayCurrency: string; dataVersion: number; settings: Settings; reload: () => Promise<void>; onOpen: (id: string) => void
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
  const loadWatchlist = () => api<WatchlistEntry[]>('/api/watchlist').then(setWatchlist).catch(() => setWatchlist([]))
  const loadThresholds = () => api<MovementThresholds>('/api/insights/thresholds').then(t => setThresholds(thresholdForm(t))).catch(() => { /* leave defaults */ })
  useEffect(() => { void loadTopMovers() }, [displayCurrency, dataVersion])
  useEffect(() => { void loadWatchlist() }, [dataVersion])
  useEffect(() => { void loadThresholds() }, [dataVersion])

  const saveThresholds = async () => {
    setSavingThresholds(true)
    try {
      await api('/api/insights/thresholds', { method: 'PUT', body: JSON.stringify({
        dailyUpPercent: numOrNull(thresholds.DAILY.up), dailyDownPercent: numOrNull(thresholds.DAILY.down),
        weeklyUpPercent: numOrNull(thresholds.WEEKLY.up), weeklyDownPercent: numOrNull(thresholds.WEEKLY.down),
        monthlyUpPercent: numOrNull(thresholds.MONTHLY.up), monthlyDownPercent: numOrNull(thresholds.MONTHLY.down),
        quarterlyUpPercent: numOrNull(thresholds.QUARTERLY.up), quarterlyDownPercent: numOrNull(thresholds.QUARTERLY.down),
        yearlyUpPercent: numOrNull(thresholds.YEARLY.up), yearlyDownPercent: numOrNull(thresholds.YEARLY.down),
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
  const thresholdsSet = Object.values(thresholds).filter(pair => pair.up.trim() !== '' || pair.down.trim() !== '').length

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

  const zone = { editing: layoutEditing, nonce: layoutNonce }
  return <>
    <LayoutZone zoneKey="insights/page" {...zone} defaults={[{ key: 'actions', span: 12 }, { key: 'topMovers', span: 12 }, { key: 'timeline', span: 12 }]} render={{
    actions: <section className="panel data-quality">
      <div className="panel-heading"><h3>Action centre</h3><span>{data.actions.length} item{data.actions.length === 1 ? '' : 's'}</span></div>
      <LayoutZone zoneKey="insights/actions" {...zone} defaults={[{ key: 'pending', span: 6 }, { key: 'radar', span: 6 }]} render={{
        pending: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">⚡</span><h4>Pending actions</h4><span className="action-col-count">{pendingActions.length}</span></div>
          {pendingActions.length
            ? <div className="warning-list action-col-list">{pendingActions.map(renderAction)}</div>
            : <p className="hint">Nothing to log right now — you're caught up.</p>}
        </div>,
        radar: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">🔭</span><h4>On your radar</h4><span className="action-col-count">{radarItems.length}</span></div>
          {radarItems.length
            ? <div className="warning-list action-col-list">{radarItems.map(renderAction)}</div>
            : <p className="hint">Nothing needs a second look right now.</p>}
        </div>,
      }} />
    </section>,

    topMovers: <section className="panel">
      <div className="panel-heading"><h3>🔥 Hot Picks</h3><span>Market-linked movement beyond your thresholds</span></div>
      <LayoutZone zoneKey="insights/topmovers" {...zone} defaults={[{ key: 'movers', span: 12 }, { key: 'thresholds', span: 6 }, { key: 'watchlist', span: 6 }]} render={{
        movers: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">🔥</span><h4>Movers</h4><span className="action-col-count">{topMovers?.length ?? 0}</span></div>
          {topMovers === null ? <p className="hint">Loading hot picks…</p> : topMovers.length ? <div className="top-mover-list">
            {topMovers.map(pick => <button key={`${pick.subjectType}-${pick.id}`} className={`top-mover${pick.subjectType === 'HOLDING' ? '' : ' static'}`}
                onClick={() => pick.subjectType === 'HOLDING' && onOpen(pick.id)}>
              <div className="top-mover-name"><strong>{pick.name}</strong><small>{pick.categoryName || pick.tickerSymbol || 'Watchlist'}</small></div>
              <div className="top-mover-value">{pick.currency ? money(pick.currentValue ?? 0, pick.currency) : (pick.currentValue ?? 0).toLocaleString(numberLocale)}</div>
              <div className="top-mover-badges">{pick.triggered.map(t => <span key={t.period} className={`mover-badge ${t.percent >= 0 ? 'positive' : 'negative'}`}>{periodLabels[t.period]} {t.percent >= 0 ? '+' : ''}{t.percent.toFixed(2)}%</span>)}</div>
            </button>)}
          </div> : thresholdsSet === 0
            ? <p className="hint">No thresholds set yet — set an up or down % in the Thresholds panel to choose how far a market-linked holding has to move before it lands here.</p>
            : <p className="hint">Nothing is outside your configured thresholds right now.</p>}
        </div>,

        thresholds: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">🎯</span><h4>Thresholds</h4>
            <span className="action-col-info"><InfoTip text="Each lookback window checks the move since that many days ago against its own up % and down % — either one alone can put a holding or watchlist symbol into Movers. Leave both blank to turn a window off." /></span>
          </div>
          <p className="hint">Up % catches gains, down % catches drops — set either or both, independently, per window.</p>
          <div className="threshold-grid">
            <div className="threshold-grid-row threshold-grid-head"><span /><span>Up %</span><span>Down %</span></div>
            {periodFields.map(([key, text]) => <div className="threshold-grid-row" key={key}>
              <span>{text}</span>
              <input type="number" min="0" step="0.1" placeholder="e.g. 5" value={thresholds[key].up}
                onChange={e => setThresholds(current => ({ ...current, [key]: { ...current[key], up: e.target.value } }))} />
              <input type="number" min="0" step="0.1" placeholder="e.g. 10" value={thresholds[key].down}
                onChange={e => setThresholds(current => ({ ...current, [key]: { ...current[key], down: e.target.value } }))} />
            </div>)}
          </div>
          <button className="primary compact" onClick={() => void saveThresholds()} disabled={savingThresholds}>{savingThresholds ? 'Saving…' : 'Save thresholds'}</button>
        </div>,

        watchlist: <div className="action-col">
          <div className="action-col-head"><span className="action-col-icon">👁</span><h4>Watchlist</h4><span className="action-col-count">{watchlist.length}</span></div>
          <div className="watchlist-actions">
            <span className="watchlist-count">{watchlist.length ? `${watchlist.length} symbol${watchlist.length === 1 ? '' : 's'} tracked` : 'No symbols yet'}</span>
            <button className="outline compact" onClick={() => setAddingWatch(true)}>+ Add to watchlist</button>
          </div>
          {watchlist.length ? <div className="table-panel"><table>
            <thead><tr><th>Name</th><th>Ticker</th><th>Price</th><th>Last updated</th><th /></tr></thead>
            <tbody>{watchlist.map(item => <tr key={item.id}>
              <td><strong>{item.name}</strong>{item.notes && <small className="owner">{item.notes}</small>}</td>
              <td>{item.tickerSymbol || '—'}</td>
              <td>{item.currentValue != null ? item.currentValue.toLocaleString(numberLocale) : '—'}</td>
              <td>{since(item.lastUpdated)}</td>
              <td className="actions actions-vertical"><button className="primary-link" onClick={() => setEditingWatch(item)}>Edit</button><button className="danger-link" onClick={() => void removeWatch(item)}>Delete</button></td>
            </tr>)}</tbody>
          </table></div> : <p className="hint">Track a symbol you don't hold — like an index or a stock you're watching — to get it into Hot Picks too.</p>}
        </div>,
      }} />
    </section>,

    timeline: <section className="panel">
      <div className="panel-heading">
        <h3>Portfolio timeline</h3>
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
  </>
}

// A lightweight inline-SVG line chart of net worth across the snapshot weeks (oldest → newest).
export function NetWorthChart({ weeks }: { weeks: TimelineWeek[] }) {
  if (weeks.length < 2) return null
  const W = 680, H = 150, padX = 10, padTop = 12, padBot = 16
  const values = weeks.map(w => w.netWorth)
  const min = Math.min(...values), max = Math.max(...values)
  const span = max - min || Math.abs(max) || 1
  const x = (i: number) => padX + (i / (weeks.length - 1)) * (W - padX * 2)
  const y = (v: number) => padTop + (1 - (v - min) / span) * (H - padTop - padBot)
  const line = weeks.map((w, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(1)} ${y(w.netWorth).toFixed(1)}`).join(' ')
  const area = `${line} L ${x(weeks.length - 1).toFixed(1)} ${(H - padBot).toFixed(1)} L ${x(0).toFixed(1)} ${(H - padBot).toFixed(1)} Z`
  const growth = weeks[weeks.length - 1].netWorth - weeks[0].netWorth
  const zeroY = min < 0 && max > 0 ? y(0) : null
  return <div className="networth-chart">
    <div className="networth-caption">
      <span>Net worth</span>
      <strong className={growth >= 0 ? 'positive' : 'negative'}>{growth >= 0 ? '▲' : '▼'} {money(Math.abs(growth))} over {weeks.length} weeks</strong>
    </div>
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="networth-svg" role="img" aria-label="Net worth over time">
      {zeroY != null && <line x1={padX} x2={W - padX} y1={zeroY} y2={zeroY} className="networth-zero" vectorEffect="non-scaling-stroke" />}
      <path d={area} className="networth-area" />
      <path d={line} className="networth-line" vectorEffect="non-scaling-stroke" />
    </svg>
    <div className="networth-axis"><span>{since(weeks[0].weekOf)}</span><span>{since(weeks[weeks.length - 1].weekOf)}</span></div>
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

export type ThresholdPair = { up: string; down: string }
export function thresholdForm(thresholds: MovementThresholds | null): Record<PeriodKey, ThresholdPair> {
  const pair = (up?: number, down?: number): ThresholdPair =>
    ({ up: up != null ? String(up) : '', down: down != null ? String(down) : '' })
  return {
    DAILY: pair(thresholds?.dailyUpPercent, thresholds?.dailyDownPercent),
    WEEKLY: pair(thresholds?.weeklyUpPercent, thresholds?.weeklyDownPercent),
    MONTHLY: pair(thresholds?.monthlyUpPercent, thresholds?.monthlyDownPercent),
    QUARTERLY: pair(thresholds?.quarterlyUpPercent, thresholds?.quarterlyDownPercent),
    YEARLY: pair(thresholds?.yearlyUpPercent, thresholds?.yearlyDownPercent),
  }
}
export const numOrNull = (value: string) => value === '' ? null : Number(value)

export function WatchlistModal({ item, onClose, onSaved }: { item: WatchlistEntry | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => ({
    name: item?.name ?? '', tickerSymbol: item?.tickerSymbol ?? '', notes: item?.notes ?? '',
    price: item?.currentValue != null ? String(item.currentValue) : '',
  }))
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))
  // Pull the name (and a first price) from the ticker, like a market-linked holding does.
  useEffect(() => {
    const symbol = form.tickerSymbol.trim()
    if (!symbol) return
    const timer = setTimeout(() => {
      api<MarketQuote>(`/api/market/quote?symbol=${encodeURIComponent(symbol)}`)
        .then(q => setForm(current => {
          if (current.tickerSymbol.trim().toUpperCase() !== symbol.toUpperCase()) return current
          const next = { ...current }
          if (q.name && (!current.name.trim() || current.name === current.tickerSymbol)) next.name = q.name
          if (q.price && !current.price) next.price = String(q.price)
          return next
        }))
        .catch(() => { /* leave fields as-is */ })
    }, 400)
    return () => clearTimeout(timer)
  }, [form.tickerSymbol])
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    try {
      if (item) {
        await api(`/api/watchlist/${item.id}`, { method: 'PUT', body: JSON.stringify({
          name: form.name, tickerSymbol: form.tickerSymbol || null, notes: form.notes || null,
        }) })
        if (form.price !== '' && Number(form.price) !== item.currentValue) {
          await api(`/api/watchlist/${item.id}/price`, { method: 'POST', body: JSON.stringify({ price: Number(form.price) }) })
        }
      } else {
        await api('/api/watchlist', { method: 'POST', body: JSON.stringify({
          name: form.name, tickerSymbol: form.tickerSymbol || null, notes: form.notes || null, price: Number(form.price),
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
        <Field label="Ticker" wide><SymbolSearchInput value={form.tickerSymbol} onChange={v => set('tickerSymbol', v)} /></Field>
        <Field label="Name" required wide><input required maxLength={128} value={form.name} onChange={e => set('name', e.target.value)} placeholder="Filled from the ticker, or type your own" /></Field>
        <Field label={item ? 'Update price' : 'Current price'} required={!item}>
          <input type="number" min="0" step="any" required={!item} value={form.price} onChange={e => set('price', e.target.value)} />
        </Field>
        <Field label="Notes" wide><textarea maxLength={1024} value={form.notes} onChange={e => set('notes', e.target.value)} placeholder="Optional notes" /></Field>
      </div>
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving}>{saving ? 'Saving…' : item ? 'Save changes' : 'Add to watchlist'}</button></div>
    </form>
  </section></div>
}
