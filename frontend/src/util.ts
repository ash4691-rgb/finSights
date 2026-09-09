import type { Frequency, RepaymentFrequency, PeriodKey, TransactionType, Page, HoldingKind, ValuationMethod, Theme } from './types'

export const frequencies: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUALLY', 'AT_MATURITY']
export const frequencyLabel = (f: Frequency) => f === 'ANNUALLY' ? 'Yearly' : f === 'AT_MATURITY' ? 'At maturity'
  : f.toLowerCase().replace(/_/g, '-').replace(/\b\w/g, c => c.toUpperCase())
export const repaymentFrequencies: RepaymentFrequency[] = ['WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY', 'ONE_TIME']
export const repaymentLabel = (f: RepaymentFrequency) => f === 'ONE_TIME' ? 'One-time' : f.charAt(0) + f.slice(1).toLowerCase()
export const periodLabels: Record<PeriodKey, string> = { DAILY: 'Daily', WEEKLY: 'Weekly', MONTHLY: 'Monthly', QUARTERLY: 'Quarterly', YEARLY: 'Yearly' }
export const periodFields: [PeriodKey, string][] = [
  ['DAILY', 'Daily'], ['WEEKLY', 'Weekly'], ['MONTHLY', 'Monthly'], ['QUARTERLY', 'Quarterly'], ['YEARLY', 'Yearly'],
]
export const transactionTypes: TransactionType[] = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT', 'REPAY']
export const assetTxnTypes: TransactionType[] = ['BUY', 'SELL', 'SPLIT', 'INTEREST', 'ADJUSTMENT']
export const liabilityTxnTypes: TransactionType[] = ['REPAY', 'ADJUSTMENT', 'BUY']
export const currencies = ['INR', 'USD', 'EUR', 'GBP', 'SGD', 'AED']
export const nav: [Page, string, string][] = [
  ['dashboard', '◫', 'Overview'], ['insights', '◔', 'Insights'], ['categories', '◈', 'Categories'],
  ['holdings', '▤', 'Holdings'], ['transactions', '⇅', 'Transactions'], ['brokers', '⇄', 'External sources'], ['settings', '⚙', 'Settings'],
]
export const blankCategoryForm = () => ({ name: '', kind: 'ASSET' as HoldingKind, description: '' })
export const blankHoldingForm = (categoryId: string) => ({ categoryId, name: '', valuationMethod: 'MANUAL' as ValuationMethod, tickerSymbol: '', currency: 'INR', fixedAnnualRate: '', compoundingFrequency: 'QUARTERLY' as Frequency, liquidWithinSevenDays: false, blocked: false, tags: [] as string[], broker: '', quantity: '', investedValue: '', currentValue: '', fixedRateStartDate: new Date().toISOString().slice(0, 10), fixedRateEndDate: '', repaymentFrequency: 'MONTHLY' as RepaymentFrequency, emiAmount: '', emiDayOfMonth: '', loanTermMonths: '', repaymentDueDate: '', description: '' })

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
