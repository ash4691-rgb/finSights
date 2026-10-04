import { useEffect, useRef, useState } from 'react'
import { api, ApiError, API_URL } from './api'
import { applyLocale, currencies, nav, downloadCsv, label, rate, ago, THEME_KEY, initialTheme } from './util'
import { clearPageLayout, createPanel, flushPageSave, hasNoPersistedSections, hydrateLayouts, LayoutMenu } from './layout'
import { ONBOARDING_DEMO_WIDGETS, PAGE_LAYOUT, sectionsZoneKeyFor } from './layout-config'
import { fetchLayouts } from './layout-api'
import { CustomLayoutOnboarding } from './custom-layout-onboarding'
import { UserOnboarding } from './user-onboarding'
import { TourNudge } from './tour-nudge'
import { PersonaOnboarding } from './persona-onboarding'
import { RiskAssessment } from './risk-assessment'
import { fetchPersona } from './persona-api'
import { GokuAdminButton, GokuAdminModal, GokuLauncher, GokuPanel, useGoku } from './goku'
import type { Page, Dashboard, Category, Holding, User, Settings, Country, FxRates, Theme, Persona } from './types'
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
  // Switching the "View in" currency re-runs the full load() — normally a quiet background
  // reload, but this one visibly changes every figure on the page, so unlike other reloads it
  // gets its own "Converting…" nudge instead of leaving the user looking at stale-currency
  // numbers with no sign anything is happening.
  const [convertingCurrency, setConvertingCurrency] = useState(false)
  const [countries, setCountries] = useState<Country[]>([])
  const [dataVersion, setDataVersion] = useState(0)
  const [showImport, setShowImport] = useState(false)
  const [exportingTransactions, setExportingTransactions] = useState(false)
  const [layoutEditing, setLayoutEditing] = useState(false)
  const [layoutNonce, setLayoutNonce] = useState(0)
  const [showLayoutOnboarding, setShowLayoutOnboarding] = useState(false)
  const [showUserOnboarding, setShowUserOnboarding] = useState(false)
  const [showPersonaOnboarding, setShowPersonaOnboarding] = useState(false)
  // The five-question risk assessment — shown automatically on a login after persona onboarding
  // is done (see load()'s first-load check below) but deliberately NOT chained into the same
  // sitting as persona onboarding itself; also reused as-is for Settings' "Reassess risk profile".
  const [showRiskAssessment, setShowRiskAssessment] = useState(false)
  // A small, non-blocking corner card offering the app-concepts tour — replaces auto-popping
  // UserOnboarding as a full-screen modal the instant persona onboarding ends or a returning
  // user logs in. Dismissing it (without "Don't show again" inside the tour itself) just hides
  // it for this session; it offers again next login, same as the tour's own Skip always did.
  const [showTourNudge, setShowTourNudge] = useState(false)
  const [persona, setPersona] = useState<Persona | null>(null)
  // Reopens PersonaOnboarding from Settings' "Start now" — separate from showPersonaOnboarding
  // so it never re-triggers the tour-nudge chain that follows the first-login flow.
  const [showPersonaOnboardingFromSettings, setShowPersonaOnboardingFromSettings] = useState(false)
  const bootstrapped = useRef(false)
  const goku = useGoku()

  // Leaving a page always drops out of layout-edit mode.
  useEffect(() => { setLayoutEditing(false) }, [page])
  const canEditLayout = page === 'dashboard' || page === 'insights'

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
      // 401 means the session itself is gone (expired, signed out elsewhere, or invalidated by
      // a backend restart) — no retry will fix that, so run the same sign-out flow as the
      // "Sign out" button instead of getting stuck on a "could not connect" error that just
      // 401s again on every "Try again".
      if (err instanceof ApiError && err.status === 401) { onSignOut(); return }
      if (isFirstLoad) { setError(err instanceof Error ? err.message : 'Unable to load the portfolio'); setLoading(false) }
      return
    }
    setUser(me)

    const [settingsR, fxR, countriesR, dashboardR, categoriesR, holdingsR, layoutsR, personaR] = await Promise.allSettled([
      api<Settings>('/api/settings'), api<FxRates>('/api/fx-rates'), api<Country[]>('/api/countries'),
      api<Dashboard>(`/api/dashboard?currency=${cur}`), api<Category[]>(`/api/categories?currency=${cur}`),
      api<Holding[]>(`/api/holdings?currency=${cur}`), fetchLayouts(), fetchPersona(),
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

    // Every load() also re-syncs whichever category/holding detail drawer is currently open (if
    // any) against these same fresh arrays — otherwise a drawer left open across some OTHER
    // change (an edit made elsewhere, a holding added to this category, a transaction logged
    // against this holding) stays frozen at whatever it showed when it was first opened, since
    // categoryDetail/holdingDetail are their own snapshots and no reload naturally touches them.
    if (categoriesR.status === 'fulfilled') {
      setCategories(categoriesR.value)
      setCategoryDetail(current => current ? categoriesR.value.find(c => c.id === current.id) ?? current : current)
    }
    else errors.categories = reason(categoriesR)

    if (holdingsR.status === 'fulfilled') {
      setHoldings(holdingsR.value)
      setHoldingDetail(current => current ? holdingsR.value.find(h => h.id === current.id) ?? current : current)
    }
    else errors.holdings = reason(holdingsR)

    if (layoutsR.status === 'fulfilled') hydrateLayouts(layoutsR.value)

    if (personaR.status === 'fulfilled') setPersona(personaR.value)

    setDisplayCurrency(cur)
    setLoadErrors(errors)
    setDataVersion(v => v + 1)
    setLayoutNonce(n => n + 1)
    if (isFirstLoad) {
      bootstrapped.current = true
      setLoading(false)
      // PersonaOnboarding comes first for a brand-new user. The five-question risk assessment
      // is deliberately a SEPARATE gate, not chained into that same sitting — it only shows up
      // here, on a later login, once persona onboarding is already dismissed. Either of those
      // closing offers the tour nudge next (see their own onClose/onDone below) rather than that
      // being decided here; a returning user past all three gates gets nothing further.
      if (nextSettings && !nextSettings.personaOnboardingDismissed) setShowPersonaOnboarding(true)
      else if (nextSettings && !nextSettings.riskOnboardingDismissed) setShowRiskAssessment(true)
      else if (nextSettings && !nextSettings.userOnboardingDismissed) setShowTourNudge(true)
      if (!currency && nextSettings?.baseCurrency && nextSettings.baseCurrency !== cur) {
        void load(nextSettings.baseCurrency)
      }
    }
  }
  useEffect(() => { void load() }, [])

  // Splices a just-created/edited holding into local state the instant its save resolves,
  // rather than waiting on the full reload below to see it — instant feedback for the one
  // thing we already know for certain (what the server just echoed back). load() still runs
  // right after to reconcile everything that isn't in this one holding: category/dashboard
  // aggregates, and a MARKET_PRICE holding's live price if it wasn't cached yet (see
  // LivePriceService) — those still arrive a moment later, same as before.
  //
  // Also used by HoldingDrawer after logging a transaction: `holdings` here is the flat list
  // backing the Holdings/Categories/Dashboard pages, but the drawer's own `holding` prop
  // (holdingDetail below) is a separate snapshot taken when it opened — reloading `holdings`
  // alone doesn't touch it, so its summary cards would keep showing pre-transaction numbers
  // forever without this also syncing holdingDetail when it's the same holding.
  const applyHoldingUpdate = (saved: Holding) => {
    setHoldings(current => {
      const index = current.findIndex(h => h.id === saved.id)
      return index === -1 ? [...current, saved] : current.map((h, i) => i === index ? saved : h)
    })
    setHoldingDetail(current => current && current.id === saved.id ? saved : current)
  }

  // Same instant-splice trick as applyHoldingUpdate above — a just-created category's own figures
  // (0 invested, 0 holdings, 0 weightage) are already final the moment the server echoes it back,
  // so there's nothing to wait on load() for here; this is what used to make a new category sit
  // invisible on the Categories page until the next full reload (up to several seconds away,
  // mostly spent re-pricing MARKET_PRICE holdings) resolved.
  const applyCategoryUpdate = (saved: Category) => {
    setCategories(current => {
      const index = current.findIndex(c => c.id === saved.id)
      return index === -1 ? [...current, saved] : current.map((c, i) => i === index ? saved : c)
    })
    setCategoryDetail(current => current && current.id === saved.id ? saved : current)
  }

  const mergeSavedHolding = (saved: Holding) => {
    setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null)
    applyHoldingUpdate(saved)
    // If this holding's category is the one currently open in the drawer, refetch just that
    // category right away too — otherwise its summary cards (current value, invested, P/L,
    // weightage) would sit stale until the background load() below finishes, which can take a
    // few seconds. Mirrors HoldingDrawer's own post-transaction refreshHolding().
    if (categoryDetail && categoryDetail.id === saved.categoryId) {
      api<Category>(`/api/categories/${saved.categoryId}?currency=${encodeURIComponent(displayCurrency)}`)
        .then(updated => setCategoryDetail(current => current && current.id === updated.id ? updated : current))
        .catch(() => { /* load() below will eventually catch it up */ })
    }
    void load()
  }

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
        {goku.admin && <GokuAdminButton goku={goku} />}
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
            <div className="currency-picker-group">
              <label className="currency-picker" title="Convert every figure on this page into another currency (live rate, refreshed at most every 20 minutes)">
                <span>View in</span>
                <select value={displayCurrency} disabled={convertingCurrency} onChange={e => {
                  setConvertingCurrency(true)
                  void load(e.target.value).finally(() => setConvertingCurrency(false))
                }}>{fxCurrencies.map(c => <option key={c}>{c}</option>)}</select>
              </label>
              {convertingCurrency
                ? <span className="fx-note fx-converting"><span className="dot" /> Converting…</span>
                : settings && displayCurrency !== settings.baseCurrency && fxRatesToBase[displayCurrency] && fxRatesToBase[settings.baseCurrency] &&
                  <span className="fx-note" title={`Live market rate${fxAsOf ? `, updated ${ago(fxAsOf)}` : ''} — a currency the feed can't reach falls back to its last known rate`}>
                    1 {displayCurrency} ≈ {rate(fxRatesToBase[displayCurrency] / fxRatesToBase[settings.baseCurrency], settings.baseCurrency)}
                  </span>}
            </div>
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
      {page === 'brokers' && <BrokersView displayCurrency={displayCurrency} dataVersion={dataVersion} />}
      {page === 'settings' && (settings
        ? <SettingsView settings={settings} countries={countries} dashboard={dashboard} holdings={holdings} reload={load} theme={theme} setTheme={setTheme}
            persona={persona} onStartOnboarding={() => setShowPersonaOnboardingFromSettings(true)} onReassessRisk={() => setShowRiskAssessment(true)}
            onAccountDeleted={onSignOut} />
        : <SectionError what="settings" message={loadErrors.settings} onRetry={() => void load()} />)}
    </main>
    {(creatingCategory || editingCategory) && <CategoryModal category={editingCategory} holdings={holdings} onClose={() => { setCreatingCategory(false); setEditingCategory(null) }} onSaved={saved => { setCreatingCategory(false); setEditingCategory(null); applyCategoryUpdate(saved); void load() }} />}
    {/* CategoryDrawer renders before HoldingModal so the modal paints on top when "+ Add holding"
        is opened from within an already-open drawer — the drawer now deliberately stays open
        behind it (see onAddHolding below) instead of closing, so this stacking order matters:
        swapping it would leave the modal visually present but unclickable underneath the drawer. */}
    {categoryDetail && <CategoryDrawer category={categoryDetail} holdings={holdings.filter(h => h.categoryId === categoryDetail.id)} onClose={() => setCategoryDetail(null)} onEdit={c => { setCategoryDetail(null); setEditingCategory(c) }} onAddHolding={c => setCreatingHoldingFor(c)} onOpenHolding={h => { setCategoryDetail(null); setHoldingDetail(h) }} reload={load} />}
    {(creatingHolding || creatingHoldingFor || editingHolding) && <HoldingModal holding={editingHolding} category={creatingHoldingFor} categories={categories} holdings={holdings} displayCurrency={displayCurrency} onClose={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null) }} onSaved={mergeSavedHolding} onGoToTransactions={() => setPage('transactions')} />}
    {holdingDetail && <HoldingDrawer holding={holdingDetail} displayCurrency={displayCurrency} fxRatesToBase={fxRatesToBase} onClose={() => setHoldingDetail(null)} onEdit={h => { setHoldingDetail(null); setEditingHolding(h) }} onHoldingUpdated={applyHoldingUpdate} reload={load} />}
    {showImport && <ImportModal onClose={() => setShowImport(false)} onImported={() => void load()} />}
    {showLayoutOnboarding && <CustomLayoutOnboarding
      onClose={() => setShowLayoutOnboarding(false)}
      onDismissForever={() => setSettings(s => s ? { ...s, customLayoutOnboardingDismissed: true } : s)} />}
    {showPersonaOnboarding && <PersonaOnboarding
      initial={persona}
      onClose={() => {
        setShowPersonaOnboarding(false)
        // The risk assessment is a separate, later-login gate (see load()'s first-load check) —
        // never chained in right after persona onboarding. The tour nudge still is.
        if (settings && !settings.userOnboardingDismissed) setShowTourNudge(true)
        void load()
      }}
      onDismissForever={() => setSettings(s => s ? { ...s, personaOnboardingDismissed: true } : s)} />}
    {showRiskAssessment && <RiskAssessment
      onClose={() => setShowRiskAssessment(false)}
      onDone={() => {
        setShowRiskAssessment(false)
        setSettings(s => s ? { ...s, riskOnboardingDismissed: true } : s)
        if (settings && !settings.userOnboardingDismissed) setShowTourNudge(true)
        void load()
      }} />}
    {showTourNudge && <TourNudge
      stackedAboveGoku={goku.available}
      onDismiss={() => setShowTourNudge(false)}
      onStart={() => { setShowTourNudge(false); setShowUserOnboarding(true) }} />}
    {showUserOnboarding && <UserOnboarding
      onClose={() => setShowUserOnboarding(false)}
      onDismissForever={() => setSettings(s => s ? { ...s, userOnboardingDismissed: true } : s)} />}
    {showPersonaOnboardingFromSettings && <PersonaOnboarding
      initial={persona}
      onClose={() => { setShowPersonaOnboardingFromSettings(false); void load() }}
      onDismissForever={() => setSettings(s => s ? { ...s, personaOnboardingDismissed: true } : s)} />}
    {goku.available && <GokuLauncher goku={goku} />}
    <GokuPanel goku={goku} />
    <GokuAdminModal goku={goku} />
  </div>
}
