import { blankHoldingForm } from '../util'
import { api, API_URL } from '../api'
import type { Category, ExtractedHolding, ExtractedTransaction, Holding, HoldingToRemove, InstrumentKind, StatementExtractionResult } from '../types'

// ---------------------------------------------------------------------------
// Pure helpers for the statement-import modal — what each extracted row becomes (a pre-filled
// review form, or a direct save for "Log all") and why a row can't be saved without a human look
// (a "conflict"). Kept out of the component so they're testable on their own.
// ---------------------------------------------------------------------------

export const kindLabel: Record<InstrumentKind, string> = { STOCK: 'Stock', ETF: 'ETF', MUTUAL_FUND: 'Mutual fund', CRYPTO: 'Crypto', COMMODITY: 'Commodity' }
export const kindOf = (row: ExtractedHolding): InstrumentKind => row.kind ?? 'STOCK'

const kindNamePattern: Record<InstrumentKind, RegExp> = {
  MUTUAL_FUND: /mutual|fund/i,
  ETF: /etf/i,
  STOCK: /stock|equit|share/i,
  CRYPTO: /crypto|coin|bitcoin/i,
  COMMODITY: /commodit|gold|silver/i,
}

/** The user's asset category whose name best fits a kind, or '' when none does — never a blind
 *  guess, so an unmatched kind is left for the user to choose on the confirm step. */
export const guessCategoryId = (kind: InstrumentKind, categories: Category[]): string => {
  const assets = categories.filter(c => c.kind === 'ASSET' && !c.realEstate)
  return assets.find(c => kindNamePattern[kind].test(c.name))?.id ?? ''
}

export const kindsPresent = (holdings: ExtractedHolding[]): InstrumentKind[] =>
  [...new Set(holdings.map(kindOf))]

export const txnKindOf = (row: ExtractedTransaction): InstrumentKind => row.kind ?? 'STOCK'

/** Every kind of instrument the statement mentions, in holdings or transactions — each one needs a
 *  category on the confirm step before "Log all" can save a new holding of that kind. */
export const statementKinds = (statement: StatementExtractionResult): InstrumentKind[] =>
  [...new Set([...statement.holdings.map(kindOf), ...statement.transactions.map(txnKindOf)])]

const round2 = (value: number) => Math.round(value * 100) / 100

// A pre-filled holding is seeded from the statement's own totals where it states them, and from
// quantity × average cost where it only states a per-unit cost — a sane non-zero starting value
// beats leaving it at 0. Left undefined when the statement states neither: an empty field the user
// must fill beats a silently wrong one.
const investedOf = (row: ExtractedHolding): number | undefined => {
  if (row.investedValue != null) return row.investedValue
  if (row.averageCostPerUnit != null) return round2(row.quantity * row.averageCostPerUnit)
  return undefined
}

export const holdingConflict = (row: ExtractedHolding, categoryId: string, broker: string, holdings: Holding[]): string | null => {
  if (!categoryId) return `No category chosen for ${kindLabel[kindOf(row)].toLowerCase()}s`
  if (row.quantity <= 0) return 'No units found'
  if (investedOf(row) == null) return 'No invested amount in the statement'
  const taken = holdings.some(h => h.name.trim().toLowerCase() === row.instrumentName.trim().toLowerCase()
    && (h.broker ?? '').trim().toLowerCase() === broker.trim().toLowerCase())
  return taken ? `Already a holding named "${row.instrumentName}" at ${broker}` : null
}

export const draftFromExtractedHolding = (row: ExtractedHolding, broker: string, categoryId: string): Partial<ReturnType<typeof blankHoldingForm>> => {
  const invested = investedOf(row)
  const invest = invested != null ? String(invested) : ''
  const current = row.currentValue != null ? String(row.currentValue) : invest
  return {
    ...(categoryId ? { categoryId } : {}),
    name: row.instrumentName,
    broker,
    quantity: String(row.quantity),
    investedValue: invest,
    currentValue: current,
    description: row.isin ? `ISIN ${row.isin}` : '',
    ...(row.tickerSymbol ? { valuationMethod: 'MARKET_PRICE' as const, tickerSymbol: row.tickerSymbol } : {}),
  }
}

/** The body POST /api/holdings takes, for a row saved straight from "Log all". */
export const holdingPayload = (row: ExtractedHolding, broker: string, categoryId: string) => {
  const invested = investedOf(row) ?? 0
  return {
    categoryId, name: row.instrumentName, broker,
    valuationMethod: row.tickerSymbol ? 'MARKET_PRICE' : 'MANUAL', tickerSymbol: row.tickerSymbol ?? null,
    currency: 'INR', quantity: row.quantity, investedValue: invested, currentValue: row.currentValue ?? invested,
    liquidWithinSevenDays: false, blocked: false, description: row.isin ? `ISIN ${row.isin}` : null, notes: null, tags: [] as string[],
  }
}

export const draftFromExtractedTransaction = (row: ExtractedTransaction) => ({
  holdingId: row.matchedHoldingId ?? '',
  type: row.type,
  date: row.date,
  amount: row.amount != null ? String(row.amount) : '',
  quantity: row.quantity != null ? String(row.quantity) : '',
  notes: row.notes || '',
})

export const transactionConflict = (row: ExtractedTransaction, newGroupKeys: Set<string> = new Set()): string | null => {
  if (!row.matchedHoldingId) {
    if (newGroupKeys.has(transactionKey(row))) return 'Create its new holding first'
    if (row.type === 'SELL') return "SELL for a holding you don't have at this broker"
    return 'No matching holding at this broker'
  }
  if (row.amount == null && row.type !== 'SPLIT') return 'No amount in the statement'
  return null
}

export const transactionPayload = (row: ExtractedTransaction) => ({
  holdingId: row.matchedHoldingId, type: row.type, date: row.date,
  amount: row.type === 'SPLIT' ? 0 : row.amount, quantity: row.quantity ?? null, interestPaid: null, notes: row.notes || null,
})

/** A SELL for the whole position, for a holding the statement no longer lists. The sale proceeds
 *  aren't on any holdings statement, so the amount is left for the user — which is also why a SELL
 *  suggestion is never part of "Log all". */
export const sellDraft = (missing: HoldingToRemove, statementDate: string | undefined, broker: string) => ({
  holdingId: missing.holdingId,
  type: 'SELL' as const,
  date: statementDate ?? new Date().toISOString().slice(0, 10),
  amount: '',
  quantity: missing.quantity != null ? String(missing.quantity) : '',
  notes: `Not listed on the ${broker} statement`,
})

// ---------------------------------------------------------------------------
// A transaction statement names instruments the user may not hold yet at this broker. A BUY for one
// opens a new holding (its earliest BUY becomes the opening position, dated as the statement dates
// it); the rest of its lines then attach to that holding. A SELL for an instrument with no BUY
// anywhere is flagged — there is nothing to sell from.
// ---------------------------------------------------------------------------

export type NewHoldingGroup = {
  key: string; name: string; kind: InstrumentKind; isin?: string; symbol?: string; tickerSymbol?: string
  openingIndex: number; rowIndexes: number[]
}

export const transactionKey = (row: ExtractedTransaction): string =>
  row.isin ?? row.instrumentName.trim().replace(/\s+/g, ' ').toUpperCase()

/** Groups the still-unmatched rows by instrument; only an instrument with a BUY becomes a group. */
export const newHoldingGroups = (rows: { row: ExtractedTransaction; index: number }[]): NewHoldingGroup[] => {
  const byKey = new Map<string, { row: ExtractedTransaction; index: number }[]>()
  for (const entry of rows.filter(({ row }) => !row.matchedHoldingId)) {
    const key = transactionKey(entry.row)
    byKey.set(key, [...(byKey.get(key) ?? []), entry])
  }
  const groups: NewHoldingGroup[] = []
  for (const [key, entries] of byKey) {
    const ordered = [...entries].sort((a, b) => a.row.date.localeCompare(b.row.date) || a.index - b.index)
    const opening = ordered.find(({ row }) => row.type === 'BUY')
    if (!opening) continue
    const { row } = opening
    groups.push({ key, name: row.instrumentName, kind: txnKindOf(row), isin: row.isin, symbol: row.symbol, tickerSymbol: row.tickerSymbol,
      openingIndex: opening.index, rowIndexes: ordered.map(e => e.index) })
  }
  return groups
}

/** Points every row of an instrument whose holding was just created at that holding. */
export const withCreatedHoldings = (rows: ExtractedTransaction[], createdByKey: Record<string, string>): ExtractedTransaction[] =>
  rows.map(row => {
    const created = createdByKey[transactionKey(row)]
    return !row.matchedHoldingId && created ? { ...row, matchedHoldingId: created } : row
  })

const holdingTaken = (name: string, broker: string, holdings: Holding[]) =>
  holdings.some(h => h.name.trim().toLowerCase() === name.trim().toLowerCase()
    && (h.broker ?? '').trim().toLowerCase() === broker.trim().toLowerCase())

export const groupConflict = (group: NewHoldingGroup, rows: ExtractedTransaction[], categoryId: string, broker: string, holdings: Holding[]): string | null => {
  const opening = rows[group.openingIndex]
  if (!categoryId) return `No category chosen for ${kindLabel[group.kind].toLowerCase()}s`
  if (!opening.quantity || opening.quantity <= 0) return 'No units on its first buy'
  if (opening.amount == null) return 'No amount on its first buy'
  return holdingTaken(group.name, broker, holdings) ? `Already a holding named "${group.name}" at ${broker}` : null
}

export const draftFromGroup = (group: NewHoldingGroup, opening: ExtractedTransaction, broker: string, categoryId: string): Partial<ReturnType<typeof blankHoldingForm>> => {
  const invested = opening.amount != null ? String(opening.amount) : ''
  return {
    ...(categoryId ? { categoryId } : {}),
    name: group.name, broker, quantity: opening.quantity != null ? String(opening.quantity) : '',
    investedValue: invested, currentValue: invested, description: group.isin ? `ISIN ${group.isin}` : '',
    ...(group.tickerSymbol ? { valuationMethod: 'MARKET_PRICE' as const, tickerSymbol: group.tickerSymbol } : {}),
  }
}

/** The body POST /api/holdings takes for a holding opened by its first BUY. The statement's date
 *  rides in fixedRateStartDate, which the server uses to date the opening position. */
export const groupHoldingPayload = (group: NewHoldingGroup, opening: ExtractedTransaction, broker: string, categoryId: string) => ({
  categoryId, name: group.name, broker,
  valuationMethod: group.tickerSymbol ? 'MARKET_PRICE' : 'MANUAL', tickerSymbol: group.tickerSymbol ?? null,
  currency: 'INR', quantity: opening.quantity, investedValue: opening.amount ?? 0, currentValue: opening.amount ?? 0,
  fixedRateStartDate: opening.date,
  liquidWithinSevenDays: false, blocked: false, description: group.isin ? `ISIN ${group.isin}` : null, notes: null, tags: [] as string[],
})

type HoldingRow = { row: ExtractedHolding; index: number }
type TxnRow = { row: ExtractedTransaction; index: number }
type CategoryFor = (kind: InstrumentKind) => string
/** A new holding opened by its first BUY, plus the rest of that instrument's lines to log after it. */
export type LoggableGroup = { group: NewHoldingGroup; opening: TxnRow; followers: TxnRow[] }
export type Loggable = { holdings: HoldingRow[]; transactions: TxnRow[]; groups: LoggableGroup[] }

const followerConflict = (row: ExtractedTransaction): string | null =>
  row.amount == null && row.type !== 'SPLIT' ? 'No amount in the statement' : null

/** What still needs the user's attention in a reconciled statement, and which of it "Log all" can
 *  save without a human look (no conflict). Rows already saved, skipped or dealt with are in
 *  `resolved`; `createdByKey` maps an instrument whose new holding was just created to its id. */
export const derivePending = (result: StatementExtractionResult | null, resolved: Set<string>, categoryFor: CategoryFor,
    broker: string, holdings: Holding[], createdByKey: Record<string, string> = {}) => {
  const newHoldings = result ? result.holdings.map((row, index) => ({ row, index })).filter(({ row }) => !row.matchedHoldingId) : []
  const effective = result ? withCreatedHoldings(result.transactions, createdByKey) : []
  const pendingHoldingRows = newHoldings.filter(({ index }) => !resolved.has(`h${index}`))
  const pendingTxnRows = effective.map((row, index) => ({ row, index })).filter(({ index }) => !resolved.has(`t${index}`))
  const pendingMissing = result ? result.toRemove.filter(r => !resolved.has(`m${r.holdingId}`)) : []
  const groups = newHoldingGroups(pendingTxnRows)
  const groupKeys = new Set(groups.map(g => g.key))

  const holdingConflicts = Object.fromEntries(pendingHoldingRows.map(({ row, index }) => [index, holdingConflict(row, categoryFor(kindOf(row)), broker, holdings)]))
  const txnConflicts = Object.fromEntries(pendingTxnRows.map(({ row, index }) => [index, transactionConflict(row, groupKeys)]))
  const groupConflicts = Object.fromEntries(groups.map(g => [g.key, groupConflict(g, effective, categoryFor(g.kind), broker, holdings)]))

  const loggableGroups: LoggableGroup[] = groups.filter(g => !groupConflicts[g.key]).map(group => ({
    group, opening: { row: effective[group.openingIndex], index: group.openingIndex },
    followers: group.rowIndexes.filter(i => i !== group.openingIndex && !followerConflict(effective[i])).map(i => ({ row: effective[i], index: i })),
  }))
  const loggable: Loggable = {
    holdings: pendingHoldingRows.filter(({ index }) => !holdingConflicts[index]),
    transactions: pendingTxnRows.filter(({ index }) => !txnConflicts[index]),
    groups: loggableGroups,
  }
  const groupRows = loggableGroups.reduce((sum, g) => sum + 1 + g.followers.length, 0)
  const loggableCount = loggable.holdings.length + loggable.transactions.length + groupRows
  return {
    effective, pendingHoldingRows, pendingTxnRows, pendingMissing, groups, holdingConflicts, txnConflicts, groupConflicts, loggable, loggableCount,
    pendingCount: pendingHoldingRows.length + pendingTxnRows.length + pendingMissing.length,
    skippedCount: pendingHoldingRows.length + pendingTxnRows.length - loggableCount,
  }
}

/** Runs one async task per item strictly one after another — saves are kept in statement order and
 *  never fired in parallel at the server. */
export const sequentially = async <T,>(items: T[], task: (item: T) => Promise<void>) => {
  await items.reduce((chain, item) => chain.then(() => task(item)), Promise.resolve())
}

/** Saves every conflict-free row, one at a time; a row the server rejects is counted, not fatal,
 *  and stays in the review list. New holdings are created first, then their remaining lines are
 *  logged against them. Resolves with the keys saved, the holdings created per instrument, and a
 *  summary line. */
export const logAllRows = async (loggable: Loggable, broker: string, categoryFor: CategoryFor,
    displayCurrency: string, onHoldingSaved: (saved: Holding) => void) => {
  const savedKeys: string[] = []
  const created: Record<string, string> = {}
  let failed = 0
  const holdingsUrl = `/api/holdings?currency=${encodeURIComponent(displayCurrency)}`
  const post = <T,>(url: string, payload: unknown) => api<T>(url, { method: 'POST', body: JSON.stringify(payload) })
  const logTransaction = async (row: ExtractedTransaction, index: number) => {
    try { await post('/api/transactions', transactionPayload(row)); savedKeys.push(`t${index}`) } catch { failed++ }
  }

  await sequentially(loggable.holdings, async ({ row, index }) => {
    try {
      onHoldingSaved(await post<Holding>(holdingsUrl, holdingPayload(row, broker, categoryFor(kindOf(row)))))
      savedKeys.push(`h${index}`)
    } catch { failed++ }
  })
  await sequentially(loggable.groups, async ({ group, opening, followers }) => {
    try {
      const saved = await post<Holding>(holdingsUrl, groupHoldingPayload(group, opening.row, broker, categoryFor(group.kind)))
      onHoldingSaved(saved)
      created[group.key] = saved.id
      savedKeys.push(`t${opening.index}`)
    } catch { failed++; return }
    await sequentially(followers, ({ row, index }) => logTransaction({ ...row, matchedHoldingId: created[group.key] }, index))
  })
  await sequentially(loggable.transactions, ({ row, index }) => logTransaction(row, index))

  const notes = [`Logged ${savedKeys.length} row${savedKeys.length === 1 ? '' : 's'}.`]
  if (failed > 0) notes.push(`${failed} could not be saved — review ${failed === 1 ? 'it' : 'them'} below.`)
  return { savedKeys, created, note: notes.join(' ') }
}

/** First step of an import: uploads the file once and returns what the model read from it. */
export const readStatement = async (file: File, password: string): Promise<StatementExtractionResult> => {
  const response = await fetch(`${API_URL}/api/statements/import`, {
    method: 'POST', credentials: 'include',
    headers: { 'Content-Type': 'application/octet-stream', 'X-Demo-User': 'demo@finsights.local', ...(password.trim() ? { 'X-Document-Password': password.trim() } : {}) },
    body: await file.arrayBuffer(),
  })
  const parsed = await response.json()
  if (!response.ok) throw new Error(parsed.message ?? 'Could not read that statement')
  return parsed
}

/** Second step: matches the rows of a first read against the confirmed broker's holdings. */
export const reconcileStatement = (extracted: StatementExtractionResult, statementType: string, broker: string) =>
  api<StatementExtractionResult>('/api/statements/reconcile', {
    method: 'POST',
    body: JSON.stringify({ statementType, broker, statementDate: extracted.statementDate ?? null, holdings: extracted.holdings, transactions: extracted.transactions, warnings: extracted.warnings }),
  })
