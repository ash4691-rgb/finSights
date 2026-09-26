// Shared domain types. Owned jointly — coordinate cross-team before changing shapes.
export type HoldingKind = 'ASSET' | 'LIABILITY'
export type ValuationMethod = 'MANUAL' | 'MARKET_PRICE' | 'BROKER_SYNC' | 'FIXED_RATE'
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'ANNUALLY' | 'AT_MATURITY'
export type RepaymentFrequency = 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY' | 'ONE_TIME'
export type TransactionType = 'BUY' | 'SELL' | 'SPLIT' | 'INTEREST' | 'ADJUSTMENT' | 'REPAY'
export type Page = 'dashboard' | 'categories' | 'holdings' | 'transactions' | 'insights' | 'brokers' | 'settings'

// A user-defined bucket (Name + Asset/Liability type). Invested/current/P&L, weightage, and
// liquid/NPA amounts are rollups computed from its mapped holdings — not stored on it.
export type Category = {
  id: string; name: string; kind: HoldingKind; description?: string
  // Valuation methods holdings filed under this category may use. Empty/absent means no
  // restriction — every method is allowed (the default for categories that predate this field).
  allowedValuationMethods?: ValuationMethod[]
  investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number
  weightagePercent: number; liquidAmount: number; liquidPercent: number; npaAmount: number; npaPercent: number
  holdingCount: number; createdAt?: string; updatedAt?: string
}
// The actual named position — a stock, fund, crypto, FD, loan — filed under one category and
// held at one broker.
export type Holding = {
  id: string; holdingId: string; categoryId: string; categoryName: string; name: string; kind: HoldingKind; valuationMethod: ValuationMethod
  tickerSymbol?: string; broker?: string; currency: string
  // The holding's own linked currency — set once at creation (from the ticker for market-linked,
  // from the form for manual/fixed-rate) and never converted, unlike `currency` above which
  // becomes the selected view currency on a display-currency-converted holdings list.
  defaultCurrency: string
  investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number; realisedProfitLoss: number; accruedIncome: number
  quantity?: number; fixedAnnualRate?: number; compoundingFrequency?: Frequency; fixedRateStartDate?: string; fixedRateEndDate?: string
  repaymentFrequency?: RepaymentFrequency; emiAmount?: number; emiDayOfMonth?: number; loanTermMonths?: number; repaymentDueDate?: string
  liquidWithinSevenDays: boolean; blocked: boolean; description?: string; notes?: string; tags: string[]
  createdAt?: string; updatedAt?: string; priceUpdatedAt?: string
  // True when the backend had to fall back a computed figure for this holding (incomplete
  // fixed-rate data, an unconvertible currency, …) instead of failing the whole list — the
  // holding still renders, but needs a look; dataIssueMessage explains what to fix.
  dataIssue?: boolean; dataIssueMessage?: string
}
export type SymbolSuggestion = { symbol: string; name: string; exchange: string; type: string }
export type MarketQuote = { symbol: string; name: string; price: number; currency: string; asOf: string }
export type ChartRange = '1D' | '1W' | '1M' | '3M' | '6M' | '1Y'
export type MarketHistoryPoint = { timestamp: string; price: number }
export type MarketHistory = { symbol: string; currency: string; points: MarketHistoryPoint[] }
export type Breakdown = { label: string; value: number; investedValue: number; profitLoss: number }
export type Dashboard = { netWorth: number; totalAssets: number; totalLiabilities: number; investedAssets: number; portfolioProfitLoss: number; byCategory: Breakdown[]; byBroker: Breakdown[]; byTag: Breakdown[] }
export type User = { email: string; displayName: string; demoMode: boolean }
export type Settings = {
  email: string; displayName: string; phone?: string; country: string; countryName: string; baseCurrency: string
  numberFormat: 'INDIAN' | 'INTERNATIONAL'; notifyEmail: boolean; notifySms: boolean; notifyPush: boolean
  notifyThresholdPercent: number
  dailyThresholdPercent?: number; weeklyThresholdPercent?: number; monthlyThresholdPercent?: number
  quarterlyThresholdPercent?: number; yearlyThresholdPercent?: number
  demoMode: boolean; holdingCount: number; memberSince: string; customLayoutOnboardingDismissed: boolean
  userOnboardingDismissed: boolean
}
export type Country = { code: string; name: string; currency: string }
export type Transaction = {
  id: string; holdingId: string; holdingName: string; categoryId: string; categoryName: string; broker?: string
  currency: string; type: TransactionType; date: string; amount: number; quantity?: number; principalPortion?: number; interestPaid?: boolean; notes?: string; createdAt?: string
}
export type Mover = { id: string; name: string; profitLoss: number; profitLossPercentage: number }
export type ActionItem = { kind: string; severity: 'WARN' | 'INFO'; title: string; detail: string; holdingId?: string; holdingName?: string; dueDate?: string; amount?: number; period?: string; key: string }
export type Insights = { byCategory: Breakdown[]; byBroker: Breakdown[]; byTag: Breakdown[]; byCurrency: Breakdown[]; byLiquidity: Breakdown[]; topGainers: Mover[]; topLosers: Mover[]; actions: ActionItem[] }
export type PeriodKey = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY'
export type PeriodMovement = { period: PeriodKey; percent: number; thresholdPercent: number }
export type TopMover = {
  subjectType: 'HOLDING' | 'WATCHLIST'; id: string; name: string; categoryName?: string; tickerSymbol?: string
  currentValue?: number; currency?: string; triggered: PeriodMovement[]
}
// Insights-owned — a single % per lookback period; a move past it either way, up or down, counts.
// A period is "off" when its field is absent.
export type MovementThresholds = {
  dailyPercent?: number; weeklyPercent?: number; monthlyPercent?: number
  quarterlyPercent?: number; halfYearlyPercent?: number; yearlyPercent?: number
}
export type WatchlistEntry = { id: string; name: string; tickerSymbol?: string; notes?: string; currentValue?: number; currency?: string; lastUpdated?: string; createdAt?: string }
export type TimelineCategoryPoint = { categoryId: string; categoryName: string; kind: 'ASSET' | 'LIABILITY'; invested: number; current: number }
export type TimelineWeek = { weekOf: string; invested: number; current: number; liabilities: number; netWorth: number; categories: TimelineCategoryPoint[] }
export type PortfolioTimeline = { weeks: TimelineWeek[]; lastCapturedAt?: string; capturedToday: boolean }
export type BrokerGroup = { name: string; holdingCount: number; currentValue: number; investedValue: number; profitLoss: number; lastUpdated?: string; categories: string[]; currencies: string[] }
export type Source = { key: string; name: string; status: string; description: string; capabilities: string[]; docsUrl?: string }
export type Brokers = { brokers: BrokerGroup[]; sources: Source[] }
export type ValuationDetail = { method: ValuationMethod; investedValue: number; currentValue: number; profitLoss: number; profitLossPercentage: number; steps: string[]; projectedMaturityDate?: string; projectedMaturityValue?: number }
export type ImportResult = { created: number; updated: number; skipped: number; errors: { row: number; message: string }[] }
export type FxRates = { base: string; asOf: string; ratesToBase: Record<string, number>; note: string }
export type Theme = 'light' | 'dark'
export type GokuConfig = { available: boolean }
export type GokuRole = 'user' | 'assistant'
export type GokuMessage = { id: string; role: GokuRole; content: string }
export type GokuChatReply = { reply: string; queriesRemainingToday: number }
