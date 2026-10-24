import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { StatementImportModal } from './StatementImportModal'
import type { Category, Holding, StatementExtractionResult } from '../types'

const category: Category = {
  id: 'cat-1', name: 'Equities', kind: 'ASSET', investedValue: 0, currentValue: 0, profitLoss: 0,
  profitLossPercentage: 0, weightagePercent: 0, liquidAmount: 0, liquidPercent: 0, npaAmount: 0,
  npaPercent: 0, holdingCount: 0,
}
const held: Holding = {
  id: 'h-1', holdingId: 'h-1', categoryId: 'cat-1', categoryName: 'Equities', name: 'Infosys', kind: 'ASSET',
  valuationMethod: 'MANUAL', currency: 'INR', defaultCurrency: 'INR', investedValue: 1, currentValue: 1, profitLoss: 0,
  profitLossPercentage: 0, realisedProfitLoss: 0, accruedIncome: 0, liquidWithinSevenDays: false, blocked: false, tags: [], broker: 'Zerodha',
}

// A CAS: shares at two demat accounts and a fund under an AMC, none naming one broker for the file.
const cas: StatementExtractionResult = {
  statementType: 'HOLDINGS', toRemove: [], transactions: [], warnings: [],
  holdings: [
    { instrumentName: 'Infosys', isin: 'INE009A01021', quantity: 10, kind: 'STOCK', account: 'Zerodha Broking' },
    { instrumentName: 'Tata Motors', isin: 'INE155A01022', quantity: 5, kind: 'STOCK', account: 'Upstox Securities' },
    { instrumentName: 'Alpha Fund', quantity: 3, kind: 'MUTUAL_FUND', account: 'Alpha AMC' },
  ],
}

const respond = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

describe('StatementImportModal — a statement spanning several brokers (CAS)', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  const reconcileBodies = () => fetchMock.mock.calls.filter(([url]) => String(url).includes('/reconcile')).map(([, init]) => JSON.parse(init.body as string))

  beforeEach(() => {
    fetchMock = vi.fn((url: string, init: RequestInit) => String(url).includes('/reconcile') ? respond({ ...JSON.parse(init.body as string), toRemove: [] }) : respond(cas))
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  const open = async (onClose = vi.fn()) => {
    render(<StatementImportModal categories={[category]} holdings={[held]} displayCurrency="INR" onClose={onClose}
      onSaved={vi.fn()} onRemoved={vi.fn()} onTransactionSaved={vi.fn()} />)
    const input = document.querySelector('input[type=file]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['x'], 'cas.pdf', { type: 'application/pdf' })] } })
    await waitFor(() => expect(screen.getByText('Check what we found')).toBeInTheDocument())
    return onClose
  }

  test('asks which broker each institution is, pre-filling the ones it can tell, and holds Continue until all are set', async () => {
    await open()

    expect(screen.getByText(/covers 3 accounts/)).toBeInTheDocument()
    const fields = screen.getAllByPlaceholderText(/Pick one of yours/) as HTMLInputElement[]
    expect(fields.map(f => f.value)).toEqual(['Zerodha', '', ''])
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()

    fireEvent.change(fields[1], { target: { value: 'Upstox' } })
    fireEvent.change(fields[2], { target: { value: 'Groww' } })
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })

  test('reconciles each broker against its own holdings only and reviews them tab by tab', async () => {
    const onClose = await open()
    const fields = screen.getAllByPlaceholderText(/Pick one of yours/)
    fireEvent.change(fields[1], { target: { value: 'Upstox' } })
    fireEvent.change(fields[2], { target: { value: 'Groww' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Groww' })).toBeInTheDocument())
    expect(reconcileBodies().map(b => [b.broker, b.holdings.map((h: { instrumentName: string }) => h.instrumentName)])).toEqual([
      ['Zerodha', ['Infosys']], ['Upstox', ['Tata Motors']], ['Groww', ['Alpha Fund']],
    ])
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['Zerodha', 'Upstox', 'Groww'])

    fireEvent.click(screen.getByRole('tab', { name: 'Groww' }))
    expect(screen.getByRole('tab', { name: 'Groww' })).toHaveAttribute('aria-selected', 'true')
    expect(onClose).not.toHaveBeenCalled()
  })

  test('a lone broker shows no tabs', async () => {
    fetchMock.mockImplementation((url: string, init: RequestInit) => String(url).includes('/reconcile')
      ? respond({ ...JSON.parse(init.body as string), toRemove: [] })
      : respond({ ...cas, holdings: cas.holdings.map(h => ({ ...h, account: 'Zerodha Broking' })) }))
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(screen.getByText(/Matched against your holdings at/)).toBeInTheDocument())
    expect(screen.queryByRole('tab')).toBeNull()
  })

  test('lines that name no institution take the broker typed for them', async () => {
    fetchMock.mockImplementation((url: string, init: RequestInit) => String(url).includes('/reconcile')
      ? respond({ ...JSON.parse(init.body as string), toRemove: [] })
      : respond({ ...cas, holdings: [...cas.holdings.slice(0, 1), { instrumentName: 'Beta Corp', isin: 'INE111A01011', quantity: 1, kind: 'STOCK' }] }))
    await open()

    expect(screen.getByText('Broker for lines that name none')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    const fields = screen.getAllByPlaceholderText(/Pick one of yours/)
    fireEvent.change(fields[0], { target: { value: 'Kite' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Kite' })).toBeInTheDocument())
  })
})
