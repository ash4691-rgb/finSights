import { useEffect, useRef, useState } from 'react'
import { api, API_URL } from './api'
import { applyLocale, currencies, nav, downloadCsv, label, rate, THEME_KEY, initialTheme } from './util'
import { clearPageLayout, createPanel, flushPageSave, hydrateLayouts, LayoutMenu } from './layout'
import { fetchLayouts } from './layout-api'
import type { Page, Dashboard, Category, Holding, User, Settings, Country, FxRates, Theme } from './types'
import { DashboardView } from './pages/DashboardView'
import { CategoriesView, CategoryDrawer, CategoryModal } from './pages/CategoriesView'
import { HoldingsView, HoldingModal, HoldingDrawer } from './pages/HoldingsView'
import { TransactionsView, ImportModal } from './pages/TransactionsView'
import { InsightsView } from './pages/InsightsView'
import { BrokersView } from './pages/BrokersView'
import { SettingsView } from './pages/SettingsView'

export function App({ onSignOut }: { onSignOut: () => void }) {
  const [page, setPage] = useState<Page>('dashboard')
  const [dashboard, setDashboard] = useState<Dashboard | null>(null)
  const [categories, setCategories] = useState<Category[]>([])
  const [holdings, setHoldings] = useState<Holding[]>([])
  const [user, setUser] = useState<User | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editingHolding, setEditingHolding] = useState<Holding | null>(null)
  const [creatingHolding, setCreatingHolding] = useState(false)
  // Optional category to pre-select and lock when adding a holding from within a category drawer.
  const [creatingHoldingFor, setCreatingHoldingFor] = useState<Category | null>(null)
  const [editingCategory, setEditingCategory] = useState<Category | null>(null)
  const [creatingCategory, setCreatingCategory] = useState(false)
  const [categoryDetail, setCategoryDetail] = useState<Category | null>(null)
  const [holdingDetail, setHoldingDetail] = useState<Holding | null>(null)
  const [theme, setTheme] = useState<Theme>(initialTheme)
  const [displayCurrency, setDisplayCurrency] = useState('INR')
  const [fxCurrencies, setFxCurrencies] = useState<string[]>(currencies)
  const [fxRatesToBase, setFxRatesToBase] = useState<Record<string, number>>({})
  const [countries, setCountries] = useState<Country[]>([])
  const [dataVersion, setDataVersion] = useState(0)
  const [showImport, setShowImport] = useState(false)
  const [exportingTransactions, setExportingTransactions] = useState(false)
  const [layoutEditing, setLayoutEditing] = useState(false)
  const [layoutNonce, setLayoutNonce] = useState(0)
  const bootstrapped = useRef(false)

  // Leaving a page always drops out of layout-edit mode.
  useEffect(() => { setLayoutEditing(false) }, [page])
  const canEditLayout = page === 'dashboard' || page === 'insights' || page === 'brokers'

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    try { localStorage.setItem(THEME_KEY, theme) } catch { /* storage unavailable */ }
  }, [theme])

  // Only the very first call blocks the screen with the full-page spinner/error — every
  // later reload (after adding a holding, importing a file, switching currency, …) updates
  // data quietly in the background so it doesn't unmount whatever the user has open (a modal,
  // a drawer) mid-interaction.
  const load = async (currency?: string) => {
    const isFirstLoad = !bootstrapped.current
    if (isFirstLoad) { setLoading(true); setError('') }
    try {
      const cur = currency ?? displayCurrency
      const [me, nextSettings, fx, nextCountries, nextDashboard, nextCategories, nextHoldings, nextLayouts] = await Promise.all([
        api<User>('/api/auth/me'), api<Settings>('/api/settings'), api<FxRates>('/api/fx-rates'), api<Country[]>('/api/countries'),
        api<Dashboard>(`/api/dashboard?currency=${cur}`), api<Category[]>(`/api/categories?currency=${cur}`),
        api<Holding[]>(`/api/holdings?currency=${cur}`), fetchLayouts(),
      ])

      applyLocale(cur, nextSettings.numberFormat)
      hydrateLayouts(nextLayouts)
      setUser(me); setSettings(nextSettings); setFxCurrencies(Object.keys(fx.ratesToBase).sort())
      setFxRatesToBase(fx.ratesToBase); setCountries(nextCountries)
      setDashboard(nextDashboard); setCategories(nextCategories); setHoldings(nextHoldings); setDisplayCurrency(cur)
      setDataVersion(v => v + 1)
      setLayoutNonce(n => n + 1)
      if (isFirstLoad) {
        bootstrapped.current = true
        if (!currency && nextSettings.baseCurrency && nextSettings.baseCurrency !== cur) {
          void load(nextSettings.baseCurrency)
          return
        }
      }
    } catch (err) {
      if (isFirstLoad) setError(err instanceof Error ? err.message : 'Unable to load the portfolio')
      else console.error('Background refresh failed', err)
    } finally { if (isFirstLoad) setLoading(false) }
  }
  useEffect(() => { void load() }, [])

  const exportCategoriesCsv = () => downloadCsv('finsights-categories.csv',
    ['Name', 'Type', 'Description', 'Invested', 'Current value', 'P/L', 'P/L %', 'Weightage %', 'Liquid amount', 'Liquid %', 'NPA amount', 'NPA %', 'Holdings'],
    categories.map(c => [c.name, label(c.kind), c.description ?? '', c.investedValue, c.currentValue, c.profitLoss, c.profitLossPercentage.toFixed(2),
      c.weightagePercent.toFixed(2), c.liquidAmount, c.liquidPercent.toFixed(2), c.npaAmount, c.npaPercent.toFixed(2), c.holdingCount]))

  const exportHoldingsCsv = () => downloadCsv('finsights-holdings.csv',
    ['Holding ID', 'Name', 'Category', 'Broker', 'Currency', 'Valuation method', 'Ticker', 'Quantity', 'Invested', 'Current value', 'Unrealised P/L', 'Unrealised P/L %', 'Realised P/L', 'Liquid', 'Blocked', 'Description', 'Tags'],
    holdings.map(h => [h.holdingId, h.name, h.categoryName, h.broker ?? '', h.currency, label(h.valuationMethod), h.tickerSymbol ?? '',
      h.quantity ?? '', h.investedValue, h.currentValue, h.profitLoss, h.profitLossPercentage.toFixed(2), h.realisedProfitLoss ?? 0,
      h.liquidWithinSevenDays ? 'Yes' : 'No', h.blocked ? 'Yes' : 'No', h.description ?? '', h.tags.join('; ')]))

  const exportTransactionsCsv = async () => {
    setExportingTransactions(true)
    try {
      const response = await fetch(`${API_URL}/api/transactions/export?format=csv`, { credentials: 'include', headers: { 'X-Demo-User': 'demo@finsights.local' } })
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.download = 'finsights-transactions.csv'; link.click()
      URL.revokeObjectURL(url)
    } finally { setExportingTransactions(false) }
  }

  const signOut = async () => {
    try { await api('/api/auth/logout', { method: 'POST' }) } catch { /* session may already be gone */ }
    onSignOut()
  }

  if (loading) return <div className="loading-screen"><div className="mark">F</div><p>Loading your portfolio</p></div>
  if (error) return <div className="loading-screen"><div className="mark">!</div><h2>FinSights could not connect</h2><p>{error}</p><button onClick={() => void load()}>Try again</button></div>

  const titles: Record<Page, string> = { dashboard: 'Portfolio overview', categories: 'Categories', holdings: 'Your holdings', transactions: 'Transactions', insights: 'Insights', brokers: 'External sources', settings: 'Settings' }

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><div className="mark">F</div><span>FinSights</span></div>
      <nav>{nav.map(([key, icon, text]) => <button key={key} className={page === key ? 'active' : ''} onClick={() => setPage(key)}><span>{icon}</span> {text}</button>)}</nav>
      <div className="sidebar-bottom">
        <div className="sync-note"><span className="dot" /> Manual tracking ready<br /><small>Broker sync is coming next</small></div>
        <div className="user"><div className="avatar">{user?.displayName?.slice(0, 1).toUpperCase()}</div><div><strong>{user?.displayName}</strong><small>{user?.demoMode ? 'Demo workspace' : user?.email}</small></div><button className="sign-out-link" onClick={() => void signOut()}>Sign out</button></div>
      </div>
    </aside>
    <main>
      <header>
        <div><p className="eyebrow">PERSONAL WEALTH</p><h1>{titles[page]}</h1></div>
        <div className="header-actions">
          {page !== 'settings' && <>
            <label className="currency-picker" title="Convert every figure on this page into another currency (static reference rates)">
              <span>View in</span>
              <select value={displayCurrency} onChange={e => void load(e.target.value)}>{fxCurrencies.map(c => <option key={c}>{c}</option>)}</select>
            </label>
            {settings && displayCurrency !== settings.baseCurrency && fxRatesToBase[displayCurrency] && fxRatesToBase[settings.baseCurrency] &&
              <span className="fx-note" title="Static reference rate, not a live market feed — see Settings for your base currency">
                1 {displayCurrency} ≈ {rate(fxRatesToBase[displayCurrency] / fxRatesToBase[settings.baseCurrency], settings.baseCurrency)}
              </span>}
          </>}
          {page === 'transactions' && <>
            <button className="tool-action" onClick={() => setShowImport(true)}>↑ Import</button>
            <button className="tool-action" onClick={() => void exportTransactionsCsv()} disabled={exportingTransactions}>{exportingTransactions ? 'Exporting…' : '↓ Export'}</button>
          </>}
          {page === 'categories' && <button className="tool-action" onClick={exportCategoriesCsv} disabled={!categories.length}>↓ Export</button>}
          {page === 'holdings' && <button className="tool-action" onClick={exportHoldingsCsv} disabled={!holdings.length}>↓ Export</button>}
          {canEditLayout && <>
            {layoutEditing && <LayoutMenu
              onCreatePanel={(title: string) => { createPanel(page, title); setLayoutNonce(n => n + 1) }}
              onSave={() => flushPageSave(page)}
              onReset={() => { clearPageLayout(page); setLayoutNonce(n => n + 1) }} />}
            <button className="tool-action" onClick={() => setLayoutEditing(e => !e)}>{layoutEditing ? '✓ Done' : '⤢ Edit layout'}</button>
          </>}
        </div>
      </header>
      {user?.demoMode && <div className="demo-banner"><strong>Demo mode</strong><span>Local data is saved in the backend. Configure Google OAuth before deployment.</span></div>}
      {page === 'dashboard' && dashboard && <DashboardView dashboard={dashboard} holdings={holdings} onManage={() => setPage('categories')} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />}
      {page === 'categories' && <CategoriesView categories={categories} onOpen={setCategoryDetail} onEdit={setEditingCategory} onAdd={() => setCreatingCategory(true)} reload={load} />}
      {page === 'holdings' && <HoldingsView holdings={holdings} categories={categories} reload={load} onEdit={setEditingHolding} onAdd={() => setCreatingHolding(true)} onOpen={setHoldingDetail} />}
      {page === 'transactions' && <TransactionsView holdings={holdings} displayCurrency={displayCurrency} dataVersion={dataVersion} reload={load} />}
      {page === 'insights' && settings && dashboard && <InsightsView displayCurrency={displayCurrency} dataVersion={dataVersion} settings={settings} dashboard={dashboard} reload={load} onOpen={id => setHoldingDetail(holdings.find(h => h.id === id) ?? null)} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />}
      {page === 'brokers' && <BrokersView displayCurrency={displayCurrency} dataVersion={dataVersion} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />}
      {page === 'settings' && settings && <SettingsView settings={settings} countries={countries} dashboard={dashboard} holdings={holdings} reload={load} theme={theme} setTheme={setTheme} />}
    </main>
    {(creatingCategory || editingCategory) && <CategoryModal category={editingCategory} holdings={holdings} onClose={() => { setCreatingCategory(false); setEditingCategory(null) }} onSaved={() => { setCreatingCategory(false); setEditingCategory(null); void load() }} />}
    {(creatingHolding || creatingHoldingFor || editingHolding) && <HoldingModal holding={editingHolding} category={creatingHoldingFor} categories={categories} holdings={holdings} onClose={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null) }} onSaved={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null); void load() }} onGoToTransactions={() => setPage('transactions')} />}
    {categoryDetail && <CategoryDrawer category={categoryDetail} holdings={holdings.filter(h => h.categoryId === categoryDetail.id)} onClose={() => setCategoryDetail(null)} onEdit={c => { setCategoryDetail(null); setEditingCategory(c) }} onAddHolding={c => { setCategoryDetail(null); setCreatingHoldingFor(c) }} onOpenHolding={h => { setCategoryDetail(null); setHoldingDetail(h) }} reload={load} />}
    {holdingDetail && <HoldingDrawer holding={holdingDetail} displayCurrency={displayCurrency} onClose={() => setHoldingDetail(null)} onEdit={h => { setHoldingDetail(null); setEditingHolding(h) }} />}
    {showImport && <ImportModal onClose={() => setShowImport(false)} onImported={() => void load()} />}
  </div>
}
