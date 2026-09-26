import { useEffect, useRef, useState } from 'react'
import { api, API_URL } from './api'
import { applyLocale, currencies, nav, downloadCsv, label, rate, ago, THEME_KEY, initialTheme } from './util'
import { clearPageLayout, createPanel, flushPageSave, hasNoPersistedSections, hydrateLayouts, LayoutMenu } from './layout'
import { ONBOARDING_DEMO_WIDGETS, PAGE_LAYOUT, sectionsZoneKeyFor } from './layout-config'
import { fetchLayouts } from './layout-api'
import { CustomLayoutOnboarding } from './custom-layout-onboarding'
import { UserOnboarding } from './user-onboarding'
import { GokuNavButton, GokuPanel, useGoku } from './goku'
import type { Page, Dashboard, Category, Holding, User, Settings, Country, FxRates, Theme } from './types'
import { DashboardView } from './pages/DashboardView'
import { CategoriesView, CategoryDrawer, CategoryModal } from './pages/CategoriesView'
import { HoldingsView, HoldingModal, HoldingDrawer } from './pages/HoldingsView'
import { TransactionsView, ImportModal } from './pages/TransactionsView'
import { InsightsView } from './pages/InsightsView'
import { BrokersView } from './pages/BrokersView'
import { SettingsView } from './pages/SettingsView'

// Which independently-fetched piece of the bootstrap failed, keyed by name, with its message —
// so one resource's failure never has to blank out the whole app, only the page(s) that need it.
type LoadErrors = Partial<Record<'settings' | 'fx' | 'countries' | 'dashboard' | 'categories' | 'holdings', string>>

// Inline, page-scoped stand-in for whatever couldn't load — the rest of the app (nav, other
// pages) stays fully usable around it.
function SectionError({ what, message, onRetry }: { what: string; message?: string; onRetry: () => void }) {
  return <div className="section-error">
    <p><strong>Couldn't load {what}.</strong> {message ?? 'Something went wrong.'}</p>
    <button className="outline" onClick={onRetry}>Retry</button>
  </div>
}

export function App({ onSignOut }: { onSignOut: () => void }) {
  const [page, setPage] = useState<Page>('dashboard')
  const [dashboard, setDashboard] = useState<Dashboard | null>(null)
  const [categories, setCategories] = useState<Category[]>([])
  const [holdings, setHoldings] = useState<Holding[]>([])
  const [user, setUser] = useState<User | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [loading, setLoading] = useState(true)
  // Only auth/me failing blocks the whole app — nothing can render without knowing who's signed
  // in. Every other resource fetched below fails independently into loadErrors instead.
  const [error, setError] = useState('')
  const [loadErrors, setLoadErrors] = useState<LoadErrors>({})
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
  const [fxAsOf, setFxAsOf] = useState<string | undefined>(undefined)
  const [countries, setCountries] = useState<Country[]>([])
  const [dataVersion, setDataVersion] = useState(0)
  const [showImport, setShowImport] = useState(false)
  const [exportingTransactions, setExportingTransactions] = useState(false)
  const [layoutEditing, setLayoutEditing] = useState(false)
  const [layoutNonce, setLayoutNonce] = useState(0)
  const [showLayoutOnboarding, setShowLayoutOnboarding] = useState(false)
  const [showUserOnboarding, setShowUserOnboarding] = useState(false)
  const bootstrapped = useRef(false)
  const goku = useGoku()

  // Leaving a page always drops out of layout-edit mode.
  useEffect(() => { setLayoutEditing(false) }, [page])
  const canEditLayout = page === 'dashboard' || page === 'insights' || page === 'brokers'

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    try { localStorage.setItem(THEME_KEY, theme) } catch { /* storage unavailable */ }
  }, [theme])

  // Only the very first call blocks the screen with the full-page spinner — every later reload
  // (after adding a holding, importing a file, switching currency, …) updates data quietly in
  // the background so it doesn't unmount whatever the user has open (a modal, a drawer)
  // mid-interaction.
  //
  // auth/me is the one call the whole shell depends on (we can't render anything without knowing
  // who's signed in), so it alone can still show the full-page "could not connect" screen. Every
  // other resource is fetched independently via Promise.allSettled and applied to its own piece
  // of state regardless of what else failed — a bad record poisoning one endpoint (say
  // /api/dashboard) no longer blanks pages that don't need it (Holdings, Transactions, …). A
  // resource that fails keeps whatever it last had (never nulled out) and records why in
  // loadErrors, which pages read to show an inline "couldn't load — retry" instead of nothing.
  const load = async (currency?: string) => {
    const isFirstLoad = !bootstrapped.current
    if (isFirstLoad) { setLoading(true); setError('') }
    const cur = currency ?? displayCurrency

    let me: User
    try {
      me = await api<User>('/api/auth/me')
    } catch (err) {
      if (isFirstLoad) { setError(err instanceof Error ? err.message : 'Unable to load the portfolio'); setLoading(false) }
      return
    }
    setUser(me)

    const [settingsR, fxR, countriesR, dashboardR, categoriesR, holdingsR, layoutsR] = await Promise.allSettled([
      api<Settings>('/api/settings'), api<FxRates>('/api/fx-rates'), api<Country[]>('/api/countries'),
      api<Dashboard>(`/api/dashboard?currency=${cur}`), api<Category[]>(`/api/categories?currency=${cur}`),
      api<Holding[]>(`/api/holdings?currency=${cur}`), fetchLayouts(),
    ])
    const errors: LoadErrors = {}
    const reason = (r: PromiseRejectedResult) => r.reason instanceof Error ? r.reason.message : 'Something went wrong'

    let nextSettings: Settings | undefined
    if (settingsR.status === 'fulfilled') { nextSettings = settingsR.value; applyLocale(cur, nextSettings.numberFormat); setSettings(nextSettings) }
    else errors.settings = reason(settingsR)

    if (fxR.status === 'fulfilled') { setFxCurrencies(Object.keys(fxR.value.ratesToBase).sort()); setFxRatesToBase(fxR.value.ratesToBase); setFxAsOf(fxR.value.asOf) }
    else errors.fx = reason(fxR)

    if (countriesR.status === 'fulfilled') setCountries(countriesR.value)
    else errors.countries = reason(countriesR)

    if (dashboardR.status === 'fulfilled') setDashboard(dashboardR.value)
    else errors.dashboard = reason(dashboardR)

    if (categoriesR.status === 'fulfilled') setCategories(categoriesR.value)
    else errors.categories = reason(categoriesR)

    if (holdingsR.status === 'fulfilled') setHoldings(holdingsR.value)
    else errors.holdings = reason(holdingsR)

    if (layoutsR.status === 'fulfilled') hydrateLayouts(layoutsR.value)

    setDisplayCurrency(cur)
    setLoadErrors(errors)
    setDataVersion(v => v + 1)
    setLayoutNonce(n => n + 1)
    if (isFirstLoad) {
      bootstrapped.current = true
      setLoading(false)
      if (nextSettings && !nextSettings.userOnboardingDismissed) setShowUserOnboarding(true)
      if (!currency && nextSettings?.baseCurrency && nextSettings.baseCurrency !== cur) {
        void load(nextSettings.baseCurrency)
      }
    }
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
      <nav>
        {nav.map(([key, icon, text]) => <button key={key} className={page === key ? 'active' : ''} onClick={() => setPage(key)}><span>{icon}</span> {text}</button>)}
        {goku.available && <GokuNavButton goku={goku} />}
      </nav>
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
            <label className="currency-picker" title="Convert every figure on this page into another currency (live rate, refreshed at most every 20 minutes)">
              <span>View in</span>
              <select value={displayCurrency} onChange={e => void load(e.target.value)}>{fxCurrencies.map(c => <option key={c}>{c}</option>)}</select>
            </label>
            {settings && displayCurrency !== settings.baseCurrency && fxRatesToBase[displayCurrency] && fxRatesToBase[settings.baseCurrency] &&
              <span className="fx-note" title={`Live market rate${fxAsOf ? `, updated ${ago(fxAsOf)}` : ''} — a currency the feed can't reach falls back to its last known rate`}>
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
            <button className="tool-action" onClick={() => setLayoutEditing(e => {
              const next = !e
              if (next) {
                // A page whose widgets zone ships no seed (Insights, since it dropped its own —
                // see layout-config.ts) gets the tour's own demo section, once ever.
                const zoneKey = sectionsZoneKeyFor(page)
                const config = zoneKey ? PAGE_LAYOUT[page as keyof typeof PAGE_LAYOUT]?.[zoneKey] : undefined
                if (config?.kind === 'sections' && config.seed.length === 0 && hasNoPersistedSections(page)) {
                  createPanel(page, 'Demo section', ONBOARDING_DEMO_WIDGETS)
                  setLayoutNonce(n => n + 1)
                }
                if (settings && !settings.customLayoutOnboardingDismissed) setShowLayoutOnboarding(true)
              }
              return next
            })}>{layoutEditing ? '✓ Done' : '⤢ Edit layout'}</button>
          </>}
        </div>
      </header>
      {user?.demoMode && <div className="demo-banner"><strong>Demo mode</strong><span>Local data is saved in the backend. Configure Google OAuth before deployment.</span></div>}
      {page === 'dashboard' && (dashboard
        ? <DashboardView dashboard={dashboard} holdings={holdings} onManage={() => setPage('categories')} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />
        : <SectionError what="your dashboard" message={loadErrors.dashboard} onRetry={() => void load()} />)}
      {page === 'categories' && (loadErrors.categories
        ? <SectionError what="categories" message={loadErrors.categories} onRetry={() => void load()} />
        : <CategoriesView categories={categories} onOpen={setCategoryDetail} onEdit={setEditingCategory} onAdd={() => setCreatingCategory(true)} reload={load} />)}
      {page === 'holdings' && (loadErrors.holdings
        ? <SectionError what="your holdings" message={loadErrors.holdings} onRetry={() => void load()} />
        : <HoldingsView holdings={holdings} categories={categories} reload={load} onEdit={setEditingHolding} onAdd={() => setCreatingHolding(true)} onOpen={setHoldingDetail} />)}
      {page === 'transactions' && <TransactionsView holdings={holdings} displayCurrency={displayCurrency} fxRatesToBase={fxRatesToBase} dataVersion={dataVersion} reload={load} />}
      {page === 'insights' && (settings && dashboard
        ? <InsightsView displayCurrency={displayCurrency} dataVersion={dataVersion} settings={settings} dashboard={dashboard} reload={load} onOpen={id => setHoldingDetail(holdings.find(h => h.id === id) ?? null)} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />
        : <SectionError what="insights" message={loadErrors.dashboard ?? loadErrors.settings} onRetry={() => void load()} />)}
      {page === 'brokers' && <BrokersView displayCurrency={displayCurrency} dataVersion={dataVersion} layoutEditing={layoutEditing} layoutNonce={layoutNonce} />}
      {page === 'settings' && (settings
        ? <SettingsView settings={settings} countries={countries} dashboard={dashboard} holdings={holdings} reload={load} theme={theme} setTheme={setTheme} />
        : <SectionError what="settings" message={loadErrors.settings} onRetry={() => void load()} />)}
    </main>
    {(creatingCategory || editingCategory) && <CategoryModal category={editingCategory} holdings={holdings} onClose={() => { setCreatingCategory(false); setEditingCategory(null) }} onSaved={() => { setCreatingCategory(false); setEditingCategory(null); void load() }} />}
    {(creatingHolding || creatingHoldingFor || editingHolding) && <HoldingModal holding={editingHolding} category={creatingHoldingFor} categories={categories} holdings={holdings} onClose={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null) }} onSaved={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null); void load() }} onGoToTransactions={() => setPage('transactions')} />}
    {categoryDetail && <CategoryDrawer category={categoryDetail} holdings={holdings.filter(h => h.categoryId === categoryDetail.id)} onClose={() => setCategoryDetail(null)} onEdit={c => { setCategoryDetail(null); setEditingCategory(c) }} onAddHolding={c => { setCategoryDetail(null); setCreatingHoldingFor(c) }} onOpenHolding={h => { setCategoryDetail(null); setHoldingDetail(h) }} reload={load} />}
    {holdingDetail && <HoldingDrawer holding={holdingDetail} displayCurrency={displayCurrency} fxRatesToBase={fxRatesToBase} onClose={() => setHoldingDetail(null)} onEdit={h => { setHoldingDetail(null); setEditingHolding(h) }} reload={load} />}
    {showImport && <ImportModal onClose={() => setShowImport(false)} onImported={() => void load()} />}
    {showLayoutOnboarding && <CustomLayoutOnboarding
      onClose={() => setShowLayoutOnboarding(false)}
      onDismissForever={() => setSettings(s => s ? { ...s, customLayoutOnboardingDismissed: true } : s)} />}
    {showUserOnboarding && <UserOnboarding
      onClose={() => setShowUserOnboarding(false)}
      onDismissForever={() => setSettings(s => s ? { ...s, userOnboardingDismissed: true } : s)} />}
    <GokuPanel goku={goku} />
  </div>
}
