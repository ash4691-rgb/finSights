import type { ManualType, Frequency, RepaymentFrequency, PeriodKey, TransactionType, Page, HoldingKind, ValuationMethod, Theme, AreaUnit } from './types'

export const frequencies: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUALLY', 'AT_MATURITY']
export const frequencyLabel = (f: Frequency) => f === 'ANNUALLY' ? 'Yearly' : f === 'AT_MATURITY' ? 'At maturity'
  : f.toLowerCase().replace(/_/g, '-').replace(/\b\w/g, c => c.toUpperCase())
export const repaymentFrequencies: RepaymentFrequency[] = ['WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY', 'ONE_TIME']
export const repaymentLabel = (f: RepaymentFrequency) => f === 'ONE_TIME' ? 'One-time' : f.charAt(0) + f.slice(1).toLowerCase()
// Interest-per-period as a fraction of the outstanding balance — mirrors the backend's own
// TransactionService.periodFraction() so the two amortization pictures never disagree.
const repaymentPeriodFraction = (f: RepaymentFrequency) => f === 'WEEKLY' ? 1 / 52 : f === 'MONTHLY' ? 1 / 12 : f === 'QUARTERLY' ? 0.25 : 1

export type LoanTermCheck = { term: number | null; error: string | null }
// Simulates the same interest-then-principal split EmiService.markPaid() applies to each real
// instalment, to (a) suggest how many instalments a new recurring loan needs and (b) catch inputs
// that can't add up — an instalment too small to ever amortize the balance, or too large for a
// multi-instalment schedule — before they're saved. Returns { term: null, error: null } while the
// inputs aren't filled in yet, so callers don't show a warning on a half-filled form.
export function checkLoanTerm(outstanding: number, instalment: number, annualRatePercent: number, frequency: RepaymentFrequency): LoanTermCheck {
  if (!(outstanding > 0) || !(instalment > 0) || frequency === 'ONE_TIME') return { term: null, error: null }
  if (instalment > outstanding) {
    return { term: null, error: 'The instalment amount is more than the outstanding balance — a recurring schedule needs more than one instalment left. Lower the instalment, or switch to a one-time repayment.' }
  }
  const periodFraction = repaymentPeriodFraction(frequency)
  const rate = (annualRatePercent || 0) / 100
  let balance = outstanding, periods = 0
  const MAX_PERIODS = 1200 // a generous cap (100 years of monthly instalments) against a runaway loop
  while (balance > 0.01 && periods < MAX_PERIODS) {
    const interest = balance * rate * periodFraction
    const principal = instalment - interest
    if (principal <= 0) {
      return { term: null, error: 'This instalment doesn’t cover the interest on the outstanding balance — the loan would never be paid off. Increase the instalment or lower the interest rate.' }
    }
    balance -= principal
    periods += 1
  }
  if (periods >= MAX_PERIODS) {
    return { term: null, error: 'These numbers imply an unrealistically long repayment — double-check the instalment amount, outstanding balance, and interest rate.' }
  }
  if (periods === 0) {
    return { term: null, error: 'The outstanding balance is already covered — this loan doesn’t need a recurring instalment.' }
  }
  return { term: periods, error: null }
}
// Matches the price-history chart's own range buttons (1D/1W/1M/3M/6M/1Y) for one consistent
// vocabulary across Hot Picks thresholds, mover badges, and ViewMoverItem.
export const periodLabels: Record<PeriodKey, string> = { DAILY: '1D', WEEKLY: '1W', MONTHLY: '1M', QUARTERLY: '3M', HALF_YEARLY: '6M', YEARLY: '1Y' }
export const periodFields: [PeriodKey, string][] = [
  ['DAILY', '1D'], ['WEEKLY', '1W'], ['MONTHLY', '1M'], ['QUARTERLY', '3M'], ['HALF_YEARLY', '6M'], ['YEARLY', '1Y'],
]
export const transactionTypes: TransactionType[] = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT', 'REPAY']
export const assetTxnTypes: TransactionType[] = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT']
export const liabilityTxnTypes: TransactionType[] = ['REPAY', 'ADJUSTMENT', 'BUY']
export const currencies = ['INR', 'USD', 'EUR', 'GBP', 'SGD', 'AED']
// A flat entry is a single sidebar tile; a group renders as one parent tile (clicking it opens
// its first page) with its own pages always shown nested beneath it, never collapsed — Categories/
// Holdings/Transactions are one workflow (file data under a category, record a position, log what
// happened to it) and read better as a "Data" tile with sub-tiles than as three separate top-level
// items competing with Overview/Signals for attention.
export type NavTile = { page: Page; icon: string; label: string }
export type NavGroup = { label: string; icon: string; pages: NavTile[] }
export const nav: (NavTile | NavGroup)[] = [
  { page: 'dashboard', icon: '◫', label: 'Overview' },
  { page: 'insights', icon: '◔', label: 'Signals' },
  { label: 'Data', icon: '▤', pages: [
      { page: 'categories', icon: '◈', label: 'Categories' },
      { page: 'holdings', icon: '▤', label: 'Holdings' },
      { page: 'transactions', icon: '⇅', label: 'Transactions' },
    ] },
  { page: 'brokers', icon: '⇄', label: 'Sources' },
  { page: 'settings', icon: '⚙', label: 'Settings' },
]
// The methods a holding form actually offers — BROKER_SYNC isn't selectable yet.
export const selectableValuationMethods: ValuationMethod[] = ['MANUAL', 'MARKET_PRICE', 'FIXED_RATE']
export const valuationMethodLabel = (m: ValuationMethod) => m === 'MANUAL' ? 'Manual value' : m === 'MARKET_PRICE' ? 'Market price' : 'Fixed-rate compounding'
// The form fields the typed-manual flows add; every value is a string, as in the rest of the holding form.
export const blankManualForm = () => ({
  manualType: 'DEFAULT' as ManualType,
  purchaseUnitPrice: '', currentUnitPrice: '',
  employeeContribution: '', employerContribution: '', interestAccumulated: '',
  expectedInterestRate: '', interestCreditMonth: '', interestCreditDay: '',
})
export type ManualForm = ReturnType<typeof blankManualForm>
export const blankCategoryForm = () => ({ name: '', kind: 'ASSET' as HoldingKind, description: '', allowedValuationMethods: [] as ValuationMethod[], realEstate: false })
export const blankHoldingForm = (categoryId: string) => ({ categoryId, name: '', valuationMethod: 'MANUAL' as ValuationMethod, tickerSymbol: '', currency: 'INR', fixedAnnualRate: '', compoundingFrequency: 'QUARTERLY' as Frequency, liquidWithinSevenDays: false, blocked: false, tags: [] as string[], broker: '', quantity: '', area: '', areaUnit: 'SQ_FT' as AreaUnit, ...blankManualForm(), investedValue: '', currentValue: '', fixedRateStartDate: new Date().toISOString().slice(0, 10), fixedRateEndDate: '', repaymentFrequency: 'MONTHLY' as RepaymentFrequency, emiAmount: '', emiDayOfMonth: '', loanTermMonths: '', repaymentDueDate: '', description: '' })
// Area units a real-estate holding can be measured in.
export const areaUnits: AreaUnit[] = ['ACRE', 'HECTARE', 'SQ_M', 'SQ_FT']
export const areaUnitLabel = (u: AreaUnit) => u === 'ACRE' ? 'acre' : u === 'HECTARE' ? 'hectare' : u === 'SQ_M' ? 'sq. m' : 'sq. ft'

export let baseCurrency = 'INR'
export let numberLocale = 'en-IN' // en-IN groups as lakh/crore; en-US groups as million/billion

export const THEME_KEY = 'finsights-theme'
export function initialTheme(): Theme {
  try {
    const stored = localStorage.getItem(THEME_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch { /* storage unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}
export const money = (value: number, currency = baseCurrency) => new Intl.NumberFormat(numberLocale, { style: 'currency', currency, maximumFractionDigits: 0 }).format(value ?? 0)
export const rate = (value: number, currency: string) => new Intl.NumberFormat(numberLocale, { style: 'currency', currency, maximumFractionDigits: 2 }).format(value ?? 0)
// Just the bare symbol ("₹", "$", …) for labelling a raw numeric input — money()/rate() always
// format a full number, which an <input type="number"> can't hold alongside its value.
export const currencySymbol = (currency = baseCurrency) => new Intl.NumberFormat(numberLocale, { style: 'currency', currency, maximumFractionDigits: 0 })
  .formatToParts(0).find(p => p.type === 'currency')?.value ?? currency
// Cross-rate via each currency's rate-to-base (as /api/fx-rates returns it) — mirrors
// FxRateService.convert() server-side. Null when either currency is missing a published rate.
export const convertAmount = (amount: number, from: string, to: string, ratesToBase: Record<string, number>): number | null => {
  const fromRate = ratesToBase[from], toRate = ratesToBase[to]
  if (!fromRate || !toRate) return null
  return from === to ? amount : amount * fromRate / toRate
}
export const percent = (value: number) => `${(value ?? 0).toFixed(1)}%`
export const label = (value: string) => value.replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase())
export const numeric = (value: string) => value === '' ? 0 : Number(value)
export const since = (value?: string) => value ? new Date(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—'
export const ago = (value?: string) => {
  if (!value) return '—'
  const secs = Math.round((Date.now() - new Date(value).getTime()) / 1000)
  if (secs < 60) return 'just now'
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)} h ago`
  return since(value)
}
export const shortId = (id: string) => `#${id.slice(-8)}`
// Collapsed / expanded affordance text — shared by every expandable section for consistency.
export const toggleLabel = (open: boolean) => (open ? 'Hide ▴' : 'Show ▾')

export function downloadCsv(filename: string, headers: string[], rows: (string | number)[][]) {
  const escape = (value: string | number) => { const s = String(value ?? ''); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s }
  const csv = [headers, ...rows].map(row => row.map(escape).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
  URL.revokeObjectURL(url)
}

// Mutable display-locale state, set once per data load by App.
export function applyLocale(cur: string, numberFormat: 'INDIAN' | 'INTERNATIONAL') {
  baseCurrency = cur
  numberLocale = numberFormat === 'INTERNATIONAL' ? 'en-US' : 'en-IN'
}
