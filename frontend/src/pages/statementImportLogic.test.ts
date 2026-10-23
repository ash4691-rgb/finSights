import { describe, test, expect, vi, beforeEach } from 'vitest'
import {
  derivePending, draftFromExtractedHolding, draftFromExtractedTransaction, guessCategoryId, holdingConflict, holdingPayload,
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
      holdings: [{ row: fund(), index: 0 }, { row: fund({ instrumentName: 'B' }), index: 1 }],
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
    const outcome = await logAllRows({ holdings: [{ row: fund(), index: 3 }], transactions: [] }, 'Groww', () => 'c-mf', 'INR', vi.fn())
    expect(outcome).toEqual({ savedKeys: ['h3'], note: 'Logged 1 row.' })
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
