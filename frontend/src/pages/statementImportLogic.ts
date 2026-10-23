import { blankHoldingForm } from '../util'
import { api, API_URL } from '../api'
import type { Category, ExtractedHolding, ExtractedTransaction, Holding, HoldingToRemove, InstrumentKind, StatementExtractionResult } from '../types'

// ---------------------------------------------------------------------------
// Pure helpers for the statement-import modal — what each extracted row becomes (a pre-filled
// review form, or a direct save for "Log all") and why a row can't be saved without a human look
// (a "conflict"). Kept out of the component so they're testable on their own.
// ---------------------------------------------------------------------------

export const kindLabel: Record<InstrumentKind, string> = { STOCK: 'Stock', ETF: 'ETF', MUTUAL_FUND: 'Mutual fund' }
export const kindOf = (row: ExtractedHolding): InstrumentKind => row.kind ?? 'STOCK'

const kindNamePattern: Record<InstrumentKind, RegExp> = {
  MUTUAL_FUND: /mutual|fund/i,
  ETF: /etf/i,
  STOCK: /stock|equit|share/i,
}

/** The user's asset category whose name best fits a kind, or '' when none does — never a blind
 *  guess, so an unmatched kind is left for the user to choose on the confirm step. */
export const guessCategoryId = (kind: InstrumentKind, categories: Category[]): string => {
  const assets = categories.filter(c => c.kind === 'ASSET' && !c.realEstate)
  return assets.find(c => kindNamePattern[kind].test(c.name))?.id ?? ''
}

export const kindsPresent = (holdings: ExtractedHolding[]): InstrumentKind[] =>
  [...new Set(holdings.map(kindOf))]

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

export const transactionConflict = (row: ExtractedTransaction): string | null => {
  if (!row.matchedHoldingId) return 'No matching holding at this broker'
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

type HoldingRow = { row: ExtractedHolding; index: number }
type TxnRow = { row: ExtractedTransaction; index: number }
export type Loggable = { holdings: HoldingRow[]; transactions: TxnRow[] }

/** What still needs the user's attention in a reconciled statement, and which of it "Log all" can
 *  save without a human look (no conflict). Rows already saved, skipped or dealt with are in `resolved`. */
export const derivePending = (result: StatementExtractionResult | null, resolved: Set<string>,
    categoryFor: (row: ExtractedHolding) => string, broker: string, holdings: Holding[]) => {
  const newHoldings = result ? result.holdings.map((row, index) => ({ row, index })).filter(({ row }) => !row.matchedHoldingId) : []
  const allTxns = result ? result.transactions.map((row, index) => ({ row, index })) : []
  const pendingHoldingRows = newHoldings.filter(({ index }) => !resolved.has(`h${index}`))
  const pendingTxnRows = allTxns.filter(({ index }) => !resolved.has(`t${index}`))
  const pendingMissing = result ? result.toRemove.filter(r => !resolved.has(`m${r.holdingId}`)) : []
  const holdingConflicts = Object.fromEntries(pendingHoldingRows.map(({ row, index }) => [index, holdingConflict(row, categoryFor(row), broker, holdings)]))
  const txnConflicts = Object.fromEntries(pendingTxnRows.map(({ row, index }) => [index, transactionConflict(row)]))
  const loggable: Loggable = {
    holdings: pendingHoldingRows.filter(({ index }) => !holdingConflicts[index]),
    transactions: pendingTxnRows.filter(({ index }) => !txnConflicts[index]),
  }
  const loggableCount = loggable.holdings.length + loggable.transactions.length
  return {
    pendingHoldingRows, pendingTxnRows, pendingMissing, holdingConflicts, txnConflicts, loggable, loggableCount,
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
 *  and stays in the review list. Resolves with the keys that were saved and a summary line. */
export const logAllRows = async (loggable: Loggable, broker: string, categoryFor: (row: ExtractedHolding) => string,
    displayCurrency: string, onHoldingSaved: (saved: Holding) => void) => {
  const savedKeys: string[] = []
  let failed = 0
  await sequentially(loggable.holdings, async ({ row, index }) => {
    try {
      const url = `/api/holdings?currency=${encodeURIComponent(displayCurrency)}`
      onHoldingSaved(await api<Holding>(url, { method: 'POST', body: JSON.stringify(holdingPayload(row, broker, categoryFor(row))) }))
      savedKeys.push(`h${index}`)
    } catch { failed++ }
  })
  await sequentially(loggable.transactions, async ({ row, index }) => {
    try {
      await api('/api/transactions', { method: 'POST', body: JSON.stringify(transactionPayload(row)) })
      savedKeys.push(`t${index}`)
    } catch { failed++ }
  })
  const notes = [`Logged ${savedKeys.length} row${savedKeys.length === 1 ? '' : 's'}.`]
  if (failed > 0) notes.push(`${failed} could not be saved — review ${failed === 1 ? 'it' : 'them'} below.`)
  return { savedKeys, note: notes.join(' ') }
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
