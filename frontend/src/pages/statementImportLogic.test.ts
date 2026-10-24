import { describe, test, expect, vi, beforeEach } from 'vitest'
import {
  derivePending, draftFromGroup, groupConflict, groupHoldingPayload, newHoldingGroups, statementKinds, transactionKey, txnKindOf, withCreatedHoldings, draftFromExtractedHolding, draftFromExtractedTransaction, guessCategoryId, holdingConflict, holdingPayload,
  kindOf, kindsPresent, logAllRows, readStatement, reconcileStatement, sellDraft, transactionConflict, transactionPayload,
} from './statementImportLogic'
import type { Category, ExtractedHolding, ExtractedTransaction, Holding, StatementExtractionResult } from '../types'

vi.mock('../api', () => ({ api: vi.fn(), API_URL: 'http://api.test' }))
import { api } from '../api'

const cat = (id: string, name: string, extra: Partial<Category> = {}): Category => ({
  id, name, kind: 'ASSET', investedValue: 0, currentValue: 0, profitLoss: 0, profitLossPercentage: 0, weightagePercent: 0,
  liquidAmount: 0, liquidPercent: 0, npaAmount: 0, npaPercent: 0, holdingCount: 0, ...extra,
})
const categories = [cat('c-eq', 'Indian Stocks'), cat('c-mf', 'Mutual Funds'), cat('c-etf', 'ETF basket'), cat('c-re', 'Real estate fund', { realEstate: true }), cat('c-debt', 'Home loan', { kind: 'LIABILITY' })]

const fund = (over: Partial<ExtractedHolding> = {}): ExtractedHolding =>
  ({ instrumentName: 'Alpha Fund', quantity: 10, kind: 'MUTUAL_FUND', investedValue: 1000, currentValue: 1200, ...over })
const heldBy = (name: string, broker: string): Holding => ({ id: `h-${name}`, name, broker } as Holding)

describe('kinds and categories', () => {
  test('a row without a kind is a stock', () => {
    expect(kindOf({ instrumentName: 'X', quantity: 1 })).toBe('STOCK')
    expect(kindOf(fund())).toBe('MUTUAL_FUND')
  })

  test('kindsPresent lists each kind once', () => {
    expect(kindsPresent([fund(), fund(), { instrumentName: 'X', quantity: 1 }])).toEqual(['MUTUAL_FUND', 'STOCK'])
  })

  test('guessCategoryId picks a matching asset category and ignores liabilities and real estate', () => {
    expect(guessCategoryId('MUTUAL_FUND', categories)).toBe('c-mf')
    expect(guessCategoryId('STOCK', categories)).toBe('c-eq')
    expect(guessCategoryId('ETF', categories)).toBe('c-etf')
    expect(guessCategoryId('MUTUAL_FUND', [categories[3], categories[4]])).toBe('')
    expect(guessCategoryId('ETF', [cat('x', 'Gold')])).toBe('')
  })
})

describe('holding rows', () => {
  test('conflicts: no category, no units, no invested amount, or a same-named holding at the same broker', () => {
    expect(holdingConflict(fund(), '', 'Groww', [])).toMatch(/No category chosen for mutual funds/)
    expect(holdingConflict(fund({ quantity: 0 }), 'c-mf', 'Groww', [])).toBe('No units found')
    expect(holdingConflict(fund({ investedValue: undefined }), 'c-mf', 'Groww', [])).toBe('No invested amount in the statement')
    expect(holdingConflict(fund(), 'c-mf', 'Groww', [heldBy(' alpha fund ', 'groww')])).toMatch(/Already a holding named "Alpha Fund" at Groww/)
    expect(holdingConflict(fund(), 'c-mf', 'Groww', [heldBy('Alpha Fund', 'Zerodha'), { id: 'x', name: 'Other' } as Holding])).toBeNull()
  })

  test('an average cost stands in for a missing invested total', () => {
    expect(holdingConflict(fund({ investedValue: undefined, averageCostPerUnit: 100 }), 'c-mf', 'Groww', [])).toBeNull()
    const draft = draftFromExtractedHolding(fund({ investedValue: undefined, currentValue: undefined, averageCostPerUnit: 33.333 }), 'Groww', 'c-mf')
    expect(draft.investedValue).toBe('333.33')
    expect(draft.currentValue).toBe('333.33')
  })

  test('the draft carries broker, category, amounts, ISIN and a ticker guess', () => {
    const draft = draftFromExtractedHolding({ ...fund(), isin: 'INF000A01010', tickerSymbol: 'MF:100' }, 'Groww', 'c-mf')
    expect(draft).toMatchObject({ categoryId: 'c-mf', name: 'Alpha Fund', broker: 'Groww', quantity: '10', investedValue: '1000', currentValue: '1200', description: 'ISIN INF000A01010', valuationMethod: 'MARKET_PRICE', tickerSymbol: 'MF:100' })
  })

  test('a foreign-currency statement row carries its currency into the draft and the payload', () => {
    const usd = fund({ currency: 'USD', tickerSymbol: 'ASML' })
    expect(draftFromExtractedHolding(usd, 'DriveWealth', 'c-us')).toMatchObject({ currency: 'USD', tickerSymbol: 'ASML' })
    expect(holdingPayload(usd, 'DriveWealth', 'c-us')).toMatchObject({ currency: 'USD' })
    expect(draftFromExtractedHolding(fund(), 'Groww', 'c-mf')).not.toHaveProperty('currency')
    expect(holdingPayload(fund(), 'Groww', 'c-mf')).toMatchObject({ currency: 'INR' })
  })

  test('the draft leaves category and valuation alone when there is nothing to put in them', () => {
    const draft = draftFromExtractedHolding(fund({ investedValue: undefined, currentValue: undefined }), 'Groww', '')
    expect(draft).not.toHaveProperty('categoryId')
    expect(draft).not.toHaveProperty('valuationMethod')
    expect(draft).toMatchObject({ investedValue: '', currentValue: '', description: '' })
  })

  test('the payload values a holding by market price only when a ticker was found', () => {
    expect(holdingPayload({ ...fund(), tickerSymbol: 'MF:100', isin: 'INF1' }, 'Groww', 'c-mf')).toMatchObject({ valuationMethod: 'MARKET_PRICE', tickerSymbol: 'MF:100', description: 'ISIN INF1', currentValue: 1200 })
    expect(holdingPayload(fund({ currentValue: undefined }), 'Groww', 'c-mf')).toMatchObject({ valuationMethod: 'MANUAL', tickerSymbol: null, description: null, currentValue: 1000, investedValue: 1000 })
    expect(holdingPayload(fund({ investedValue: undefined, currentValue: undefined }), 'Groww', 'c-mf')).toMatchObject({ investedValue: 0, currentValue: 0 })
  })
})

describe('transaction rows', () => {
  const txn = (over: Partial<ExtractedTransaction> = {}): ExtractedTransaction =>
    ({ date: '2026-01-02', type: 'BUY', instrumentName: 'X', isin: 'I', amount: 10, quantity: 2, matchedHoldingId: 'h-1', ...over })

  test('conflicts: no matching holding, or no amount (a split needs none)', () => {
    expect(transactionConflict(txn())).toBeNull()
    expect(transactionConflict(txn({ matchedHoldingId: undefined }))).toMatch(/No matching holding/)
    expect(transactionConflict(txn({ amount: undefined }))).toBe('No amount in the statement')
    expect(transactionConflict(txn({ amount: undefined, type: 'SPLIT' }))).toBeNull()
  })

  test('draft and payload', () => {
    expect(draftFromExtractedTransaction(txn({ notes: 'n' }))).toMatchObject({ holdingId: 'h-1', amount: '10', quantity: '2', notes: 'n' })
    expect(draftFromExtractedTransaction(txn({ matchedHoldingId: undefined, amount: undefined, quantity: undefined }))).toMatchObject({ holdingId: '', amount: '', quantity: '', notes: '' })
    expect(transactionPayload(txn())).toMatchObject({ holdingId: 'h-1', amount: 10, quantity: 2, notes: null })
    expect(transactionPayload(txn({ type: 'SPLIT', amount: undefined, quantity: undefined, notes: 'x' }))).toMatchObject({ amount: 0, quantity: null, notes: 'x' })
  })
})

describe('sellDraft', () => {
  test('sells the whole position on the statement date', () => {
    expect(sellDraft({ holdingId: 'h-1', name: 'A', quantity: 12 }, '2026-10-23', 'Groww')).toEqual({
      holdingId: 'h-1', type: 'SELL', date: '2026-10-23', amount: '', quantity: '12', notes: 'Not listed on the Groww statement',
    })
  })

  test('falls back to today and a blank quantity', () => {
    const draft = sellDraft({ holdingId: 'h-1', name: 'A' }, undefined, 'Groww')
    expect(draft.date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(draft.quantity).toBe('')
  })
})

describe('derivePending', () => {
  const result: StatementExtractionResult = {
    statementType: 'MIXED', warnings: [],
    holdings: [fund(), fund({ instrumentName: 'Matched', matchedHoldingId: 'h-1' }), fund({ instrumentName: 'No cost', investedValue: undefined })],
    transactions: [{ date: 'd', type: 'BUY', instrumentName: 'X', amount: 1, matchedHoldingId: 'h-1' } as ExtractedTransaction],
    toRemove: [{ holdingId: 'h-gone', name: 'Gone' }, { holdingId: 'h-kept', name: 'Kept' }],
  }
  const categoryFor = () => 'c-mf'

  test('splits pending rows into loggable and conflicted, and skips resolved ones', () => {
    const pending = derivePending(result, new Set(['mh-kept']), categoryFor, 'Groww', [])

    expect(pending.pendingHoldingRows.map(r => r.row.instrumentName)).toEqual(['Alpha Fund', 'No cost'])
    expect(pending.pendingMissing.map(m => m.holdingId)).toEqual(['h-gone'])
    expect(pending.loggableCount).toBe(2)
    expect(pending.skippedCount).toBe(1)
    expect(pending.pendingCount).toBe(4)
    expect(pending.holdingConflicts[2]).toBe('No invested amount in the statement')
  })

  test('is empty before there is a result', () => {
    const pending = derivePending(null, new Set(), categoryFor, 'Groww', [])
    expect(pending.pendingCount).toBe(0)
    expect(pending.loggableCount).toBe(0)
  })
})

describe('network helpers', () => {
  beforeEach(() => { vi.mocked(api).mockReset(); vi.unstubAllGlobals() })

  test('logAllRows saves each row, counts failures, and reports what was saved', async () => {
    vi.mocked(api).mockResolvedValueOnce({ id: 'h-new' }).mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('boom'))
    const onSaved = vi.fn()
    const loggable = {
      holdings: [{ row: fund(), index: 0 }, { row: fund({ instrumentName: 'B' }), index: 1 }], groups: [],
      transactions: [{ row: { date: 'd', type: 'BUY', instrumentName: 'X', amount: 1, matchedHoldingId: 'h-1' } as ExtractedTransaction, index: 0 },
        { row: { date: 'd', type: 'BUY', instrumentName: 'Y', amount: 1, matchedHoldingId: 'h-1' } as ExtractedTransaction, index: 1 }],
    }

    const outcome = await logAllRows(loggable, 'Groww', () => 'c-mf', 'INR', onSaved)

    expect(outcome.savedKeys).toEqual(['h0', 't0'])
    expect(outcome.note).toBe('Logged 2 rows. 2 could not be saved — review them below.')
    expect(onSaved).toHaveBeenCalledWith({ id: 'h-new' })
  })

  test('logAllRows words a single save without a failure note', async () => {
    vi.mocked(api).mockResolvedValueOnce({})
    const outcome = await logAllRows({ holdings: [{ row: fund(), index: 3 }], transactions: [], groups: [] }, 'Groww', () => 'c-mf', 'INR', vi.fn())
    expect(outcome).toEqual({ savedKeys: ['h3'], created: {}, note: 'Logged 1 row.' })
  })

  test('readStatement uploads the file with the password and returns the reading', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ statementType: 'HOLDINGS' }) })
    vi.stubGlobal('fetch', fetchMock)

    const parsed = await readStatement(new File(['x'], 's.pdf'), ' pw ')

    expect(parsed).toEqual({ statementType: 'HOLDINGS' })
    expect(fetchMock.mock.calls[0][0]).toBe('http://api.test/api/statements/import')
    expect(fetchMock.mock.calls[0][1].headers['X-Document-Password']).toBe('pw')
  })

  test('readStatement throws the server message, or a generic one', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: false, json: () => Promise.resolve({ message: 'Bad file' }) }).mockResolvedValueOnce({ ok: false, json: () => Promise.resolve({}) }))
    await expect(readStatement(new File(['x'], 's.csv'), '')).rejects.toThrow('Bad file')
    await expect(readStatement(new File(['x'], 's.csv'), '')).rejects.toThrow('Could not read that statement')
  })

  test('reconcileStatement posts the confirmed type, broker and rows', async () => {
    vi.mocked(api).mockResolvedValueOnce({ ok: true })
    await reconcileStatement({ statementType: 'HOLDINGS', holdings: [fund()], transactions: [], warnings: ['w'], toRemove: [] }, 'MIXED', 'Groww')

    const [path, init] = vi.mocked(api).mock.calls[0]
    expect(path).toBe('/api/statements/reconcile')
    expect(JSON.parse(init!.body as string)).toMatchObject({ statementType: 'MIXED', broker: 'Groww', statementDate: null, warnings: ['w'] })
  })
})

describe('crypto and commodity kinds', () => {
  test('categories are guessed for crypto and commodities too', () => {
    const cats = [cat('c-cr', 'Crypto wallet'), cat('c-gd', 'Gold & silver')]
    expect(guessCategoryId('CRYPTO', cats)).toBe('c-cr')
    expect(guessCategoryId('COMMODITY', cats)).toBe('c-gd')
  })

  test('statementKinds covers holdings and transactions alike, defaulting a kindless line to stock', () => {
    const statement: StatementExtractionResult = {
      statementType: 'MIXED', warnings: [], toRemove: [], holdings: [fund()],
      transactions: [{ date: 'd', type: 'BUY', instrumentName: 'Bitcoin', kind: 'CRYPTO' }, { date: 'd', type: 'BUY', instrumentName: 'X' }],
    }
    expect(statementKinds(statement)).toEqual(['MUTUAL_FUND', 'CRYPTO', 'STOCK'])
    expect(txnKindOf(statement.transactions[1])).toBe('STOCK')
  })
})

describe('new holdings from transactions', () => {
  const buy = (name: string, date: string, over: Partial<ExtractedTransaction> = {}): ExtractedTransaction =>
    ({ date, type: 'BUY', instrumentName: name, isin: undefined, amount: 100, quantity: 2, kind: 'MUTUAL_FUND', ...over })
  const entries = (rows: ExtractedTransaction[]) => rows.map((row, index) => ({ row, index }))

  test('keys by ISIN, else by normalised name', () => {
    expect(transactionKey(buy(' Alpha   Fund ', 'd'))).toBe('ALPHA FUND')
    expect(transactionKey(buy('Alpha', 'd', { isin: 'INF1' }))).toBe('INF1')
  })

  test('an instrument with a BUY becomes a group opened by its earliest BUY; matched rows and sell-only instruments do not', () => {
    const rows = [
      buy('Alpha Fund', '2026-03-01'),
      { ...buy('Alpha Fund', '2026-02-01'), type: 'SELL' as const },
      buy('Alpha Fund', '2026-01-01', { tickerSymbol: 'MF:1' }),
      { ...buy('Orphan Fund', '2026-01-05'), type: 'SELL' as const },
      buy('Held Fund', '2026-01-06', { matchedHoldingId: 'h-1' }),
    ]

    const groups = newHoldingGroups(entries(rows))

    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({ key: 'ALPHA FUND', name: 'Alpha Fund', kind: 'MUTUAL_FUND', openingIndex: 2, tickerSymbol: 'MF:1' })
    expect(groups[0].rowIndexes).toEqual([2, 1, 0])
  })

  test('same-date lines keep statement order', () => {
    const groups = newHoldingGroups(entries([buy('A', '2026-01-01'), buy('A', '2026-01-01')]))
    expect(groups[0].openingIndex).toBe(0)
  })

  test('withCreatedHoldings attaches every unmatched line of a created instrument and nothing else', () => {
    const rows = [buy('Alpha Fund', 'd'), buy('Beta Fund', 'd'), buy('Alpha Fund', 'd', { matchedHoldingId: 'h-old' })]
    const result = withCreatedHoldings(rows, { 'ALPHA FUND': 'h-new' })
    expect(result.map(r => r.matchedHoldingId)).toEqual(['h-new', undefined, 'h-old'])
  })

  const group = newHoldingGroups(entries([buy('Alpha Fund', '2026-01-01', { tickerSymbol: 'MF:1', isin: 'INF1' })]))[0]

  test('group conflicts: no category, no units, no amount, or a same-named holding at the broker', () => {
    const rows = (over: Partial<ExtractedTransaction>) => [buy('Alpha Fund', '2026-01-01', over)]
    expect(groupConflict(group, rows({}), '', 'Groww', [])).toMatch(/No category chosen for mutual funds/)
    expect(groupConflict(group, rows({ quantity: undefined }), 'c-mf', 'Groww', [])).toBe('No units on its first buy')
    expect(groupConflict(group, rows({ quantity: 0 }), 'c-mf', 'Groww', [])).toBe('No units on its first buy')
    expect(groupConflict(group, rows({ amount: undefined }), 'c-mf', 'Groww', [])).toBe('No amount on its first buy')
    expect(groupConflict(group, rows({}), 'c-mf', 'Groww', [heldBy('alpha fund', 'GROWW')])).toMatch(/Already a holding named "Alpha Fund" at Groww/)
    expect(groupConflict(group, rows({}), 'c-mf', 'Groww', [])).toBeNull()
  })

  test('the form draft and the payload carry the first buy, ticker, ISIN and opening date', () => {
    const opening = buy('Alpha Fund', '2026-01-01', { isin: 'INF1' })
    expect(draftFromGroup(group, opening, 'Groww', 'c-mf')).toMatchObject({ categoryId: 'c-mf', name: 'Alpha Fund', broker: 'Groww', quantity: '2', investedValue: '100', currentValue: '100', description: 'ISIN INF1', valuationMethod: 'MARKET_PRICE', tickerSymbol: 'MF:1' })
    expect(groupHoldingPayload(group, opening, 'Groww', 'c-mf')).toMatchObject({ categoryId: 'c-mf', valuationMethod: 'MARKET_PRICE', tickerSymbol: 'MF:1', quantity: 2, investedValue: 100, fixedRateStartDate: '2026-01-01', description: 'ISIN INF1' })
  })

  test('without a ticker, ISIN, quantity or amount the draft and payload fall back to blanks and manual valuation', () => {
    const bare = newHoldingGroups(entries([buy('Gold', '2026-01-01', { kind: 'COMMODITY', amount: undefined, quantity: undefined })]))[0]
    const opening = buy('Gold', '2026-01-01', { amount: undefined, quantity: undefined })
    const draft = draftFromGroup(bare, opening, 'Groww', '')
    expect(draft).not.toHaveProperty('categoryId')
    expect(draft).not.toHaveProperty('valuationMethod')
    expect(draft).toMatchObject({ quantity: '', investedValue: '', description: '' })
    expect(groupHoldingPayload(bare, opening, 'Groww', 'c-gd')).toMatchObject({ valuationMethod: 'MANUAL', tickerSymbol: null, investedValue: 0, currentValue: 0, description: null })
  })

  test('derivePending groups unmatched BUYs, flags SELL-only lines, and counts group rows as loggable', () => {
    const result: StatementExtractionResult = {
      statementType: 'TRANSACTIONS', warnings: [], holdings: [], toRemove: [],
      transactions: [
        buy('Alpha Fund', '2026-01-01'),
        buy('Alpha Fund', '2026-02-01'),
        buy('Alpha Fund', '2026-03-01', { amount: undefined }),
        { ...buy('Orphan Fund', '2026-01-05'), type: 'SELL' },
      ],
    }

    const pending = derivePending(result, new Set(), () => 'c-mf', 'Groww', [])

    expect(pending.groups.map(g => g.key)).toEqual(['ALPHA FUND'])
    expect(pending.txnConflicts[0]).toBe('Create its new holding first')
    expect(pending.txnConflicts[3]).toBe("SELL for a holding you don't have at this broker")
    expect(pending.loggable.groups).toHaveLength(1)
    expect(pending.loggable.groups[0].opening.index).toBe(0)
    expect(pending.loggable.groups[0].followers.map(f => f.index)).toEqual([1])
    expect(pending.loggableCount).toBe(2)
    expect(pending.skippedCount).toBe(2)
    expect(pending.effective).toHaveLength(4)
  })

  test('once the holding exists its remaining lines are ordinary matched transactions', () => {
    const result: StatementExtractionResult = { statementType: 'TRANSACTIONS', warnings: [], holdings: [], toRemove: [], transactions: [buy('Alpha Fund', '2026-01-01'), buy('Alpha Fund', '2026-02-01')] }
    const pending = derivePending(result, new Set(['t0']), () => 'c-mf', 'Groww', [], { 'ALPHA FUND': 'h-new' })

    expect(pending.groups).toHaveLength(0)
    expect(pending.txnConflicts[1]).toBeNull()
    expect(pending.loggable.transactions.map(t => t.index)).toEqual([1])
  })

  test('logAllRows creates each new holding first, then logs its other lines against it, and reports the ids', async () => {
    vi.mocked(api).mockReset()
    vi.mocked(api).mockResolvedValueOnce({ id: 'h-new' }).mockResolvedValueOnce({})
    const opening = { row: buy('Alpha Fund', '2026-01-01'), index: 0 }
    const follower = { row: buy('Alpha Fund', '2026-02-01'), index: 1 }
    const onSaved = vi.fn()

    const outcome = await logAllRows({ holdings: [], transactions: [], groups: [{ group, opening, followers: [follower] }] }, 'Groww', () => 'c-mf', 'INR', onSaved)

    expect(outcome.created).toEqual({ INF1: 'h-new' })
    expect(outcome.savedKeys).toEqual(['t0', 't1'])
    expect(onSaved).toHaveBeenCalledWith({ id: 'h-new' })
    const followerCall = vi.mocked(api).mock.calls[1]
    expect(followerCall[0]).toBe('/api/transactions')
    expect(JSON.parse(followerCall[1]!.body as string)).toMatchObject({ holdingId: 'h-new', amount: 100 })
  })

  test('a group whose holding cannot be created is counted as failed and its lines are not logged', async () => {
    vi.mocked(api).mockReset()
    vi.mocked(api).mockRejectedValueOnce(new Error('rejected'))
    const outcome = await logAllRows({ holdings: [], transactions: [], groups: [{ group, opening: { row: buy('Alpha Fund', 'd'), index: 0 }, followers: [{ row: buy('Alpha Fund', 'd'), index: 1 }] }] },
      'Groww', () => 'c-mf', 'INR', vi.fn())

    expect(outcome.created).toEqual({})
    expect(outcome.savedKeys).toEqual([])
    expect(outcome.note).toBe('Logged 0 rows. 1 could not be saved — review it below.')
    expect(vi.mocked(api)).toHaveBeenCalledTimes(1)
  })
})
