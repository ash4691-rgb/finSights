import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { money, since, ago, toggleLabel, periodLabels, periodFields, numberLocale } from '../util'
import { Field, SymbolSearchInput, InfoTip, useEscToClose } from '../ui'
import { useChartHover } from '../charts'
import type { Settings, Dashboard, ActionItem, DeferredAction, TopMover, MovementThresholds, WatchlistEntry, PeriodKey, MarketQuote, ChartRange, MarketHistory, Category, NewsItem } from '../types'

// ---------------------------------------------------------------------------

// Shown to users as "Signals" — a terminal-style split-attention layout: a wide Main column
// (Tasks needing a decision, then Movers worth a look) and a narrow Side column (Radar — a
// passive feed: lower-urgency flags, upcoming EMI/interest payouts, and snoozed items). Fixed,
// not drag/resizable — Edit Layout is Overview-only now; see App.tsx's canEditLayout.
export function InsightsView({ displayCurrency, dataVersion, settings, dashboard, reload, onOpen }: {
  displayCurrency: string; dataVersion: number; settings: Settings; dashboard: Dashboard; reload: () => Promise<void>; onOpen: (id: string) => void
}) {
  // Signals' sections each load from their own endpoint, independently — a slow or failing one
  // (News, reading an external-feed-backed cache, is the likeliest candidate) never blocks the
  // others from rendering. null = "still loading" (matches the existing topMovers convention
  // below); [] = "loaded, nothing there."
  const [actions, setActions] = useState<ActionItem[] | null>(null)
  const [upcoming, setUpcoming] = useState<ActionItem[] | null>(null)
  const [deferred, setDeferred] = useState<DeferredAction[] | null>(null)
  const [news, setNews] = useState<NewsItem[] | null>(null)
  const [error, setError] = useState('')
  const [topMovers, setTopMovers] = useState<TopMover[] | null>(null)
  const [watchlist, setWatchlist] = useState<WatchlistEntry[]>([])
  const [thresholds, setThresholds] = useState(() => thresholdForm(null))
  const [savingThresholds, setSavingThresholds] = useState(false)
  const [addingWatch, setAddingWatch] = useState(false)
  const [editingWatch, setEditingWatch] = useState<WatchlistEntry | null>(null)
  const [viewingChart, setViewingChart] = useState<{ symbol: string; name: string; currency?: string; triggered?: TopMover['triggered'] } | null>(null)
  const [watchlistOpen, setWatchlistOpen] = useState(false)
  // Only needed for the BROKER_SYNC_NEW action card's category picker — fetched lazily (once)
  // rather than threaded down as a prop, since most visits to this page never need it.
  const [categories, setCategories] = useState<Category[] | null>(null)
  const [pickedCategory, setPickedCategory] = useState<Record<string, string>>({})
  const [resolvingPending, setResolvingPending] = useState<string | null>(null)
  const [pendingError, setPendingError] = useState<Record<string, string>>({})

  const loadActions = () => api<ActionItem[]>(`/api/insights/actions?currency=${displayCurrency}`).then(setActions).catch(() => setActions([]))
  const loadUpcoming = () => api<ActionItem[]>('/api/insights/upcoming').then(setUpcoming).catch(() => setUpcoming([]))
  const loadDeferred = () => api<DeferredAction[]>('/api/insights/deferred').then(setDeferred).catch(() => setDeferred([]))
  const loadNews = () => api<NewsItem[]>('/api/insights/news').then(setNews).catch(() => setNews([]))
  const loadTopMovers = () => api<TopMover[]>(`/api/insights/top-movers?currency=${displayCurrency}`).then(setTopMovers).catch(() => setTopMovers([]))
  const loadWatchlist = () => api<WatchlistEntry[]>(`/api/watchlist?currency=${displayCurrency}`).then(setWatchlist).catch(() => setWatchlist([]))
  const loadThresholds = () => api<MovementThresholds>('/api/insights/thresholds').then(t => setThresholds(thresholdForm(t))).catch(() => { /* leave defaults */ })
  useEffect(() => { void loadActions() }, [displayCurrency, dataVersion])
  useEffect(() => { void loadUpcoming() }, [dataVersion])
  useEffect(() => { void loadDeferred() }, [dataVersion])
  useEffect(() => { void loadNews() }, [dataVersion])
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

  const thresholdsSet = Object.values(thresholds).filter(value => value.trim() !== '').length

  const isPendingAction = (kind: string) =>
    kind === 'EMI_DUE' || kind === 'EMI_OVERDUE' || kind === 'INTEREST_DUE' || kind === 'INTEREST_OVERDUE'
    || kind === 'BROKER_SYNC_NEW' || kind === 'BROKER_SYNC_CONFLICT' || kind === 'MANUAL_VALUATION_REVIEW' || kind === 'PROVIDENT_FUND_CONTRIBUTION'
  const pendingActions = (actions ?? []).filter(a => isPendingAction(a.kind))
  const radarItems = (actions ?? []).filter(a => !isPendingAction(a.kind))
  // Dismissing/restoring only ever changes Tasks/Worth-a-look and Snoozed — reload just those two
  // sections rather than the parent's full-page reload(), which EMI/interest logging and
  // broker-sync resolution below still use since those actually change holdings/net worth.
  const dismissAction = async (key: string, status: 'DONE' | 'DEFERRED' | 'DELETED') => {
    try { await api('/api/insights/actions', { method: 'POST', body: JSON.stringify({ key, status }) }); await Promise.all([loadActions(), loadDeferred()]) }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not update that item') }
  }
  const ensureCategoriesLoaded = () => {
    if (categories === null) api<Category[]>('/api/categories').then(setCategories).catch(() => setCategories([]))
  }
  const createFromPending = async (pendingId: string) => {
    const categoryId = pickedCategory[pendingId]
    if (!categoryId) { setPendingError(prev => ({ ...prev, [pendingId]: 'Pick a category first' })); return }
    setPendingError(prev => ({ ...prev, [pendingId]: '' })); setResolvingPending(pendingId)
    try {
      await api(`/api/pending-broker-holdings/${pendingId}/create`, { method: 'POST', body: JSON.stringify({ categoryId }) })
      await reload()
    } catch (err) {
      setPendingError(prev => ({ ...prev, [pendingId]: err instanceof Error ? err.message : 'Could not create this holding' }))
    } finally { setResolvingPending(null) }
  }
  const linkPending = async (pendingId: string) => {
    setPendingError(prev => ({ ...prev, [pendingId]: '' })); setResolvingPending(pendingId)
    try {
      await api(`/api/pending-broker-holdings/${pendingId}/link`, { method: 'POST' })
      await reload()
    } catch (err) {
      setPendingError(prev => ({ ...prev, [pendingId]: err instanceof Error ? err.message : 'Could not link this holding' }))
    } finally { setResolvingPending(null) }
  }
  const restoreDeferred = async (key: string) => {
    try { await api(`/api/insights/actions?key=${encodeURIComponent(key)}`, { method: 'DELETE' }); await Promise.all([loadActions(), loadDeferred()]) }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not restore that item') }
  }
  // Star acts on the ticker — boosts every headline for that symbol to the top (see
  // NewsService#forUser's ranking). Cross acts on just this one article: it's dismissed for good
  // (won't resurface on reload) but the ticker itself stays fully visible — no ticker-level
  // blocking happens here. Both only ever change News, so just reload that section.
  const toggleStarTicker = async (n: NewsItem) => {
    if (!n.tickerSymbol) return
    try { await api(`/api/insights/news/${encodeURIComponent(n.tickerSymbol)}/star`, { method: n.starred ? 'DELETE' : 'POST' }); await loadNews() }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not update that ticker') }
  }
  const dismissNewsItem = async (n: NewsItem) => {
    try { await api('/api/insights/news/dismiss', { method: 'POST', body: JSON.stringify({ link: n.link }) }); await loadNews() }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not dismiss that article') }
  }

  // Shared by Tasks (pendingActions) and Radar's lower-urgency items — same row, same tools
  // (done / snooze / dismiss, plus the inline "Log repayment"/"Log interest" for EMI/interest
  // kinds, and the broker-sync resolution controls), but `neutral` drops the amber severity badge
  // for Radar: colour there is reserved for nothing at all — Radar is a passive feed to read, not
  // a signal to react to.
  const renderActionRow = (a: ActionItem, neutral?: boolean) => {
    const isEmi = a.kind === 'EMI_DUE' || a.kind === 'EMI_OVERDUE'
    const isInterest = a.kind === 'INTEREST_DUE' || a.kind === 'INTEREST_OVERDUE'
    const isContribution = a.kind === 'PROVIDENT_FUND_CONTRIBUTION'
    const isBrokerSyncNew = a.kind === 'BROKER_SYNC_NEW'
    const isBrokerSyncConflict = a.kind === 'BROKER_SYNC_CONFLICT'
    const actionLabels: Record<string, string> = { EMI_DUE: 'Log repayment', EMI_OVERDUE: 'Log repayment', PROVIDENT_FUND_CONTRIBUTION: 'Confirm contribution' }
    const actionLabel = actionLabels[a.kind] ?? 'Log interest'
    return <div key={a.key} className={`dense-row signal-row${neutral ? '' : ` ${a.severity.toLowerCase()}`}`}>
      {a.broker && <span className="source-chip" title={a.broker}>{initials(a.broker)}</span>}
      <button className="signal-row-body" onClick={() => { if (a.holdingId) onOpen(a.holdingId); else if (isBrokerSyncNew) ensureCategoriesLoaded() }}>
        <strong>{a.title}</strong><span>{a.detail}</span>
      </button>
      {(isEmi || isInterest || isContribution) && a.holdingId && a.period && <button className="warning-action" onClick={async e => {
        e.stopPropagation()
        const urls: Record<string, string> = {
          EMI_DUE: `/api/emis/${a.holdingId}/pay?dueDate=${a.period}`,
          EMI_OVERDUE: `/api/emis/${a.holdingId}/pay?dueDate=${a.period}`,
          PROVIDENT_FUND_CONTRIBUTION: `/api/provident-fund/${a.holdingId}/contributions/confirm?month=${a.period}`,
        }
        const url = urls[a.kind] ?? `/api/interest-payouts/${a.holdingId}/confirm?dueDate=${a.period}`
        try { await api(url, { method: 'POST' }); await reload() }
        catch (err) { setError(err instanceof Error ? err.message : 'Could not record that') }
      }}>{actionLabel}</button>}
      {isBrokerSyncConflict && a.pendingId && <button className="warning-action" onClick={async e => {
        e.stopPropagation(); await linkPending(a.pendingId!)
      }}>{resolvingPending === a.pendingId ? 'Linking…' : 'Link to this holding'}</button>}
      {isBrokerSyncNew && a.pendingId && <div className="broker-sync-new-action" onClick={e => e.stopPropagation()}>
        <select value={pickedCategory[a.pendingId] ?? ''} onFocus={ensureCategoriesLoaded}
          onChange={e => setPickedCategory(prev => ({ ...prev, [a.pendingId!]: e.target.value }))}>
          <option value="">Pick a category…</option>
          {(categories ?? []).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <button onClick={() => createFromPending(a.pendingId!)}>{resolvingPending === a.pendingId ? 'Creating…' : 'Create holding'}</button>
        {pendingError[a.pendingId] && <small className="form-error">{pendingError[a.pendingId]}</small>}
      </div>}
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

  return <>
    {error && <p className="form-error">{error}</p>}
    <div className="signals-grid">
    <div className="signals-main">
      <section className="panel signals-tasks">
        <div className="panel-heading"><h3>📥 Tasks <InfoTip text="EMIs and interest payouts due or overdue right now — log them here or from the holding itself." /></h3><span>{actions === null ? '' : `${pendingActions.length} item${pendingActions.length === 1 ? '' : 's'}`}</span></div>
        {actions === null ? <p className="hint">Loading tasks…</p>
          : pendingActions.length
          ? <div className="dense-list">{pendingActions.map(a => renderActionRow(a))}</div>
          : <p className="hint">Nothing to log right now — you're caught up.</p>}
      </section>

      <section className="panel signals-movers">
        <div className="panel-heading hot-picks-heading">
          <h3>📈 Movers <InfoTip text="Market-linked holdings and watchlist symbols whose price has moved past the % you set here for that lookback window, checked daily through yearly. Click any entry for its price history and which windows it broke." /></h3>
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
        {topMovers === null ? <p className="hint">Loading movers…</p> : topMovers.length ? <div className="dense-list">
          {topMovers.map(pick => <button key={`${pick.subjectType}-${pick.id}`} className={`dense-row top-mover${pick.tickerSymbol ? '' : ' static'}`}
              onClick={() => pick.tickerSymbol && setViewingChart({ symbol: pick.tickerSymbol, name: pick.name, currency: pick.currency, triggered: pick.triggered })}>
            <div className="top-mover-name"><strong>{pick.name}</strong><small>{pick.categoryName || pick.tickerSymbol || 'Watchlist'}</small></div>
            <div className="top-mover-value">{pick.currency ? money(pick.currentValue ?? 0, pick.currency) : (pick.currentValue ?? 0).toLocaleString(numberLocale)}</div>
            <div className="top-mover-badges">{pick.triggered.map(t => <span key={t.period} className={`mover-badge ${t.percent >= 0 ? 'positive' : 'negative'}`}>{periodLabels[t.period]} {t.percent >= 0 ? '+' : ''}{t.percent.toFixed(2)}%</span>)}</div>
          </button>)}
        </div> : thresholdsSet === 0
          ? <p className="hint">No thresholds set yet — set one above to choose how far a market-linked holding or watchlist symbol has to move before it lands here.</p>
          : <p className="hint">Nothing is outside your configured thresholds right now.</p>}

        <button className="action-col-head action-col-toggle watchlist-toggle" onClick={() => setWatchlistOpen(open => !open)} aria-expanded={watchlistOpen}>
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
          </table></div> : <p className="hint">Track a symbol you don't hold — like an index or a stock you're watching — to get it into Movers too.</p>}
        </>}
      </section>
    </div>

    <aside className="signals-side">
      <section className="panel signals-radar">
        <div className="panel-heading"><h3>👀 Radar <InfoTip text="A passive feed, not an inbox — lower-urgency flags, EMI/interest payouts coming up beyond the immediate window, and anything you've snoozed." /></h3></div>
        {news === null && actions === null && upcoming === null && deferred === null
          ? <p className="hint">Loading radar…</p>
          : <>
            {news === null ? <p className="hint">Loading news…</p> : news.length > 0 && <div className="radar-group">
              <h5 className="radar-group-label">News</h5>
              <div className="dense-list">{news.map(n => <div key={n.link} className="dense-row signal-row">
                <span className="source-chip" title={n.name}>{initials(n.name)}</span>
                <a className="signal-row-body" href={n.link} target="_blank" rel="noopener noreferrer">
                  <strong>{n.title}</strong><span>{n.source} · {ago(n.publishedAt)}</span>
                </a>
                <div className="warning-tools">
                  <button className={`warning-tool star${n.starred ? ' active' : ''}`}
                    title={n.starred ? 'Unstar this ticker' : "Star this ticker — boosts its news to the top"}
                    aria-label={n.starred ? 'Unstar' : 'Star'} onClick={() => void toggleStarTicker(n)}>{n.starred ? '★' : '☆'}</button>
                  <button className="warning-tool delete" title="Dismiss this article — won't come back" aria-label="Dismiss"
                    onClick={() => void dismissNewsItem(n)}>✕</button>
                </div>
              </div>)}</div>
            </div>}
            {actions !== null && radarItems.length > 0 && <div className="radar-group">
              <h5 className="radar-group-label">Worth a look</h5>
              <div className="dense-list">{radarItems.map(a => renderActionRow(a, true))}</div>
            </div>}
            {upcoming === null ? <p className="hint">Loading upcoming…</p> : upcoming.length > 0 && <div className="radar-group">
              <h5 className="radar-group-label">Upcoming</h5>
              <div className="dense-list">{upcoming.map(a => <button key={a.key} className="dense-row signal-row" onClick={() => a.holdingId && onOpen(a.holdingId)}>
                {a.broker && <span className="source-chip" title={a.broker}>{initials(a.broker)}</span>}
                <div className="signal-row-body"><strong>{a.title}</strong><span>{a.detail}</span></div>
              </button>)}</div>
            </div>}
            {deferred === null ? <p className="hint">Loading snoozed…</p> : deferred.length > 0 && <div className="radar-group">
              <h5 className="radar-group-label">Snoozed</h5>
              <div className="dense-list">{deferred.map(d => <div key={d.key} className="dense-row signal-row">
                <div className="signal-row-body"><strong>{d.label} — {d.holdingName}</strong><span>Snoozed until {since(d.deferredUntil)}</span></div>
                <button className="warning-tool delete" title="Un-snooze" aria-label="Un-snooze" onClick={() => void restoreDeferred(d.key)}>✕</button>
              </div>)}</div>
            </div>}
            {news !== null && actions !== null && upcoming !== null && deferred !== null
              && !news.length && !radarItems.length && !upcoming.length && !deferred.length
              && <p className="hint">Nothing on your radar right now.</p>}
          </>}
      </section>
    </aside>

    {(addingWatch || editingWatch) && <WatchlistModal item={editingWatch}
      onClose={() => { setAddingWatch(false); setEditingWatch(null) }}
      onSaved={() => { setAddingWatch(false); setEditingWatch(null); void loadWatchlist(); void loadTopMovers() }} />}
    {viewingChart && <ViewMoverItemModal symbol={viewingChart.symbol} name={viewingChart.name} currency={viewingChart.currency} triggered={viewingChart.triggered}
      onClose={() => setViewingChart(null)} />}
    </div>
  </>
}

// First letter of each of the first two words, or the first two letters of a one-word name —
// a lightweight stand-in for a broker/bank logo (no icon assets exist for real institution
// logos — see BrokersView's identical treatment for connected-broker tiles).
function initials(name: string): string {
  const words = name.trim().split(/\s+/)
  return (words.length > 1 ? words[0][0] + words[1][0] : name.slice(0, 2)).toUpperCase()
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
// market-linked — a Movers entry or a watchlist item alike. Backed by /api/market/history,
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
