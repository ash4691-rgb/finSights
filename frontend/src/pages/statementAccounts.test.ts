import { describe, test, expect, vi, afterEach } from 'vitest'
import { accountKey, accountsMapped, accountsOf, guessBroker, hasUnaccountedRows, reconcileByBroker, splitByBroker } from './statementAccounts'
import type { ExtractedHolding, ExtractedTransaction, StatementExtractionResult } from '../types'

const holding = (name: string, account?: string | null): ExtractedHolding => ({ instrumentName: name, isin: `INE${name}`, quantity: 1, account })
const txn = (name: string, account?: string | null): ExtractedTransaction => ({ date: '2026-01-05', type: 'BUY', instrumentName: name, account })
const statement = (holdings: ExtractedHolding[], transactions: ExtractedTransaction[] = [], warnings: string[] = []): StatementExtractionResult =>
  ({ statementType: 'MIXED', holdings, toRemove: [], transactions, warnings })

describe('accountsOf / hasUnaccountedRows', () => {
  test('lists each institution once, case-insensitively, across holdings and transactions', () => {
    const s = statement([holding('A', 'Zerodha Broking'), holding('B', ' zerodha broking '), holding('C', 'Alpha AMC'), holding('D', '  ')], [txn('E', 'Beta AMC'), txn('F', null)])
    expect(accountsOf(s)).toEqual(['Zerodha Broking', 'Alpha AMC', 'Beta AMC'])
    expect(hasUnaccountedRows(s)).toBe(true)
  })

  test('a statement whose every line names an institution has no unaccounted rows', () => {
    expect(hasUnaccountedRows(statement([holding('A', 'Alpha AMC')], [txn('B', 'Alpha AMC')]))).toBe(false)
    expect(accountsOf(statement([holding('A')]))).toEqual([])
  })
})

describe('guessBroker', () => {
  const brokers = ['Zerodha', 'Groww', 'Groww Pay']
  test('prefers an exact match, ignoring case', () => expect(guessBroker('GROWW', brokers)).toBe('Groww'))
  test('accepts a single broker the institution name contains or is contained in', () => {
    expect(guessBroker('Zerodha Broking Ltd', brokers)).toBe('Zerodha')
    expect(guessBroker('Zero', brokers)).toBe('Zerodha')
  })
  test('gives up when several brokers or none could be meant', () => {
    expect(guessBroker('Groww Invest', ['Groww', 'Groww Invest Pro', 'Groww Invest Lite'])).toBe('')
    expect(guessBroker('Upstox', brokers)).toBe('')
  })
})

describe('accountsMapped', () => {
  test('needs a broker for every account and, when asked, for the lines naming none', () => {
    const mapped = { [accountKey('Alpha AMC')]: 'Groww' }
    expect(accountsMapped(['Alpha AMC'], mapped, '', false)).toBe(true)
    expect(accountsMapped(['Alpha AMC', 'Beta AMC'], mapped, '', false)).toBe(false)
    expect(accountsMapped(['Alpha AMC'], { [accountKey('Alpha AMC')]: '  ' }, '', false)).toBe(false)
    expect(accountsMapped(['Alpha AMC'], mapped, ' ', true)).toBe(false)
    expect(accountsMapped(['Alpha AMC'], mapped, 'Zerodha', true)).toBe(true)
  })
})

describe('splitByBroker', () => {
  const map = { [accountKey('Zerodha Broking')]: 'Zerodha', [accountKey('Alpha AMC')]: 'Groww', [accountKey('Beta AMC')]: 'groww' }

  test('groups lines by the broker their institution maps to, merging accounts at one broker', () => {
    const parts = splitByBroker(statement([holding('A', 'Zerodha Broking'), holding('B', 'Alpha AMC'), holding('C', 'Beta AMC')], [txn('D', 'Alpha AMC')]), map, '')
    expect(parts.map(p => p.broker)).toEqual(['Zerodha', 'Groww'])
    expect(parts[1].statement.holdings.map(h => h.instrumentName)).toEqual(['B', 'C'])
    expect(parts[1].statement.transactions.map(t => t.instrumentName)).toEqual(['D'])
  })

  test('lines naming no institution go to the fallback broker, and are dropped when there is none', () => {
    const s = statement([holding('A', 'Zerodha Broking'), holding('B')], [txn('C')])
    expect(splitByBroker(s, map, 'Kite').map(p => p.broker)).toEqual(['Zerodha', 'Kite'])
    expect(splitByBroker(s, map, '  ').map(p => p.broker)).toEqual(['Zerodha'])
  })
})

describe('reconcileByBroker', () => {
  afterEach(() => vi.unstubAllGlobals())
  const bodies = () => (fetch as ReturnType<typeof vi.fn>).mock.calls.map(([, init]) => JSON.parse(init.body as string))

  test('reconciles each broker separately and keeps the statement notes on the first broker only', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => ({ ok: true, status: 200, json: async () => ({ ...JSON.parse(init.body as string), toRemove: [] }) })))
    const s = statement([holding('A', 'Zerodha Broking'), holding('B', 'Alpha AMC')], [], ['skipped a bond'])

    const results = await reconcileByBroker(s, 'HOLDINGS', { [accountKey('Zerodha Broking')]: 'Zerodha', [accountKey('Alpha AMC')]: 'Groww' }, '')

    expect(results.map(r => r.broker)).toEqual(['Zerodha', 'Groww'])
    expect(bodies().map(b => [b.broker, b.holdings.length, b.warnings])).toEqual([['Zerodha', 1, ['skipped a bond']], ['Groww', 1, []]])
  })

  test('an empty statement still reconciles against the one broker given', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => statement([]) })))

    const results = await reconcileByBroker(statement([]), 'HOLDINGS', {}, ' Zerodha ')

    expect(results).toHaveLength(1)
    expect(results[0].broker).toBe('Zerodha')
    expect(bodies()[0].broker).toBe('Zerodha')
  })
})
