import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

type HoldingKind = 'ASSET' | 'LIABILITY'
type ValuationMethod = 'MANUAL' | 'MARKET_PRICE' | 'BROKER_SYNC' | 'FIXED_RATE'
type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'ANNUALLY'
type TransactionType = 'BUY' | 'SELL' | 'SPLIT' | 'INTEREST' | 'ADJUSTMENT'
type Page = 'dashboard' | 'categories' | 'holdings' | 'transactions' | 'insights' | 'brokers' | 'settings'

// A user-defined bucket (Name + Asset/Liability type). Invested/current/P&L, weightage, and
// liquid/NPA amounts are rollups computed from its mapped holdings — not stored on it.
type Category = {
  id: string; name: string; kind: HoldingKind; description?: string
  investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number
  weightagePercent: number; liquidAmount: number; liquidPercent: number; npaAmount: number; npaPercent: number
  holdingCount: number; createdAt?: string; updatedAt?: string
}
// The actual named position — a stock, fund, crypto, FD, loan — filed under one category and
// held at one broker.
type Holding = {
  id: string; holdingId: string; categoryId: string; categoryName: string; name: string; kind: HoldingKind; valuationMethod: ValuationMethod
  tickerSymbol?: string; broker?: string; currency: string
  investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number
  quantity?: number; fixedAnnualRate?: number; compoundingFrequency?: Frequency; fixedRateStartDate?: string
  liquidWithinSevenDays: boolean; blocked: boolean; description?: string; notes?: string; tags: string[]
  createdAt?: string; updatedAt?: string; priceUpdatedAt?: string
}
type SymbolSuggestion = { symbol: string; name: string; exchange: string; type: string }
type MarketQuote = { symbol: string; name: string; price: number; currency: string; asOf: string }
type Breakdown = { label: string; value: number; investedValue: number; profitLoss: number }
type Dashboard = { netWorth: number; totalAssets: number; totalLiabilities: number; investedAssets: number; portfolioProfitLoss: number; byCategory: Breakdown[]; byBroker: Breakdown[]; byTag: Breakdown[] }
type User = { email: string; displayName: string; demoMode: boolean }
type Settings = {
  email: string; displayName: string; phone?: string; country: string; countryName: string; baseCurrency: string
  numberFormat: 'INDIAN' | 'INTERNATIONAL'; notifyEmail: boolean; notifySms: boolean; notifyPush: boolean
  notifyThresholdPercent: number
  dailyThresholdPercent?: number; weeklyThresholdPercent?: number; monthlyThresholdPercent?: number
  quarterlyThresholdPercent?: number; yearlyThresholdPercent?: number
  demoMode: boolean; holdingCount: number; memberSince: string
}
type Country = { code: string; name: string; currency: string }
type Transaction = {
  id: string; holdingId: string; holdingName: string; categoryId: string; categoryName: string; broker?: string
  currency: string; type: TransactionType; date: string; amount: number; quantity?: number; notes?: string; createdAt?: string
}
type Mover = { id: string; name: string; profitLoss: number; profitLossPercentage: number }
type Warning = { severity: 'WARN' | 'INFO'; message: string; holdingId?: string; holdingName?: string }
type Insights = { byCategory: Breakdown[]; byBroker: Breakdown[]; byTag: Breakdown[]; byCurrency: Breakdown[]; byLiquidity: Breakdown[]; topGainers: Mover[]; topLosers: Mover[]; warnings: Warning[] }
type PeriodKey = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
type PeriodMovement = { period: PeriodKey; percent: number; thresholdPercent: number }
type HotPick = {
  subjectType: 'HOLDING' | 'WATCHLIST'; id: string; name: string; categoryName?: string; tickerSymbol?: string
  currentValue?: number; currency?: string; triggered: PeriodMovement[]
}
type WatchlistEntry = { id: string; name: string; tickerSymbol?: string; notes?: string; currentValue?: number; lastUpdated?: string; createdAt?: string }
type BrokerGroup = { name: string; holdingCount: number; currentValue: number; investedValue: number; profitLoss: number; lastUpdated?: string; categories: string[]; currencies: string[] }
type Source = { key: string; name: string; status: string; description: string; capabilities: string[]; docsUrl?: string }
type Brokers = { brokers: BrokerGroup[]; sources: Source[] }
type ValuationDetail = { method: ValuationMethod; investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number; steps: string[]; projectedMaturityDate?: string; projectedMaturityValue?: number }
type ImportResult = { created: number; updated: number; skipped: number; errors: { row: number; message: string }[] }
type FxRates = { base: string; asOf: string; ratesToBase: Record<string, number>; note: string }

const frequencies: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUALLY']
const periodLabels: Record<PeriodKey, string> = { DAILY: 'Daily', WEEKLY: 'Weekly', MONTHLY: 'Monthly', QUARTERLY: 'Quarterly', YEARLY: 'Yearly' }
const periodFields: [PeriodKey, string][] = [
  ['DAILY', 'Daily'], ['WEEKLY', 'Weekly'], ['MONTHLY', 'Monthly'], ['QUARTERLY', 'Quarterly'], ['YEARLY', 'Yearly'],
]
const transactionTypes: TransactionType[] = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT']
const currencies = ['INR', 'USD', 'EUR', 'GBP', 'SGD', 'AED']
const nav: [Page, string, string][] = [
  ['dashboard', '◫', 'Overview'], ['categories', '◈', 'Categories'], ['holdings', '▤', 'Holdings'],
  ['transactions', '⇅', 'Transactions'], ['insights', '◔', 'Insights'], ['brokers', '⇄', 'External sources'], ['settings', '⚙', 'Settings'],
]
const blankCategoryForm = () => ({ name: '', kind: 'ASSET' as HoldingKind, description: '' })
const blankHoldingForm = (categoryId: string) => ({ categoryId, name: '', valuationMethod: 'MANUAL' as ValuationMethod, tickerSymbol: '', currency: 'INR', fixedAnnualRate: '', compoundingFrequency: 'MONTHLY' as Frequency, liquidWithinSevenDays: false, blocked: false, tags: [] as string[], broker: '', quantity: '', investedValue: '', currentValue: '', fixedRateStartDate: new Date().toISOString().slice(0, 10), description: '' })

let baseCurrency = 'INR'
let numberLocale = 'en-IN' // en-IN groups as lakh/crore; en-US groups as million/billion

type Theme = 'light' | 'dark'
const THEME_KEY = 'finsights-theme'
function initialTheme(): Theme {
  try {
    const stored = localStorage.getItem(THEME_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch { /* storage unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-Demo-User': 'demo@finsights.local', ...(options.headers ?? {}) },
    ...options,
  })
  if (!response.ok) {
    const message = await response.json().catch(() => ({}))
    throw new Error(message.message ?? 'Something went wrong')
  }
  return response.status === 204 ? undefined as T : response.json()
}

const money = (value: number, currency = baseCurrency) => new Intl.NumberFormat(numberLocale, { style: 'currency', currency, maximumFractionDigits: 0 }).format(value ?? 0)
const rate = (value: number, currency: string) => new Intl.NumberFormat(numberLocale, { style: 'currency', currency, maximumFractionDigits: 2 }).format(value ?? 0)
const percent = (value: number) => `${(value ?? 0).toFixed(1)}%`
const label = (value: string) => value.replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase())
const numeric = (value: string) => value === '' ? 0 : Number(value)
const since = (value?: string) => value ? new Date(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—'
const ago = (value?: string) => {
  if (!value) return '—'
  const secs = Math.round((Date.now() - new Date(value).getTime()) / 1000)
  if (secs < 60) return 'just now'
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)} h ago`
  return since(value)
}
const shortId = (id: string) => `#${id.slice(-8)}`

function downloadCsv(filename: string, headers: string[], rows: (string | number)[][]) {
  const escape = (value: string | number) => { const s = String(value ?? ''); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s }
  const csv = [headers, ...rows].map(row => row.map(escape).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
  URL.revokeObjectURL(url)
}

function App({ onSignOut }: { onSignOut: () => void }) {
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
  const bootstrapped = useRef(false)

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
      const [me, nextSettings, fx, nextCountries, nextDashboard, nextCategories, nextHoldings] = await Promise.all([
        api<User>('/api/auth/me'), api<Settings>('/api/settings'), api<FxRates>('/api/fx-rates'), api<Country[]>('/api/countries'),
        api<Dashboard>(`/api/dashboard?currency=${cur}`), api<Category[]>(`/api/categories?currency=${cur}`),
        api<Holding[]>(`/api/holdings?currency=${cur}`),
      ])
      baseCurrency = cur
      numberLocale = nextSettings.numberFormat === 'INTERNATIONAL' ? 'en-US' : 'en-IN'
      setUser(me); setSettings(nextSettings); setFxCurrencies(Object.keys(fx.ratesToBase).sort())
      setFxRatesToBase(fx.ratesToBase); setCountries(nextCountries)
      setDashboard(nextDashboard); setCategories(nextCategories); setHoldings(nextHoldings); setDisplayCurrency(cur)
      setDataVersion(v => v + 1)
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
    ['Holding ID', 'Name', 'Category', 'Broker', 'Currency', 'Valuation method', 'Ticker', 'Quantity', 'Invested', 'Current value', 'P/L', 'P/L %', 'Liquid', 'Blocked', 'Description', 'Tags'],
    holdings.map(h => [h.holdingId, h.name, h.categoryName, h.broker ?? '', h.currency, label(h.valuationMethod), h.tickerSymbol ?? '',
      h.quantity ?? '', h.investedValue, h.currentValue, h.profitLoss, h.profitLossPercentage.toFixed(2),
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
        </div>
      </header>
      {user?.demoMode && <div className="demo-banner"><strong>Demo mode</strong><span>Local data is saved in the backend. Configure Google OAuth before deployment.</span></div>}
      {page === 'dashboard' && dashboard && <DashboardView dashboard={dashboard} holdings={holdings} onManage={() => setPage('categories')} />}
      {page === 'categories' && <CategoriesView categories={categories} onOpen={setCategoryDetail} onEdit={setEditingCategory} onAdd={() => setCreatingCategory(true)} reload={load} />}
      {page === 'holdings' && <HoldingsView holdings={holdings} categories={categories} reload={load} onEdit={setEditingHolding} onAdd={() => setCreatingHolding(true)} onOpen={setHoldingDetail} />}
      {page === 'transactions' && <TransactionsView holdings={holdings} displayCurrency={displayCurrency} dataVersion={dataVersion} reload={load} />}
      {page === 'insights' && settings && <InsightsView displayCurrency={displayCurrency} dataVersion={dataVersion} settings={settings} reload={load} onOpen={id => setHoldingDetail(holdings.find(h => h.id === id) ?? null)} />}
      {page === 'brokers' && <BrokersView displayCurrency={displayCurrency} dataVersion={dataVersion} />}
      {page === 'settings' && settings && <SettingsView settings={settings} countries={countries} dashboard={dashboard} holdings={holdings} reload={load} theme={theme} setTheme={setTheme} />}
    </main>
    {(creatingCategory || editingCategory) && <CategoryModal category={editingCategory} onClose={() => { setCreatingCategory(false); setEditingCategory(null) }} onSaved={() => { setCreatingCategory(false); setEditingCategory(null); void load() }} />}
    {(creatingHolding || creatingHoldingFor || editingHolding) && <HoldingModal holding={editingHolding} category={creatingHoldingFor} categories={categories} holdings={holdings} onClose={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null) }} onSaved={() => { setCreatingHolding(false); setCreatingHoldingFor(null); setEditingHolding(null); void load() }} />}
    {categoryDetail && <CategoryDrawer category={categoryDetail} holdings={holdings.filter(h => h.categoryId === categoryDetail.id)} onClose={() => setCategoryDetail(null)} onEdit={c => { setCategoryDetail(null); setEditingCategory(c) }} onAddHolding={c => { setCategoryDetail(null); setCreatingHoldingFor(c) }} onOpenHolding={h => { setCategoryDetail(null); setHoldingDetail(h) }} reload={load} />}
    {holdingDetail && <HoldingDrawer holding={holdingDetail} displayCurrency={displayCurrency} onClose={() => setHoldingDetail(null)} onEdit={h => { setHoldingDetail(null); setEditingHolding(h) }} />}
    {showImport && <ImportModal onClose={() => setShowImport(false)} onImported={() => void load()} />}
  </div>
}


function DashboardView({ dashboard, holdings, onManage }: { dashboard: Dashboard; holdings: Holding[]; onManage: () => void }) {
  const cards = [
    ['Net worth', dashboard.netWorth, 'Assets less liabilities'], ['Total assets', dashboard.totalAssets, `${holdings.filter(h => h.kind === 'ASSET').length} active holdings`],
    ['Total liabilities', dashboard.totalLiabilities, 'Outstanding obligations'], ['Portfolio P/L', dashboard.portfolioProfitLoss, `${dashboard.investedAssets ? percent((dashboard.portfolioProfitLoss / dashboard.investedAssets) * 100) : '0.0%'} on invested assets`],
  ] as const
  return <>
    <section className="hero"><div><p>Current portfolio value</p><h2>{money(dashboard.netWorth)}</h2><span className={dashboard.portfolioProfitLoss >= 0 ? 'positive' : 'negative'}>{dashboard.portfolioProfitLoss >= 0 ? '↑' : '↓'} {money(Math.abs(dashboard.portfolioProfitLoss))} total gain/loss</span></div><button className="outline" onClick={onManage}>Manage categories →</button></section>
    <section className="metric-grid">{cards.map(([title, value, note]) => <article className="metric-card" key={title}><p>{title}</p><strong className={title === 'Portfolio P/L' && Number(value) < 0 ? 'negative' : ''}>{money(value as number)}</strong><small>{note}</small></article>)}</section>
    <section className="insight-grid">
      <BreakdownCard title="Allocation by category" items={dashboard.byCategory} total={dashboard.totalAssets} />
      <BreakdownCard title="Value by broker" items={dashboard.byBroker} total={dashboard.totalAssets} />
      <article className="panel recent"><div className="panel-heading"><h3>Portfolio pulse</h3><span>Live calculation</span></div><div className="pulse-row"><span>Liquid within 7 days</span><strong>{money(holdings.filter(h => h.liquidWithinSevenDays).reduce((sum, h) => sum + h.currentValue, 0))}</strong></div><div className="pulse-row"><span>Blocked / NPA</span><strong>{money(holdings.filter(h => h.blocked).reduce((sum, h) => sum + h.currentValue, 0))}</strong></div><div className="pulse-row"><span>Fixed-rate instruments</span><strong>{holdings.filter(h => h.valuationMethod === 'FIXED_RATE').length}</strong></div><p className="hint">Daily portfolio snapshots and broker reconciliation are the next integration layer.</p></article>
      <BreakdownCard title="Value by tag" items={dashboard.byTag} total={dashboard.totalAssets} />
    </section>
  </>
}

function BreakdownCard({ title, items, total }: { title: string; items: Breakdown[]; total: number }) {
  return <article className="panel"><div className="panel-heading"><h3>{title}</h3><span>{items.length} group{items.length === 1 ? '' : 's'}</span></div><div className="breakdown-list">{items.slice(0, 6).map(item => <div className="breakdown" key={item.label}><div className="breakdown-copy"><div><span title={label(item.label)}>{label(item.label)}</span><strong>{money(item.value)}</strong></div><div className="bar"><i style={{ width: `${Math.min(100, total ? (item.value / total) * 100 : 0)}%` }} /></div></div><b className={item.profitLoss >= 0 ? 'positive' : 'negative'}>{item.profitLoss >= 0 ? '+' : ''}{money(item.profitLoss)}</b></div>)}{!items.length && <p className="hint">Nothing to group yet.</p>}</div></article>
}

// ---------------------------------------------------------------------------
// Categories — user-defined buckets (Name + Asset/Liability type). Invested,
// current, P&L, weightage, and liquid/NPA amounts all roll up from mapped
// holdings; a row click lists those holdings.
// ---------------------------------------------------------------------------

type CategorySortKey = 'name' | 'kind' | 'investedValue' | 'currentValue' | 'profitLoss' | 'weightagePercent' | 'liquidAmount' | 'npaAmount'
const categoryColumns: [string, CategorySortKey][] = [
  ['Name', 'name'], ['Type', 'kind'], ['Invested', 'investedValue'], ['Current value', 'currentValue'],
  ['P/L', 'profitLoss'], ['Weightage', 'weightagePercent'], ['Liquid', 'liquidAmount'], ['NPA', 'npaAmount'],
]

type HoldingSortKey = 'name' | 'categoryName' | 'broker' | 'investedValue' | 'currentValue' | 'profitLoss'

function CategoriesView({ categories, onOpen, onEdit, onAdd, reload }: { categories: Category[]; onOpen: (c: Category) => void; onEdit: (c: Category) => void; onAdd: () => void; reload: () => Promise<void> }) {
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<CategorySortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [dragId, setDragId] = useState<string | null>(null)
  const [dragOverId, setDragOverId] = useState<string | null>(null)

  const filtered = useMemo(() => categories.filter(c => c.name.toLowerCase().includes(filter.toLowerCase())), [categories, filter])
  const sorted = useMemo(() => {
    if (!sortKey) return filtered
    const dir = sortDir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => {
      const av = a[sortKey], bv = b[sortKey]
      return typeof av === 'string' && typeof bv === 'string' ? av.localeCompare(bv) * dir : ((av as number) - (bv as number)) * dir
    })
  }, [filtered, sortKey, sortDir])

  // Click cycles ascending → descending → back to the custom drag order (Excel-style tri-state).
  const toggleSort = (key: CategorySortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: CategorySortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const dropOnto = async (targetId: string) => {
    const draggedId = dragId
    setDragId(null); setDragOverId(null)
    if (!draggedId || draggedId === targetId) return
    const ids = categories.map(c => c.id)
    const from = ids.indexOf(draggedId), to = ids.indexOf(targetId)
    if (from === -1 || to === -1) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    await api('/api/categories/reorder', { method: 'PATCH', body: JSON.stringify({ orderedIds: ids }) })
    await reload()
  }

  return <>
    <section className="holdings-toolbar">
      <div className="search"><span>⌕</span><input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Search categories" /></div>
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary push-end" onClick={onAdd}>+ Add category</button>
    </section>
    <section className="table-panel"><table>
      <thead><tr>
        <th className="drag-col" title={sortKey ? 'Clear the column sort to drag-reorder rows' : 'Drag rows to reorder'} />
        {categoryColumns.map(([text, key]) => <th key={key} className="sortable" onClick={() => toggleSort(key)}>{text}{arrow(key)}</th>)}
        <th />
      </tr></thead>
      <tbody>{sorted.length ? sorted.map(c => <tr key={c.id} className={`clickable-row${dragOverId === c.id ? ' drag-over' : ''}`}
          onClick={() => onOpen(c)}
          onDragOver={e => { if (!sortKey) { e.preventDefault(); setDragOverId(c.id) } }}
          onDragLeave={() => setDragOverId(current => current === c.id ? null : current)}
          onDrop={e => { e.preventDefault(); void dropOnto(c.id) }}>
        <td className="drag-col" onClick={e => e.stopPropagation()}>
          {!sortKey && <span className="drag-handle" draggable onDragStart={() => setDragId(c.id)} onDragEnd={() => { setDragId(null); setDragOverId(null) }} title="Drag to reorder">⠿</span>}
        </td>
        <td>
          <div className="name-cell"><strong title={c.name}>{c.name}</strong>{c.description && <InfoTip text={c.description} />}</div>
          <small className="owner">{c.holdingCount} holding{c.holdingCount === 1 ? '' : 's'}</small>
        </td>
        <td><span className={`badge ${c.kind === 'LIABILITY' ? 'liability' : ''}`}>{label(c.kind)}</span></td>
        <td>{money(c.investedValue)}</td>
        <td><strong>{money(c.currentValue)}</strong></td>
        <td>{c.kind === 'LIABILITY' ? <span className="owner">—</span> : <span className={c.profitLoss >= 0 ? 'positive' : 'negative'}>{c.profitLoss >= 0 ? '+' : ''}{money(c.profitLoss)}<small>{percent(c.profitLossPercentage)}</small></span>}</td>
        <td>{c.holdingCount ? percent(c.weightagePercent) : '—'}</td>
        <td>{money(c.liquidAmount)}<small className="owner">{percent(c.liquidPercent)}</small></td>
        <td>{money(c.npaAmount)}<small className="owner">{percent(c.npaPercent)}</small></td>
        <td className="actions"><button className="primary-link" onClick={e => { e.stopPropagation(); onEdit(c) }}>Edit</button></td>
      </tr>) : <tr><td colSpan={10} className="empty"><strong>No categories yet</strong><span>Categories are simple buckets — "Growth Equity", "Emergency Fund" — that holdings get filed under.</span><button className="primary" onClick={onAdd}>Add category</button></td></tr>}</tbody>
    </table></section>
  </>
}

function CategoryDrawer({ category, holdings, onClose, onEdit, onAddHolding, onOpenHolding, reload }: {
  category: Category; holdings: Holding[]; onClose: () => void; onEdit: (c: Category) => void
  onAddHolding: (c: Category) => void; onOpenHolding: (h: Holding) => void; reload: () => Promise<void>
}) {
  const removeCategory = async () => {
    if (!confirm(`Delete ${category.name} and its ${holdings.length} holding${holdings.length === 1 ? '' : 's'}? This also removes their transactions.`)) return
    await api(`/api/categories/${category.id}`, { method: 'DELETE' }); await reload(); onClose()
  }
  return <div className="modal-backdrop" onClick={onClose}><section className="modal drawer" onClick={e => e.stopPropagation()}>
    <div className="modal-header">
      <div><p className="eyebrow">{label(category.kind)} CATEGORY</p><h2>{category.name}</h2></div>
      <button className="close" onClick={onClose}>×</button>
    </div>
    {category.description && <p className="drawer-description">{category.description}</p>}
    <div className="drawer-metrics">
      <div><p>Current value</p><strong>{money(category.currentValue)}</strong></div>
      <div><p>Invested</p><strong>{money(category.investedValue)}</strong></div>
      <div><p>P/L</p><strong className={category.kind === 'LIABILITY' ? '' : category.profitLoss >= 0 ? 'positive' : 'negative'}>{category.kind === 'LIABILITY' ? '—' : `${category.profitLoss >= 0 ? '+' : ''}${money(category.profitLoss)} · ${percent(category.profitLossPercentage)}`}</strong></div>
    </div>
    <div className="drawer-facts">
      <span>Weightage<b>{category.holdingCount ? percent(category.weightagePercent) : '—'}</b></span>
      <span>Liquid within 7 days<b>{money(category.liquidAmount)} · {percent(category.liquidPercent)}</b></span>
      <span>Blocked / NPA<b>{money(category.npaAmount)} · {percent(category.npaPercent)}</b></span>
    </div>

    <div className="panel-heading"><h3>Mapped holdings</h3><span>{holdings.length} position{holdings.length === 1 ? '' : 's'}</span></div>
    {holdings.length ? <div className="mapped-holdings">{holdings.map(h => <button key={h.id} className="mapped-holding" onClick={() => onOpenHolding(h)}>
      <div className="mapped-holding-name"><strong title={h.name}>{h.name}</strong><small>{h.broker || 'Unassigned broker'}{h.quantity != null ? ` · qty ${h.quantity}` : ''}</small></div>
      <div className="mapped-holding-value"><span>{money(h.currentValue, h.currency)}</span><small className={h.profitLoss >= 0 ? 'positive' : 'negative'}>{h.kind === 'LIABILITY' ? '—' : `${h.profitLoss >= 0 ? '+' : ''}${percent(h.profitLossPercentage)}`}</small></div>
    </button>)}</div> : <p className="hint">No holdings mapped yet — add one to record an actual position in this category.</p>}
    <button className="outline compact" onClick={() => onAddHolding(category)}>+ Add a holding here</button>

    <div className="modal-actions"><button className="danger-link-btn" onClick={() => void removeCategory()}>Delete category</button><button className="outline" onClick={onClose}>Close</button><button className="primary" onClick={() => onEdit(category)}>Edit category</button></div>
  </section></div>
}

function CategoryModal({ category, onClose, onSaved }: { category: Category | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => category ? { name: category.name, kind: category.kind, description: category.description || '' } : blankCategoryForm())
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    try {
      await api(category ? `/api/categories/${category.id}` : '/api/categories', { method: category ? 'PUT' : 'POST', body: JSON.stringify(form) })
      onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save category') } finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section className="modal narrow"><div className="modal-header"><div><p className="eyebrow">{category ? 'EDIT CATEGORY' : 'NEW CATEGORY'}</p><h2>{category ? category.name : 'Add a category'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Name" required wide><input required maxLength={128} value={form.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Growth Equity, Emergency Fund" /></Field>
        <Field label="Type" required wide><select value={form.kind} onChange={e => set('kind', e.target.value)}><option value="ASSET">Asset</option><option value="LIABILITY">Liability</option></select></Field>
        <Field label="Description" wide><input value={form.description} onChange={e => set('description', e.target.value)} placeholder="One line — shows in the ⓘ tooltip on the Categories table" maxLength={1024} /></Field>
      </div>
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving}>{saving ? 'Saving…' : category ? 'Save changes' : 'Add category'}</button></div>
    </form>
  </section></div>
}

// ---------------------------------------------------------------------------
// Holdings — a named position, filed under one category and held at one broker.
// ---------------------------------------------------------------------------

function HoldingsView({ holdings, categories, reload, onEdit, onAdd, onOpen }: {
  holdings: Holding[]; categories: Category[]; reload: () => Promise<void>; onEdit: (h: Holding) => void
  onAdd: () => void; onOpen: (h: Holding) => void
}) {
  const [filter, setFilter] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [sortKey, setSortKey] = useState<HoldingSortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [dragId, setDragId] = useState<string | null>(null)
  const [dragOverId, setDragOverId] = useState<string | null>(null)
  const filtered = useMemo(() => holdings.filter(h =>
    `${h.name} ${h.broker ?? ''} ${h.categoryName} ${h.currency} ${h.tags.join(' ')}`.toLowerCase().includes(filter.toLowerCase())
    && (!categoryId || h.categoryId === categoryId)), [holdings, filter, categoryId])
  const sorted = useMemo(() => {
    if (!sortKey) return filtered
    const dir = sortDir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => {
      const av = a[sortKey] ?? '', bv = b[sortKey] ?? ''
      return typeof av === 'string' && typeof bv === 'string' ? av.localeCompare(bv) * dir : ((av as number) - (bv as number)) * dir
    })
  }, [filtered, sortKey, sortDir])

  // Click cycles ascending → descending → back to the custom drag order (Excel-style tri-state).
  const toggleSort = (key: HoldingSortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: HoldingSortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const dropOnto = async (targetId: string) => {
    const draggedId = dragId
    setDragId(null); setDragOverId(null)
    if (!draggedId || draggedId === targetId) return
    const ids = holdings.map(h => h.id)
    const from = ids.indexOf(draggedId), to = ids.indexOf(targetId)
    if (from === -1 || to === -1) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    await api('/api/holdings/reorder', { method: 'PATCH', body: JSON.stringify({ orderedIds: ids }) })
    await reload()
  }

  const remove = async (holding: Holding) => { if (confirm(`Delete ${holding.name}?`)) { await api(`/api/holdings/${holding.id}`, { method: 'DELETE' }); await reload() } }

  return <>
    <section className="holdings-toolbar">
      <div className="search"><span>⌕</span><input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Search holdings, brokers, tags" /></div>
      <select className="filter-select" value={categoryId} onChange={e => setCategoryId(e.target.value)}><option value="">All categories</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select>
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary push-end" onClick={onAdd} disabled={!categories.length} title={categories.length ? '' : 'Add a category first'}>+ Add holding</button>
    </section>
    <section className="table-panel"><table>
      <thead><tr>
        <th className="drag-col" title={sortKey ? 'Clear the column sort to drag-reorder rows' : 'Drag rows to reorder'} />
        <th className="sortable" onClick={() => toggleSort('name')}>Holding{arrow('name')}</th>
        <th className="sortable" onClick={() => toggleSort('broker')}>Broker{arrow('broker')}</th>
        <th className="sortable" onClick={() => toggleSort('categoryName')}>Category{arrow('categoryName')}</th>
        <th className="sortable" onClick={() => toggleSort('investedValue')}>Invested{arrow('investedValue')}</th>
        <th className="sortable" onClick={() => toggleSort('currentValue')}>Current value{arrow('currentValue')}</th>
        <th className="sortable" onClick={() => toggleSort('profitLoss')}>P/L{arrow('profitLoss')}</th>
        <th>Quantity</th>
        <th>Tags</th>
        <th />
      </tr></thead>
      <tbody>{sorted.length ? sorted.map(holding => <tr key={holding.id} className={dragOverId === holding.id ? 'drag-over' : ''}
          onDragOver={e => { if (!sortKey) { e.preventDefault(); setDragOverId(holding.id) } }}
          onDragLeave={() => setDragOverId(current => current === holding.id ? null : current)}
          onDrop={e => { e.preventDefault(); void dropOnto(holding.id) }}>
        <td className="drag-col">
          {!sortKey && <span className="drag-handle" draggable onDragStart={() => setDragId(holding.id)} onDragEnd={() => { setDragId(null); setDragOverId(null) }} title="Drag to reorder">⠿</span>}
        </td>
        <td><div className="name-cell">
          <button className="name-button" onClick={() => onOpen(holding)} title={holding.name}><strong>{holding.name}</strong><small className="holding-ref">{holding.holdingId}</small></button>
          {holding.description && <InfoTip text={holding.description} />}
        </div></td>
        <td><span className="trunc-cell" title={holding.broker || ''}>{holding.broker || '—'}</span></td>
        <td><span className={`badge trunc-cell ${holding.kind === 'LIABILITY' ? 'liability' : ''}`} title={holding.categoryName}>{holding.categoryName}</span><small className="owner">{label(holding.valuationMethod)}</small></td>
        <td>{money(holding.investedValue, holding.currency)}</td>
        <td><strong>{money(holding.currentValue, holding.currency)}</strong></td>
        <td>{holding.kind === 'LIABILITY' ? <span className="owner">—</span> : <span className={holding.profitLoss >= 0 ? 'positive' : 'negative'}>{holding.profitLoss >= 0 ? '+' : ''}{money(holding.profitLoss, holding.currency)}<small>{percent(holding.profitLossPercentage)}</small></span>}</td>
        <td>{holding.quantity != null && holding.quantity > 0
          ? <span>{holding.quantity}<small className="owner">avg {rate(holding.investedValue / holding.quantity, holding.currency)}</small></span>
          : <span className="owner">—</span>}</td>
        <td>{holding.liquidWithinSevenDays || holding.blocked || holding.tags.length
          ? <div className="tag-row">
              {holding.liquidWithinSevenDays && <em className="tag-liquid">Liquid</em>}
              {holding.blocked && <em className="tag-npa">NPA</em>}
              {holding.tags.slice(0, 5).map(tag => <em key={tag} title={tag}>{tag}</em>)}
              {holding.tags.length > 5 && <em className="tag-more" title={holding.tags.slice(5).join(', ')}>+{holding.tags.length - 5}</em>}
            </div>
          : <span className="owner">—</span>}</td>
        <td className="actions actions-vertical"><button className="primary-link" onClick={() => onEdit(holding)}>Edit</button><button className="danger-link" onClick={() => void remove(holding)}>Delete</button></td>
      </tr>) : <tr><td colSpan={10} className="empty"><strong>No holdings match</strong><span>Every holding maps to a category and a broker. {categories.length ? 'Add one below.' : 'Add a category first.'}</span><button className="primary" onClick={onAdd} disabled={!categories.length}>Add holding</button></td></tr>}</tbody>
    </table></section>
  </>
}

function HoldingModal({ holding, category, categories, holdings, onClose, onSaved }: {
  holding: Holding | null; category: Category | null; categories: Category[]; holdings: Holding[]; onClose: () => void; onSaved: () => void
}) {
  const startCategoryId = holding?.categoryId ?? category?.id ?? categories[0]?.id ?? ''
  const [form, setForm] = useState(() => holding
    ? { categoryId: holding.categoryId, name: holding.name, valuationMethod: holding.valuationMethod, tickerSymbol: holding.tickerSymbol || '', currency: holding.currency, fixedAnnualRate: holding.fixedAnnualRate ? String(holding.fixedAnnualRate * 100) : '', compoundingFrequency: holding.compoundingFrequency || 'MONTHLY', liquidWithinSevenDays: holding.liquidWithinSevenDays, blocked: holding.blocked, tags: [...holding.tags], broker: holding.broker || '', quantity: holding.quantity != null ? String(holding.quantity) : '', investedValue: String(holding.investedValue), currentValue: String(holding.currentValue), fixedRateStartDate: holding.fixedRateStartDate || new Date().toISOString().slice(0, 10), description: holding.description || '' }
    : blankHoldingForm(startCategoryId))
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const isFixedRate = form.valuationMethod === 'FIXED_RATE'
  const isMarket = form.valuationMethod === 'MARKET_PRICE'
  const isEdit = !!holding
  const set = (key: string, value: string | boolean) => setForm(current => ({ ...current, [key]: value }))

  // Market-linked holdings take their name and currency from the ticker, not the user.
  useEffect(() => {
    if (!isMarket) return
    const symbol = form.tickerSymbol.trim()
    if (!symbol) return
    const timer = setTimeout(() => {
      api<MarketQuote>(`/api/market/quote?symbol=${encodeURIComponent(symbol)}`)
        .then(q => setForm(current => current.tickerSymbol.trim().toUpperCase() === symbol.toUpperCase()
          ? { ...current, name: q.name || current.name, currency: q.currency || current.currency }
          : current))
        .catch(() => { /* leave name/currency as-is if the feed is unreachable */ })
    }, 400)
    return () => clearTimeout(timer)
  }, [form.tickerSymbol, isMarket])

  const [tagIdeas, setTagIdeas] = useState<string[]>([])
  useEffect(() => {
    if (!form.categoryId) { setTagIdeas([]); return }
    const query = new URLSearchParams({ categoryId: form.categoryId, valuationMethod: form.valuationMethod, limit: '50' })
    api<string[]>(`/api/holdings/tag-suggestions?${query}`).then(setTagIdeas).catch(() => setTagIdeas([]))
  }, [form.categoryId, form.valuationMethod])

  // A holding is 1-1 with a (name, broker) pair — block a new one that would collide.
  const duplicate = !isEdit && !!form.name.trim() && !!form.broker.trim() && holdings.some(h =>
    h.name.trim().toLowerCase() === form.name.trim().toLowerCase()
    && (h.broker ?? '').trim().toLowerCase() === form.broker.trim().toLowerCase())

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const payload = {
      categoryId: form.categoryId, name: form.name, valuationMethod: form.valuationMethod,
      tickerSymbol: form.tickerSymbol || null, broker: form.broker.trim(),
      currency: form.currency, quantity: form.quantity ? numeric(form.quantity) : null,
      investedValue: numeric(form.investedValue), currentValue: isFixedRate ? null : numeric(form.currentValue),
      fixedAnnualRate: isFixedRate ? numeric(form.fixedAnnualRate) / 100 : null,
      compoundingFrequency: isFixedRate ? form.compoundingFrequency : null,
      fixedRateStartDate: isFixedRate ? form.fixedRateStartDate : null,
      liquidWithinSevenDays: form.liquidWithinSevenDays, blocked: form.blocked,
      description: form.description || null, notes: null,
      tags: form.tags,
    }
    try { await api(holding ? `/api/holdings/${holding.id}` : '/api/holdings', { method: holding ? 'PUT' : 'POST', body: JSON.stringify(payload) }); onSaved() }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not save holding') } finally { setSaving(false) }
  }

  return <div className="modal-backdrop"><section className="modal"><div className="modal-header"><div><p className="eyebrow">{holding ? 'EDIT HOLDING' : 'NEW HOLDING'}</p><h2>{holding ? holding.name : category ? `Add a holding in ${category.name}` : 'Add a holding'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Category" required>
          <select required disabled={!!category && !holding} value={form.categoryId} onChange={e => set('categoryId', e.target.value)}>
            {!categories.length && <option value="">No categories yet</option>}
            {categories.map(c => <option key={c.id} value={c.id}>{c.name} ({label(c.kind)})</option>)}
          </select>
        </Field>
        <Field label="Valuation method" required><select value={form.valuationMethod} onChange={e => set('valuationMethod', e.target.value)}><option value="MANUAL">Manual value</option><option value="MARKET_PRICE">Market price</option><option value="FIXED_RATE">Fixed-rate compounding</option></select></Field>
        {isMarket && <Field label="Ticker symbol" required wide><SymbolSearchInput value={form.tickerSymbol} onChange={v => set('tickerSymbol', v)} /></Field>}
        <Field label="Name" required><input required maxLength={128} value={form.name} disabled={isMarket} onChange={e => set('name', e.target.value)} placeholder={isMarket ? 'Filled from the ticker' : 'e.g. Reliance Industries, HDFC FD'} /></Field>
        <Field label="Description" wide><input value={form.description} onChange={e => set('description', e.target.value)} placeholder="One line — shows in the ⓘ tooltip on the Holdings table" maxLength={1024} /></Field>
        {!isEdit && <Field label="Broker / platform" required><input required maxLength={96} value={form.broker} onChange={e => set('broker', e.target.value)} placeholder="Kite, Groww, HDFC Bank…" /></Field>}
        {!isEdit && <Field label="Quantity"><input type="number" step="any" value={form.quantity} onChange={e => set('quantity', e.target.value)} placeholder="Units held" /></Field>}
        {!isEdit && <Field label={isFixedRate ? 'Principal' : 'Invested value'}><input type="number" min="0" step="0.01" value={form.investedValue} onChange={e => set('investedValue', e.target.value)} /></Field>}
        {isFixedRate && <>
          <Field label="Annual rate (%)"><input type="number" min="0" step="0.01" value={form.fixedAnnualRate} onChange={e => set('fixedAnnualRate', e.target.value)} /></Field>
          <Field label="Compounding"><select value={form.compoundingFrequency} onChange={e => set('compoundingFrequency', e.target.value)}>{frequencies.map(item => <option key={item}>{item}</option>)}</select></Field>
          <Field label="Start date"><input type="date" value={form.fixedRateStartDate} onChange={e => set('fixedRateStartDate', e.target.value)} /></Field>
        </>}
        <div className="field-pair">
          <Field label="Currency"><select disabled={isEdit || isMarket} value={form.currency} onChange={e => set('currency', e.target.value)}>{currencies.map(item => <option key={item}>{item}</option>)}</select></Field>
          <Field label={isFixedRate ? 'Current value (computed)' : isMarket ? 'Current value (live price)' : 'Current value'}><input type="number" min="0" step="0.01" disabled={isFixedRate || isMarket} value={form.currentValue} onChange={e => set('currentValue', e.target.value)} placeholder={isMarket ? 'Priced after saving' : isFixedRate ? 'Computed after saving' : ''} /></Field>
        </div>
        <div className="check-row">
          <label><input type="checkbox" checked={form.liquidWithinSevenDays} onChange={e => set('liquidWithinSevenDays', e.target.checked)} /> Liquid within 7 days <InfoTip text="Money you could realistically access within a week. Feeds the “liquid within 7 days” figure on the overview so you know how much of the portfolio is reachable in an emergency." /></label>
          <label><input type="checkbox" checked={form.blocked} onChange={e => set('blocked', e.target.checked)} /> Blocked / NPA <InfoTip text="The holding is locked, pledged, in default, or a non-performing asset. It is valued separately from healthy assets and flagged in the data-quality checks." /></label>
        </div>
        <Field label="Tags" wide>
          <TagInput tags={form.tags} suggestions={tagIdeas} onChange={next => setForm(current => ({ ...current, tags: next }))} />
        </Field>
      </div>
      {isEdit && <p className="form-callout"><span className="form-callout-dot">i</span>
        <span><b>Broker</b> is fixed for the life of a holding, and <b>invested value</b> &amp; <b>quantity</b> are calculated from its transactions — add or edit transactions to change them.</span>
      </p>}
      {duplicate && <p className="form-error">A holding named "{form.name.trim()}" at "{form.broker.trim()}" already exists — one holding maps to one broker.</p>}
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || !form.categoryId || !form.name.trim() || (isMarket && !form.tickerSymbol.trim()) || duplicate}>{saving ? 'Saving…' : holding ? 'Save changes' : 'Add holding'}</button></div>
    </form>
  </section></div>
}

function HoldingDrawer({ holding, displayCurrency, onClose, onEdit }: { holding: Holding; displayCurrency: string; onClose: () => void; onEdit: (holding: Holding) => void }) {
  const [detail, setDetail] = useState<ValuationDetail | null>(null)
  const [txns, setTxns] = useState<Transaction[] | null>(null)
  const [visibleTxns, setVisibleTxns] = useState(10)
  const [calcOpen, setCalcOpen] = useState(false)
  useEffect(() => { api<ValuationDetail>(`/api/holdings/${holding.id}/valuation`).then(setDetail).catch(() => setDetail(null)) }, [holding.id])
  useEffect(() => { setVisibleTxns(10); api<Transaction[]>(`/api/transactions?holdingId=${holding.id}&currency=${displayCurrency}`).then(setTxns).catch(() => setTxns([])) }, [holding.id, displayCurrency])
  const onTxnScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 48) setVisibleTxns(count => count + 10)
  }
  return <div className="modal-backdrop" onClick={onClose}><section className="modal drawer" onClick={e => e.stopPropagation()}>
    <div className="modal-header">
      <div><p className="eyebrow">{label(holding.kind)} · {holding.broker || 'Unassigned broker'}</p><h2>{holding.name}</h2><p className="drawer-ref">{holding.holdingId}</p></div>
      <button className="close" onClick={onClose}>×</button>
    </div>
    {holding.description && <p className="drawer-description">{holding.description}</p>}
    <div className="drawer-metrics">
      <div><p>Current value</p><strong>{money(holding.currentValue, holding.currency)}</strong></div>
      <div><p>{holding.kind === 'LIABILITY' ? 'Original principal' : 'Invested'}</p><strong>{money(holding.investedValue, holding.currency)}</strong></div>
      <div><p>P/L</p><strong className={holding.kind === 'LIABILITY' ? '' : holding.profitLoss >= 0 ? 'positive' : 'negative'}>{holding.kind === 'LIABILITY' ? '—' : `${holding.profitLoss >= 0 ? '+' : ''}${money(holding.profitLoss, holding.currency)} · ${percent(holding.profitLossPercentage)}`}</strong></div>
    </div>
    <div className="drawer-facts">
      <span>Broker<b>{holding.broker || '—'}</b></span>
      <span>Currency<b>{holding.currency}</b></span>
      <span>Valuation method<b className="fact-with-icon">{label(holding.valuationMethod)}
        <button type="button" className={`calc-toggle${calcOpen ? ' open' : ''}`} aria-expanded={calcOpen} aria-label="How this value is calculated" title="How this value is calculated" onClick={() => setCalcOpen(o => !o)}><i>i</i></button>
      </b></span>
      <span>Last updated<b>{since(holding.updatedAt)}</b></span>
      {holding.quantity != null && <span>Quantity<b>{holding.quantity}</b></span>}
      {holding.valuationMethod === 'MARKET_PRICE' && holding.tickerSymbol && <span>Live price
        <b className="fact-with-icon"><span className="live-dot" />{holding.tickerSymbol} · {holding.priceUpdatedAt ? ago(holding.priceUpdatedAt) : 'pending'}</b></span>}
    </div>
    {calcOpen && <div className="calc-panel">
      <div className="panel-heading"><h3>How this value is calculated</h3></div>
      {detail ? <ol className="audit-list">{detail.steps.map((step, i) => <li key={i}>{step}</li>)}</ol> : <p className="hint">Loading calculation…</p>}
      {detail?.projectedMaturityValue != null && <p className="hint">By {since(detail.projectedMaturityDate)}, if the rate holds: <b>{money(detail.projectedMaturityValue, holding.currency)}</b>.</p>}
    </div>}

    <div className="panel-heading"><h3>Transactions</h3><span>{txns && txns.length ? `${txns.length}` : ''}</span></div>
    {txns === null ? <p className="hint">Loading transactions…</p>
      : txns.length === 0 ? <p className="hint">No transactions logged against this holding yet.</p>
      : <div className="drawer-txns" onScroll={onTxnScroll}>{txns.slice(0, visibleTxns).map(t => <div className="drawer-txn" key={t.id}>
          <span className={`badge txn-${t.type.toLowerCase()}`}>{label(t.type)}</span>
          <span className="drawer-txn-date">{since(t.date)}</span>
          <span className="drawer-txn-amount">{money(t.amount, t.currency)}{t.quantity != null ? ` · qty ${t.quantity}` : ''}</span>
          {t.notes && <span className="drawer-txn-notes">{t.notes}</span>}
        </div>)}{visibleTxns < txns.length && <p className="hint drawer-txns-more">Scroll for {txns.length - visibleTxns} more</p>}</div>}

    <div className="modal-actions"><button className="outline" onClick={onClose}>Close</button><button className="primary" onClick={() => onEdit(holding)}>Edit holding</button></div>
  </section></div>
}

// ---------------------------------------------------------------------------
// Transactions — the only page with bulk CSV/XML import; each row is linked
// to an existing holdingId.
// ---------------------------------------------------------------------------

type TransactionSortKey = 'date' | 'type' | 'holdingName' | 'broker' | 'amount' | 'quantity'

function TransactionsView({ holdings, displayCurrency, dataVersion, reload }: { holdings: Holding[]; displayCurrency: string; dataVersion: number; reload: () => Promise<void> }) {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [holdingId, setHoldingId] = useState('')
  const [type, setType] = useState('')
  const [broker, setBroker] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [sortKey, setSortKey] = useState<TransactionSortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const brokers = useMemo(() => [...new Set(holdings.map(h => h.broker).filter((b): b is string => !!b))].sort(), [holdings])

  const load = async () => {
    setLoading(true); setError('')
    const params = new URLSearchParams({ currency: displayCurrency })
    if (holdingId) params.set('holdingId', holdingId)
    if (type) params.set('type', type)
    if (broker) params.set('broker', broker)
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    try { setTransactions(await api<Transaction[]>(`/api/transactions?${params}`)) }
    catch (err) { setError(err instanceof Error ? err.message : 'Unable to load transactions') }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [displayCurrency, dataVersion, holdingId, type, broker, from, to])

  const sorted = useMemo(() => {
    if (!sortKey) return transactions
    const dir = sortDir === 'asc' ? 1 : -1
    return [...transactions].sort((a, b) => {
      const av = a[sortKey], bv = b[sortKey]
      return typeof av === 'number' || typeof bv === 'number'
        ? ((av as number ?? -Infinity) - (bv as number ?? -Infinity)) * dir
        : String(av ?? '').localeCompare(String(bv ?? '')) * dir
    })
  }, [transactions, sortKey, sortDir])

  // Click cycles ascending → descending → back to the default (server-returned) order.
  const toggleSort = (key: TransactionSortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: TransactionSortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const remove = async (t: Transaction) => { if (confirm(`Delete this ${label(t.type)} transaction?`)) { await api(`/api/transactions/${t.id}`, { method: 'DELETE' }); await reload() } }

  return <>
    <section className="holdings-toolbar">
      <select className="filter-select" value={holdingId} onChange={e => setHoldingId(e.target.value)}><option value="">All holdings</option>{holdings.map(h => <option key={h.id} value={h.id}>{h.name} @ {h.broker || 'unassigned'}</option>)}</select>
      <select className="filter-select" value={type} onChange={e => setType(e.target.value)}><option value="">All types</option>{transactionTypes.map(t => <option key={t} value={t}>{label(t)}</option>)}</select>
      <select className="filter-select" value={broker} onChange={e => setBroker(e.target.value)}><option value="">All brokers</option>{brokers.map(b => <option key={b} value={b}>{b}</option>)}</select>
      <input className="filter-select date-filter" type="date" value={from} onChange={e => setFrom(e.target.value)} title="From date" />
      <input className="filter-select date-filter" type="date" value={to} onChange={e => setTo(e.target.value)} title="To date" />
      {(holdingId || type || broker || from || to) && <button className="outline compact" onClick={() => { setHoldingId(''); setType(''); setBroker(''); setFrom(''); setTo('') }}>Clear filters</button>}
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary compact push-end" onClick={() => setCreating(true)} disabled={!holdings.length} title={holdings.length ? '' : 'Add a holding first'}>+ Log transaction</button>
    </section>
    {error && <p className="hint">{error}</p>}
    <section className="table-panel"><table>
      <thead><tr>
        <th className="sortable" onClick={() => toggleSort('date')}>Date{arrow('date')}</th>
        <th className="sortable" onClick={() => toggleSort('type')}>Type{arrow('type')}</th>
        <th className="sortable" onClick={() => toggleSort('holdingName')}>Holding{arrow('holdingName')}</th>
        <th className="sortable" onClick={() => toggleSort('broker')}>Broker{arrow('broker')}</th>
        <th className="sortable" onClick={() => toggleSort('amount')}>Amount{arrow('amount')}</th>
        <th className="sortable" onClick={() => toggleSort('quantity')}>Quantity{arrow('quantity')}</th>
        <th>Notes</th>
        <th />
      </tr></thead>
      <tbody>{loading ? <tr><td colSpan={8} className="empty"><strong>Loading…</strong></td></tr> : sorted.length ? sorted.map(t => <tr key={t.id}>
        <td>{since(t.date)}</td>
        <td><span className={`badge txn-${t.type.toLowerCase()}`}>{label(t.type)}</span></td>
        <td><strong className="trunc-name" title={t.holdingName}>{t.holdingName}</strong></td>
        <td><span className="trunc-cell" title={t.broker || ''}>{t.broker || '—'}</span></td>
        <td><strong>{money(t.amount, t.currency)}</strong></td>
        <td>{t.quantity ?? '—'}</td>
        <td className="txn-notes">{t.notes || '—'}</td>
        <td className="actions actions-vertical"><button className="primary-link" onClick={() => setEditing(t)}>Edit</button><button className="danger-link" onClick={() => void remove(t)}>Delete</button></td>
      </tr>) : <tr><td colSpan={8} className="empty"><strong>No transactions match</strong><span>Log a buy, sell, split, interest, or adjustment against a holding.</span>{holdings.length > 0 && <button className="primary" onClick={() => setCreating(true)}>Log transaction</button>}</td></tr>}</tbody>
    </table></section>
    {(creating || editing) && <TransactionModal transaction={editing} holdings={holdings} onClose={() => { setCreating(false); setEditing(null) }} onSaved={() => { setCreating(false); setEditing(null); void reload() }} />}
  </>
}

function TransactionModal({ transaction, holdings, onClose, onSaved }: { transaction: Transaction | null; holdings: Holding[]; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => transaction
    ? { holdingId: transaction.holdingId, type: transaction.type, date: transaction.date.slice(0, 10), amount: String(transaction.amount), quantity: transaction.quantity != null ? String(transaction.quantity) : '', notes: transaction.notes || '' }
    : { holdingId: holdings[0]?.id ?? '', type: 'BUY' as TransactionType, date: new Date().toISOString().slice(0, 10), amount: '', quantity: '', notes: '' })
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const payload = { holdingId: form.holdingId, type: form.type, date: form.date, amount: numeric(form.amount), quantity: form.quantity ? numeric(form.quantity) : null, notes: form.notes || null }
    try {
      await api(transaction ? `/api/transactions/${transaction.id}` : '/api/transactions', { method: transaction ? 'PUT' : 'POST', body: JSON.stringify(payload) })
      onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save transaction') } finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section className="modal"><div className="modal-header"><div><p className="eyebrow">{transaction ? 'EDIT TRANSACTION' : 'NEW TRANSACTION'}</p><h2>{transaction ? label(transaction.type) : 'Log a transaction'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}><div className="form-grid">
      <Field label="Holding" required wide><select required value={form.holdingId} onChange={e => set('holdingId', e.target.value)}>{holdings.map(h => <option key={h.id} value={h.id}>{h.name} @ {h.broker || 'unassigned'}</option>)}</select></Field>
      <Field label="Type" required><select required value={form.type} onChange={e => set('type', e.target.value)}>{transactionTypes.map(t => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
      <Field label="Date" required><input required type="date" value={form.date} onChange={e => set('date', e.target.value)} /></Field>
      <Field label="Amount"><input type="number" min="0" step="0.01" value={form.amount} onChange={e => set('amount', e.target.value)} /></Field>
      <Field label="Quantity"><input type="number" step="any" value={form.quantity} onChange={e => set('quantity', e.target.value)} /></Field>
      <Field label="Notes" wide><textarea maxLength={1024} value={form.notes} onChange={e => set('notes', e.target.value)} placeholder="Optional notes" /></Field>
    </div>
    {error && <p className="form-error">{error}</p>}
    <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving}>{saving ? 'Saving…' : transaction ? 'Save changes' : 'Log transaction'}</button></div>
    </form>
  </section></div>
}

function ImportModal({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const [tab, setTab] = useState<'csv' | 'xml'>('csv')
  const [busy, setBusy] = useState(false)
  const [fileName, setFileName] = useState('')
  const [result, setResult] = useState<ImportResult | null>(null)
  const csvExample = 'id,holdingId,type,date,amount,quantity,notes\n,h-abc123,BUY,2026-01-15,100000,40,Initial buy\n,h-abc123,SELL,2026-03-01,20000,5,Partial profit booking'
  const xmlExample = '<transactions>\n  <transaction>\n    <holdingId>h-abc123</holdingId>\n    <type>BUY</type>\n    <date>2026-01-15</date>\n    <amount>100000</amount>\n    <quantity>40</quantity>\n    <notes>Initial buy</notes>\n  </transaction>\n</transactions>'

  const importFile = async (file: File) => {
    setBusy(true); setResult(null); setFileName(file.name)
    try {
      const isXml = file.name.toLowerCase().endsWith('.xml')
      const text = await file.text()
      const response = await fetch(`${API_URL}/api/transactions/import`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': isXml ? 'application/xml' : 'text/csv', 'X-Demo-User': 'demo@finsights.local' }, body: text,
      })
      const parsed = await response.json()
      if (!response.ok) throw new Error(parsed.message ?? 'Import failed')
      setResult(parsed)
      if (parsed.created || parsed.updated) onImported()
    } catch (err) { setResult({ created: 0, updated: 0, skipped: 0, errors: [{ row: 0, message: err instanceof Error ? err.message : 'Import failed' }] }) }
    finally { setBusy(false) }
  }

  return <div className="modal-backdrop" onClick={onClose}><section className="modal" onClick={e => e.stopPropagation()}>
    <div className="modal-header"><div><p className="eyebrow">BULK UPDATE</p><h2>Import transactions</h2></div><button className="close" onClick={onClose}>×</button></div>

    <div className="import-upload">
      <label className="outline file-label">{busy ? 'Importing…' : fileName ? 'Choose a different file' : '↑ Choose a CSV or XML file'}
        <input type="file" accept=".csv,.xml,text/csv,text/xml,application/xml" hidden disabled={busy}
          onChange={e => { const f = e.target.files?.[0]; if (f) void importFile(f); e.target.value = '' }} />
      </label>
      {fileName && <span className="hint">{fileName}</span>}
    </div>
    {result && <section className={`import-summary ${result.errors.length ? 'has-errors' : ''}`}>
      <strong>{result.created} created · {result.updated} updated · {result.skipped} skipped</strong>
      {result.errors.map((err, i) => <span key={i}>{err.row ? `Row ${err.row}: ` : ''}{err.message}</span>)}
    </section>}

    <p className="hint">Instruments and holdings stay manual — this bulk-imports <b>transactions only</b>, each linked to an existing <b>holdingId</b>. Leave <b>id</b> blank to log a new transaction, or include an existing transaction's id to update it. Use "↓ Export" on this page with your current transactions to get real holding ids to work from.</p>
    <div className="format-tabs">
      <button type="button" className={tab === 'csv' ? 'active' : ''} onClick={() => setTab('csv')}>CSV</button>
      <button type="button" className={tab === 'xml' ? 'active' : ''} onClick={() => setTab('xml')}>XML</button>
    </div>
    <div className="format-fields-scroll"><table className="format-fields">
      <thead><tr><th>Field</th><th>Required</th><th>Notes</th></tr></thead>
      <tbody>
        <tr><td>id</td><td>No</td><td>Existing transaction id → updates it. Blank → logs a new one.</td></tr>
        <tr><td>holdingId</td><td>Yes</td><td>The holding (instrument + broker) this transaction belongs to.</td></tr>
        <tr><td>type</td><td>Yes</td><td>BUY, SELL, SPLIT, INTEREST, ADJUSTMENT.</td></tr>
        <tr><td>date</td><td>Yes</td><td>YYYY-MM-DD.</td></tr>
        <tr><td>amount</td><td>No</td><td>Plain number; default 0.</td></tr>
        <tr><td>quantity</td><td>No</td><td>Plain number, decimals allowed.</td></tr>
        <tr><td>notes</td><td>No</td><td>Free text.</td></tr>
      </tbody>
    </table></div>
    <div className="panel-heading"><h3>Example {tab.toUpperCase()}</h3></div>
    <pre className="format-example">{tab === 'csv' ? csvExample : xmlExample}</pre>
    <div className="modal-actions"><button className="primary" onClick={onClose}>Close</button></div>
  </section></div>
}

// ---------------------------------------------------------------------------

// Insights is deliberately not a second Overview: the shared breakdown/movers data that also
// appears on the Dashboard lives in the collapsed "Portfolio overview" section at the bottom.
// This page's own job is Hot picks (holdings + watchlist symbols moving beyond a configured
// threshold) and data-quality checks.
function InsightsView({ displayCurrency, dataVersion, settings, reload, onOpen }: {
  displayCurrency: string; dataVersion: number; settings: Settings; reload: () => Promise<void>; onOpen: (id: string) => void
}) {
  const [data, setData] = useState<Insights | null>(null)
  const [error, setError] = useState('')
  const [hotPicks, setHotPicks] = useState<HotPick[] | null>(null)
  const [watchlist, setWatchlist] = useState<WatchlistEntry[]>([])
  const [thresholds, setThresholds] = useState(() => thresholdForm(settings))
  const [savingThresholds, setSavingThresholds] = useState(false)
  const [addingWatch, setAddingWatch] = useState(false)
  const [editingWatch, setEditingWatch] = useState<WatchlistEntry | null>(null)
  const [thresholdsOpen, setThresholdsOpen] = useState(false)
  const [watchlistOpen, setWatchlistOpen] = useState(false)
  const [overviewOpen, setOverviewOpen] = useState(false)

  useEffect(() => { api<Insights>(`/api/insights?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message)) }, [displayCurrency, dataVersion])
  const loadHotPicks = () => api<HotPick[]>(`/api/insights/hot-picks?currency=${displayCurrency}`).then(setHotPicks).catch(() => setHotPicks([]))
  const loadWatchlist = () => api<WatchlistEntry[]>('/api/watchlist').then(setWatchlist).catch(() => setWatchlist([]))
  useEffect(() => { void loadHotPicks() }, [displayCurrency, dataVersion])
  useEffect(() => { void loadWatchlist() }, [dataVersion])
  useEffect(() => { setThresholds(thresholdForm(settings)) }, [settings])

  const saveThresholds = async () => {
    setSavingThresholds(true)
    try {
      await api('/api/settings', { method: 'PUT', body: JSON.stringify({
        country: settings.country, displayName: settings.displayName, phone: settings.phone, numberFormat: settings.numberFormat,
        notifyEmail: settings.notifyEmail, notifySms: settings.notifySms, notifyPush: settings.notifyPush,
        notifyThresholdPercent: settings.notifyThresholdPercent,
        dailyThresholdPercent: numOrNull(thresholds.DAILY), weeklyThresholdPercent: numOrNull(thresholds.WEEKLY),
        monthlyThresholdPercent: numOrNull(thresholds.MONTHLY), quarterlyThresholdPercent: numOrNull(thresholds.QUARTERLY),
        yearlyThresholdPercent: numOrNull(thresholds.YEARLY),
      }) })
      await reload(); await loadHotPicks()
    } finally { setSavingThresholds(false) }
  }
  const removeWatch = async (item: WatchlistEntry) => {
    if (!confirm(`Remove ${item.name} from the watchlist?`)) return
    await api(`/api/watchlist/${item.id}`, { method: 'DELETE' }); await loadWatchlist(); await loadHotPicks()
  }

  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading insights…</p>
  const total = data.byCategory.reduce((sum, item) => sum + item.value, 0)
  const thresholdsSet = Object.values(thresholds).filter(value => value.trim() !== '').length

  return <>
    <section className="panel">
      <div className="panel-heading"><h3>Hot picks</h3><span>Movement beyond your thresholds</span></div>

      {hotPicks === null ? <p className="hint">Loading hot picks…</p> : hotPicks.length ? <div className="hot-pick-list">
        {hotPicks.map(pick => <button key={`${pick.subjectType}-${pick.id}`} className={`hot-pick${pick.subjectType === 'HOLDING' ? '' : ' static'}`}
            onClick={() => pick.subjectType === 'HOLDING' && onOpen(pick.id)}>
          <div className="hot-pick-name"><strong>{pick.name}</strong><small>{pick.categoryName || pick.tickerSymbol || 'Watchlist'}</small></div>
          <div className="hot-pick-value">{pick.currency ? money(pick.currentValue ?? 0, pick.currency) : (pick.currentValue ?? 0).toLocaleString(numberLocale)}</div>
          <div className="hot-pick-badges">{pick.triggered.map(t => <span key={t.period} className={`hot-badge ${t.percent >= 0 ? 'positive' : 'negative'}`}>{periodLabels[t.period]} {t.percent >= 0 ? '+' : ''}{t.percent.toFixed(2)}%</span>)}</div>
        </button>)}
      </div> : thresholdsSet === 0
        ? <p className="hint">No thresholds set yet — open <button className="inline-link" onClick={() => setThresholdsOpen(true)}>Movement thresholds</button> to choose how far a holding has to move before it lands here.</p>
        : <p className="hint">Nothing is outside your configured thresholds right now.</p>}

      <button className="section-toggle" onClick={() => setThresholdsOpen(open => !open)}>
        <span>Movement thresholds</span>
        <span className="toggle-meta">{thresholdsSet ? `${thresholdsSet} set` : 'none set'} {thresholdsOpen ? '▴' : '▾'}</span>
      </button>
      {thresholdsOpen && <div className="threshold-row">
        {periodFields.map(([key, text]) => <Field label={`${text} threshold %`} key={key}>
          <input type="number" min="0" step="0.1" placeholder="e.g. 5" value={thresholds[key]}
            onChange={e => setThresholds(current => ({ ...current, [key]: e.target.value }))} />
        </Field>)}
        <button className="primary compact" onClick={() => void saveThresholds()} disabled={savingThresholds}>{savingThresholds ? 'Saving…' : 'Save thresholds'}</button>
      </div>}

      <button className="section-toggle" onClick={() => setWatchlistOpen(open => !open)}>
        <span>Watchlist</span>
        <span className="toggle-meta">{watchlist.length ? `${watchlist.length} tracked` : 'empty'} {watchlistOpen ? '▴' : '▾'}</span>
      </button>
      {watchlistOpen && <div className="watchlist-body">
        <div className="watchlist-actions"><button className="outline compact" onClick={() => setAddingWatch(true)}>+ Add to watchlist</button></div>
        {watchlist.length ? <div className="table-panel"><table>
          <thead><tr><th>Name</th><th>Ticker</th><th>Price</th><th>Last updated</th><th /></tr></thead>
          <tbody>{watchlist.map(item => <tr key={item.id}>
            <td><strong>{item.name}</strong>{item.notes && <small className="owner">{item.notes}</small>}</td>
            <td>{item.tickerSymbol || '—'}</td>
            <td>{item.currentValue != null ? item.currentValue.toLocaleString(numberLocale) : '—'}</td>
            <td>{since(item.lastUpdated)}</td>
            <td className="actions actions-vertical"><button className="primary-link" onClick={() => setEditingWatch(item)}>Edit</button><button className="danger-link" onClick={() => void removeWatch(item)}>Delete</button></td>
          </tr>)}</tbody>
        </table></div> : <p className="hint">Track a symbol you don't hold — like an index or a stock you're watching — to get it into Hot picks too.</p>}
      </div>}
    </section>

    <section className="panel data-quality">
      <div className="panel-heading"><h3>Data-quality checks</h3><span>{data.warnings.length} items</span></div>
      {data.warnings.length ? <div className="warning-list">{data.warnings.map((w, i) => <button key={i} className={`warning ${w.severity.toLowerCase()}`} onClick={() => w.holdingId && onOpen(w.holdingId)}>
        <b>{w.severity}</b><span>{w.holdingName ? `${w.holdingName} — ` : ''}{w.message}</span>
      </button>)}</div> : <p className="hint">Everything looks consistent. Nice.</p>}
    </section>

    <section className="overview-section">
      <button className="overview-toggle" onClick={() => setOverviewOpen(current => !current)}>
        <h3>Portfolio overview</h3><span>{overviewOpen ? '▴ Hide' : '▾ Show'}</span>
      </button>
      {overviewOpen && <div className="insight-grid">
        <BreakdownCard title="By category" items={data.byCategory} total={total} />
        <BreakdownCard title="By broker" items={data.byBroker} total={total} />
        <BreakdownCard title="By tag" items={data.byTag} total={total} />
        <BreakdownCard title="By currency" items={data.byCurrency} total={total} />
        <BreakdownCard title="By liquidity" items={data.byLiquidity} total={total} />
        <article className="panel">
          <div className="panel-heading"><h3>Movers</h3><span>Top 5 each way</span></div>
          <div className="mover-list">
            {data.topGainers.map(m => <button key={m.id} className="mover" onClick={() => onOpen(m.id)}><span>{m.name}</span><b className="positive">+{money(m.profitLoss)} · {percent(m.profitLossPercentage)}</b></button>)}
            {data.topLosers.map(m => <button key={m.id} className="mover" onClick={() => onOpen(m.id)}><span>{m.name}</span><b className="negative">{money(m.profitLoss)} · {percent(m.profitLossPercentage)}</b></button>)}
            {!data.topGainers.length && !data.topLosers.length && <p className="hint">No profit or loss recorded yet.</p>}
          </div>
        </article>
      </div>}
    </section>

    {(addingWatch || editingWatch) && <WatchlistModal item={editingWatch}
      onClose={() => { setAddingWatch(false); setEditingWatch(null) }}
      onSaved={() => { setAddingWatch(false); setEditingWatch(null); void loadWatchlist(); void loadHotPicks() }} />}
  </>
}

function thresholdForm(settings: Settings): Record<PeriodKey, string> {
  return {
    DAILY: settings.dailyThresholdPercent != null ? String(settings.dailyThresholdPercent) : '',
    WEEKLY: settings.weeklyThresholdPercent != null ? String(settings.weeklyThresholdPercent) : '',
    MONTHLY: settings.monthlyThresholdPercent != null ? String(settings.monthlyThresholdPercent) : '',
    QUARTERLY: settings.quarterlyThresholdPercent != null ? String(settings.quarterlyThresholdPercent) : '',
    YEARLY: settings.yearlyThresholdPercent != null ? String(settings.yearlyThresholdPercent) : '',
  }
}
const numOrNull = (value: string) => value === '' ? null : Number(value)

function WatchlistModal({ item, onClose, onSaved }: { item: WatchlistEntry | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => ({
    name: item?.name ?? '', tickerSymbol: item?.tickerSymbol ?? '', notes: item?.notes ?? '',
    price: item?.currentValue != null ? String(item.currentValue) : '',
  }))
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))
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
        <Field label="Name" required wide><input required maxLength={128} value={form.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Nifty 50, Bitcoin" /></Field>
        <Field label="Ticker"><input value={form.tickerSymbol} onChange={e => set('tickerSymbol', e.target.value.toUpperCase())} placeholder="e.g. NIFTY, BTC" /></Field>
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

function BrokersView({ displayCurrency, dataVersion }: { displayCurrency: string; dataVersion: number }) {
  const [data, setData] = useState<Brokers | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<Brokers>(`/api/brokers?currency=${displayCurrency}`).then(setData).catch(e => setError(e.message)) }, [displayCurrency, dataVersion])
  if (error) return <p className="hint">{error}</p>
  if (!data) return <p className="hint">Loading brokers…</p>
  return <>
    <section className="broker-grid">{data.brokers.map(b => <article className="panel broker-card" key={b.name}>
      <div className="panel-heading"><h3>{b.name}</h3><span>{b.holdingCount} holding{b.holdingCount === 1 ? '' : 's'}</span></div>
      <strong className="broker-value">{money(b.currentValue)}</strong>
      <div className="broker-meta"><span>Invested {money(b.investedValue)}</span><span className={b.profitLoss >= 0 ? 'positive' : 'negative'}>{b.profitLoss >= 0 ? '+' : ''}{money(b.profitLoss)}</span></div>
      <div className="tag-row">{b.categories.map(c => <em key={c}>{c}</em>)}</div>
      <p className="hint">Last change {since(b.lastUpdated)}{b.currencies.length > 1 ? ` · ${b.currencies.join(', ')}` : ''}</p>
    </article>)}{!data.brokers.length && <p className="hint">Assign holdings to a broker to see them grouped here.</p>}</section>
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

function SettingsView({ settings, countries, dashboard, holdings, reload, theme, setTheme }: {
  settings: Settings; countries: Country[]; dashboard: Dashboard | null; holdings: Holding[]
  reload: () => Promise<void>; theme: Theme; setTheme: React.Dispatch<React.SetStateAction<Theme>>
}) {
  const [form, setForm] = useState({
    displayName: settings.displayName, phone: settings.phone || '', country: settings.country,
    numberFormat: settings.numberFormat, notifyEmail: settings.notifyEmail, notifySms: settings.notifySms,
    notifyPush: settings.notifyPush, notifyThresholdPercent: String(settings.notifyThresholdPercent),
  })
  const [status, setStatus] = useState('')
  const [confirmText, setConfirmText] = useState('')
  const selectedCountry = countries.find(c => c.code === form.country)
  const set = <K extends keyof typeof form>(key: K, value: typeof form[K]) => setForm(current => ({ ...current, [key]: value }))
  const save = async () => {
    setStatus('saving')
    try {
      await api('/api/settings', { method: 'PUT', body: JSON.stringify({
        country: form.country, displayName: form.displayName, phone: form.phone, numberFormat: form.numberFormat,
        notifyEmail: form.notifyEmail, notifySms: form.notifySms, notifyPush: form.notifyPush,
        notifyThresholdPercent: numeric(form.notifyThresholdPercent),
      }) })
      await reload(); setStatus('saved')
    } catch (e) { setStatus(e instanceof Error ? e.message : 'Could not save') }
  }
  const exportJson = async () => {
    const response = await fetch(`${API_URL}/api/account/export`, { credentials: 'include', headers: { 'X-Demo-User': 'demo@finsights.local' } })
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = 'finsights-export.json'; link.click()
    URL.revokeObjectURL(url)
  }
  const deleteAccount = async () => {
    if (confirmText !== 'DELETE') return
    await api('/api/account', { method: 'DELETE' })
    await reload()
  }
  const brokersConnected = new Set(holdings.map(h => h.broker).filter((b): b is string => !!b)).size
  const saveBar = <>
    <button className="primary" onClick={() => void save()} disabled={status === 'saving'}>{status === 'saving' ? 'Saving…' : 'Save changes'}</button>
    {status && status !== 'saving' && <span className="settings-status">{status === 'saved' ? 'Saved.' : status}</span>}
  </>

  return <div className="settings-grid">
    <article className="panel">
      <div className="panel-heading"><h3>User profile</h3><span>Who you are</span></div>
      <div className="settings-field"><label>Display name</label><input value={form.displayName} onChange={e => set('displayName', e.target.value)} /></div>
      <div className="settings-field"><label>Email</label><input value={settings.email} disabled title="Managed by your sign-in provider" /></div>
      <div className="settings-field"><label>Contact number</label><input value={form.phone} onChange={e => set('phone', e.target.value)} placeholder="+91 98765 43210" /></div>
      <div className="settings-field">
        <label>Country of residence</label>
        <select value={form.country} onChange={e => set('country', e.target.value)}>{countries.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}</select>
      </div>
      <p className="hint">Your base currency follows your country: <b>{selectedCountry?.currency ?? settings.baseCurrency}</b>. Any page also has a "View in" dropdown for a one-off switch, using static reference rates.</p>
      {saveBar}
    </article>

    <article className="panel">
      <div className="panel-heading"><h3>User preferences</h3><span>How the app looks &amp; behaves</span></div>
      <div className="settings-field">
        <label>Appearance</label>
        <Switch checked={theme === 'light'} onChange={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} text={theme === 'dark' ? 'Dark' : 'Light'} icon={theme === 'dark' ? '🌙' : '☀'} />
      </div>
      <div className="settings-field">
        <label>Number system</label>
        <select value={form.numberFormat} onChange={e => set('numberFormat', e.target.value as Settings['numberFormat'])}>
          <option value="INDIAN">Indian (Lakh, Crore — e.g. 12,34,567)</option>
          <option value="INTERNATIONAL">International (Million, Billion — e.g. 1,234,567)</option>
        </select>
      </div>
      <div className="settings-field">
        <label>Two-factor authentication</label>
        <Switch checked={false} disabled text="Off — needs Google Sign-In" icon="🔒" title="2FA becomes available once Google Sign-In replaces demo mode" />
      </div>
      {saveBar}
    </article>

    <article className="panel">
      <div className="panel-heading"><h3>Notification preferences</h3><span>Channels &amp; limits</span></div>
      <p className="hint">Alerts aren't sent yet — these preferences are saved now so they take effect as soon as alerting ships.</p>
      <div className="check-row settings-checks">
        <label><input type="checkbox" checked={form.notifyEmail} onChange={e => set('notifyEmail', e.target.checked)} /> Email</label>
        <label><input type="checkbox" checked={form.notifySms} onChange={e => set('notifySms', e.target.checked)} /> SMS</label>
        <label><input type="checkbox" checked={form.notifyPush} onChange={e => set('notifyPush', e.target.checked)} /> Push</label>
      </div>
      <div className="settings-field">
        <label>Notify on moves greater than</label>
        <div className="inline-field"><input type="number" min="0" step="0.5" value={form.notifyThresholdPercent} onChange={e => set('notifyThresholdPercent', e.target.value)} /><span>%</span></div>
      </div>
      {saveBar}
    </article>

    <article className="panel">
      <div className="panel-heading"><h3>Account management</h3><span>Member since {since(settings.memberSince)}</span></div>
      <div className="pulse-row"><span>Net worth</span><strong>{dashboard ? money(dashboard.netWorth) : '—'}</strong></div>
      <div className="pulse-row"><span>Holdings tracked</span><strong>{settings.holdingCount}</strong></div>
      <div className="pulse-row"><span>Brokers connected</span><strong>{brokersConnected}</strong></div>
      <div className="pulse-row"><span>Authentication</span><strong>{settings.demoMode ? 'Demo mode' : 'Google'}</strong></div>
      <button className="outline" onClick={() => void exportJson()}>Export all data (JSON)</button>
      <div className="danger-zone-inline">
        <div className="panel-heading"><h3>Delete account</h3><span>Cannot be undone</span></div>
        <p className="hint">This permanently removes every instrument, holding, transaction, and your profile. Type <b>DELETE</b> to confirm.</p>
        <div className="settings-field"><input value={confirmText} onChange={e => setConfirmText(e.target.value)} placeholder="DELETE" /></div>
        <button className="danger-btn" onClick={() => void deleteAccount()} disabled={confirmText !== 'DELETE'}>Delete everything</button>
      </div>
    </article>
  </div>
}

function Switch({ checked, onChange, text, icon, disabled, title }: { checked: boolean; onChange?: () => void; text: string; icon?: string; disabled?: boolean; title?: string }) {
  return <label className={`switch${disabled ? ' disabled' : ''}`} title={title}>
    <input type="checkbox" checked={checked} disabled={disabled} onChange={onChange} />
    <span className="switch-track"><span className="switch-thumb" /></span>
    <span className="switch-text">{icon && <span className="icon">{icon}</span>}{text}</span>
  </label>
}

function Field({ label: title, children, required, wide }: { label: string; children: React.ReactNode; required?: boolean; wide?: boolean }) { return <label className={wide ? 'field wide' : 'field'}><span>{title}{required && <b> *</b>}</span>{children}</label> }

// A small ⓘ dot that shows `text` in a floating tooltip on hover/focus. Positioned with
// position:fixed off the icon's rect so it never gets clipped by a table's overflow.
function InfoTip({ text }: { text: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [tip, setTip] = useState<{ left: number; top: number; above: boolean } | null>(null)
  const show = () => {
    const r = ref.current?.getBoundingClientRect()
    if (!r) return
    const vw = window.innerWidth || document.documentElement.clientWidth || 1024
    const vh = window.innerHeight || document.documentElement.clientHeight || 768
    const left = Math.min(Math.max(r.left + r.width / 2, 150), Math.max(vw - 150, 150))
    const above = r.bottom > vh - 130
    setTip({ left, top: above ? r.top - 8 : r.bottom + 8, above })
  }
  return <span ref={ref} className="info-dot" tabIndex={0}
    onMouseEnter={show} onMouseLeave={() => setTip(null)} onFocus={show} onBlur={() => setTip(null)}
    onClick={e => e.stopPropagation()} aria-label={`Description: ${text}`}>i
    {tip && <span className={`info-tip${tip.above ? ' above' : ''}`} style={{ left: tip.left, top: tip.top }}>{text}</span>}
  </span>
}

// A LinkedIn-style tag editor: selected tags as removable chips, a text field
// that filters `suggestions` into a dropdown as you type (with a "create"
// row), and a row of not-yet-picked popular suggestions underneath.
function TagInput({ tags, suggestions, onChange }: { tags: string[]; suggestions: string[]; onChange: (next: string[]) => void }) {
  const [input, setInput] = useState('')
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const MAX_TAGS = 30
  const full = tags.length >= MAX_TAGS
  const norm = (t: string) => t.trim().toLowerCase().slice(0, 48)
  const has = (t: string) => tags.some(x => norm(x) === norm(t))
  const add = (raw: string) => { const t = norm(raw); if (t && !has(t) && !full) onChange([...tags, t]); setInput(''); setOpen(false) }
  const remove = (t: string) => onChange(tags.filter(x => x !== t))

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const typed = norm(input)
  const matches = suggestions.filter(s => !has(s) && (!typed || s.includes(typed))).slice(0, 8)
  const showCreate = !!typed && !suggestions.some(s => s === typed) && !has(typed)
  const popular = suggestions.filter(s => !has(s)).slice(0, 8)

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <div className="tag-input-box" onClick={() => setOpen(true)}>
        {tags.map(t => <span className="tag-chip" key={t} title={t}><span className="tag-chip-label">{t}</span><button type="button" aria-label={`Remove ${t}`} onClick={e => { e.stopPropagation(); remove(t) }}>×</button></span>)}
        {!full && <input value={input} maxLength={48} placeholder={tags.length ? 'Add another…' : 'Search or add a tag'}
          onFocus={() => setOpen(true)}
          onChange={e => { setInput(e.target.value); setOpen(true) }}
          onKeyDown={e => {
            if (e.key === 'Enter' && typed) { e.preventDefault(); add(input) }
            else if (e.key === 'Backspace' && !input && tags.length) remove(tags[tags.length - 1])
            else if (e.key === 'Escape') setOpen(false)
          }} />}
      </div>
      {open && !full && (matches.length > 0 || showCreate) && <ul className="tag-menu">
        {matches.map(s => <li key={s}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => add(s)}>{s}</button></li>)}
        {showCreate && <li><button type="button" className="tag-menu-create" onMouseDown={e => e.preventDefault()} onClick={() => add(input)}>Add “{input.trim().toLowerCase()}”</button></li>}
      </ul>}
    </div>
    {full
      ? <p className="tag-limit">30-tag limit reached — remove one to add another.</p>
      : popular.length > 0 && <div className="tag-ideas">
          <span>Popular</span>
          {popular.map(t => <button type="button" key={t} className="tag-idea" onClick={() => add(t)}>+ {t}</button>)}
        </div>}
  </div>
}

// Ticker type-ahead backed by /api/market/search (Yahoo Finance). Free text is
// still accepted; picking a row commits the exchange symbol (e.g. RELIANCE.NS).
function SymbolSearchInput({ value, onChange }: { value: string; onChange: (symbol: string) => void }) {
  const [query, setQuery] = useState(value)
  const [results, setResults] = useState<SymbolSuggestion[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const justPicked = useRef(false)

  useEffect(() => {
    if (justPicked.current) { justPicked.current = false; return }
    const q = query.trim()
    if (q.length < 2) { setResults([]); setLoading(false); return }
    setLoading(true)
    const timer = setTimeout(() => {
      api<SymbolSuggestion[]>(`/api/market/search?q=${encodeURIComponent(q)}`)
        .then(setResults).catch(() => setResults([])).finally(() => setLoading(false))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const pick = (s: SymbolSuggestion) => { justPicked.current = true; setQuery(s.symbol); onChange(s.symbol); setResults([]); setOpen(false) }

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <input value={query} placeholder="Search RELIANCE, INFY, BTC-USD…"
        onFocus={() => setOpen(true)}
        onChange={e => { const v = e.target.value.toUpperCase(); setQuery(v); onChange(v); setOpen(true) }} />
      {open && (loading || results.length > 0) && <ul className="tag-menu">
        {loading && results.length === 0 && <li className="tag-menu-note">Searching…</li>}
        {results.map(s => <li key={s.symbol}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => pick(s)}>
          <b>{s.symbol}</b> <span>{s.name}{s.exchange ? ` · ${s.exchange}` : ''}</span>
        </button></li>)}
      </ul>}
    </div>
  </div>
}

// ---------------------------------------------------------------------------
// Public site — a marketing homepage and a minimal Google sign-in screen sit
// in front of the authenticated app shell. "Entered" is remembered locally so
// a returning visitor skips straight back into the app; "Sign out" clears it.
// ---------------------------------------------------------------------------

const ENTERED_KEY = 'finsights-entered-app'
type Phase = 'home' | 'login' | 'app'

function Root() {
  const [phase, setPhase] = useState<Phase>(() => {
    try { return localStorage.getItem(ENTERED_KEY) === 'true' ? 'app' : 'home' } catch { return 'home' }
  })
  const [loginMode, setLoginMode] = useState<'login' | 'signup'>('signup')

  const enterApp = () => {
    try { localStorage.setItem(ENTERED_KEY, 'true') } catch { /* storage unavailable */ }
    setPhase('app')
  }
  const exitApp = () => {
    try { localStorage.removeItem(ENTERED_KEY) } catch { /* storage unavailable */ }
    setPhase('home')
  }
  const goToLogin = (mode: 'login' | 'signup') => { setLoginMode(mode); setPhase('login') }

  // A real (non-demo) session means the user is already signed in — skip straight into the app,
  // e.g. after returning from the Google redirect.
  useEffect(() => {
    api<User>('/api/auth/me').then(me => { if (!me.demoMode) enterApp() }).catch(() => { /* not signed in */ })
  }, [])

  if (phase === 'home') return <Homepage onGetStarted={() => goToLogin('signup')} onSignIn={() => goToLogin('login')} />
  if (phase === 'login') return <LoginScreen initialMode={loginMode} onBack={() => setPhase('home')} onEnter={enterApp} />
  return <App onSignOut={exitApp} />
}

const homeFeatures: [string, string, string][] = [
  ['◈', 'Categories & holdings', 'Define your own buckets — Growth Equity, Emergency Fund, whatever makes sense to you — and file every stock, fund, FD, or loan under one you own.'],
  ['⇄', 'FX-aware, base-currency first', 'Every value converts on the fly against your base currency, with the live conversion rate shown — never hidden behind a single blended number.'],
  ['▤', 'Manual-first, by design', 'No live broker sync yet, so every figure is one you entered and can trust. Bulk-import transactions from CSV or XML the moment you\'re ready.'],
]

function Homepage({ onGetStarted, onSignIn }: { onGetStarted: () => void; onSignIn: () => void }) {
  return <div className="public-page">
    <header className="public-nav">
      <div className="brand"><div className="mark">F</div><span>FinSights</span></div>
      <nav className="public-nav-links">
        <a href="#features">Features</a>
        <a href="#privacy">Privacy</a>
      </nav>
      <button className="outline compact" onClick={onSignIn}>Log in</button>
    </header>

    <section className="public-hero">
      <div className="public-hero-copy">
        <p className="eyebrow">PORTFOLIO INTELLIGENCE</p>
        <h1>Know what your portfolio needs, not just what it's worth.</h1>
        <p className="public-lede">Bring your categories, holdings, and transactions together in one place, and FinSights reads the whole picture — surfacing what's moved, what's drifted, and what actually needs your attention.</p>
        <div className="public-cta-row">
          <button className="primary" onClick={onGetStarted}>Get started</button>
          <a className="outline" href="#features">Explore features</a>
        </div>
        <ul className="public-checklist">
          <li>✓ Intelligence that flags what needs a look</li>
          <li>✓ Your data lives in your own backend</li>
          <li>✓ Built for long-term, hands-on investors</li>
        </ul>
      </div>
      <div className="public-hero-preview" aria-hidden="true">
        <div className="preview-card">
          <p className="preview-label">Current portfolio value · Preview</p>
          <strong className="preview-value">₹33,05,835</strong>
          <span className="positive">↑ ₹1,21,335 total gain/loss</span>
          <div className="preview-rows">
            <div className="preview-row"><span>Growth Equity</span><b>₹1,97,850</b></div>
            <div className="preview-row"><span>Fixed Income</span><b>₹2,34,315</b></div>
            <div className="preview-row"><span>Gold &amp; Commodities</span><b>₹62,000</b></div>
          </div>
        </div>
      </div>
    </section>

    <section className="public-features" id="features">
      {homeFeatures.map(([icon, title, copy]) => <article className="public-feature-card" key={title}>
        <div className="mark">{icon}</div>
        <h3>{title}</h3>
        <p>{copy}</p>
      </article>)}
    </section>

    <section className="public-band">
      <div>
        <p className="eyebrow">WHAT YOUR PORTFOLIO IS ASKING FOR</p>
        <h2>The numbers, read for you.</h2>
        <p className="public-lede">Set movement thresholds — daily to yearly — for holdings you own and symbols you're just watching, and Hot picks surfaces the ones that broke them. Data-quality checks catch missing values, unassigned brokers, and currency mismatches before they skew anything. Allocation drift, concentration risk, and rebalancing prompts are next.</p>
      </div>
    </section>

    <section className="public-band alt" id="privacy">
      <div>
        <p className="eyebrow">PRIVACY-FIRST</p>
        <h2>Your data, your backend.</h2>
        <p className="public-lede">FinSights doesn't sell data or run ads. Everything you enter is stored in your own backend — create an account with email or Google when you're ready to make it permanent.</p>
      </div>
    </section>

    <section className="public-cta-band">
      <h2>A clearer read on what you own.</h2>
      <button className="primary" onClick={onGetStarted}>Get started</button>
    </section>

    <footer className="public-footer">
      <div className="brand"><div className="mark">F</div><span>FinSights</span></div>
      <p>Portfolio intelligence for long-term investors.</p>
      <small>© {new Date().getFullYear()} FinSights.</small>
    </footer>
  </div>
}

function LoginScreen({ initialMode, onBack, onEnter }: { initialMode: 'login' | 'signup'; onBack: () => void; onEnter: () => void }) {
  const [mode, setMode] = useState<'login' | 'signup'>(initialMode)
  const [googleEnabled, setGoogleEnabled] = useState(false)
  const [demoEnabled, setDemoEnabled] = useState(false)
  const [form, setForm] = useState({ email: '', password: '', displayName: '' })
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))

  useEffect(() => {
    api<{ googleEnabled: boolean; demoEnabled: boolean }>('/api/auth/config')
      .then(config => { setGoogleEnabled(config.googleEnabled); setDemoEnabled(config.demoEnabled) })
      .catch(() => { /* keep both off */ })
  }, [])

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      await api(mode === 'signup' ? '/api/auth/register' : '/api/auth/login', {
        method: 'POST',
        body: JSON.stringify(mode === 'signup'
          ? { email: form.email, displayName: form.displayName || null, password: form.password }
          : { email: form.email, password: form.password }),
      })
      onEnter()
    } catch (err) { setError(err instanceof Error ? err.message : 'Something went wrong') } finally { setBusy(false) }
  }
  const continueWithGoogle = () => { window.location.href = `${API_URL}/oauth2/authorization/google` }

  return <div className="public-page login-page">
    <header className="public-nav"><button className="brand brand-btn" onClick={onBack}><div className="mark">F</div><span>FinSights</span></button></header>
    <div className="login-center">
      <section className="modal narrow login-card">
        <h2>{mode === 'signup' ? 'Create your account' : 'Log in'}</h2>
        <form onSubmit={submit} className="auth-form">
          {mode === 'signup' && <Field label="Name"><input value={form.displayName} onChange={e => set('displayName', e.target.value)} placeholder="Your name" autoComplete="name" /></Field>}
          <Field label="Email" required><input type="email" required value={form.email} onChange={e => set('email', e.target.value)} placeholder="you@example.com" autoComplete="email" /></Field>
          <Field label="Password" required><input type="password" required minLength={mode === 'signup' ? 8 : undefined} value={form.password} onChange={e => set('password', e.target.value)} placeholder={mode === 'signup' ? 'At least 8 characters' : ''} autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} /></Field>
          {error && <p className="form-error">{error}</p>}
          <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'signup' ? 'Create account' : 'Log in'}</button>
        </form>
        <p className="auth-toggle">
          {mode === 'signup' ? 'Already have an account? ' : 'New to FinSights? '}
          <button type="button" onClick={() => { setMode(mode === 'signup' ? 'login' : 'signup'); setError('') }}>
            {mode === 'signup' ? 'Log in' : 'Create an account'}
          </button>
        </p>
        <div className="auth-divider"><span>or</span></div>
        <button type="button" className="outline google-btn" onClick={continueWithGoogle} disabled={!googleEnabled}
          title={googleEnabled ? '' : 'Set APP_AUTH_MODE=google on the backend to enable Google sign-in'}>Continue with Google</button>
        {!googleEnabled && <p className="auth-note">Google sign-in isn't configured on this deployment.</p>}
        {demoEnabled && <button type="button" className="link-back" onClick={onEnter}>Skip — explore the demo without an account</button>}
        <button type="button" className="link-back" onClick={onBack}>← Back to homepage</button>
      </section>
    </div>
  </div>
}

createRoot(document.getElementById('root')!).render(<Root />)
