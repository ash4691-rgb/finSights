import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { StatementImportModal } from './StatementImportModal'
import type { Category, Holding, StatementExtractionResult } from '../types'

const category: Category = {
  id: 'cat-1', name: 'Equities', kind: 'ASSET', investedValue: 0, currentValue: 0, profitLoss: 0,
  profitLossPercentage: 0, weightagePercent: 0, liquidAmount: 0, liquidPercent: 0, npaAmount: 0,
  npaPercent: 0, holdingCount: 0,
}

const existingHolding: Holding = {
  id: 'h-existing', holdingId: 'h-existing', categoryId: 'cat-1', categoryName: 'Equities', name: 'Infosys',
  kind: 'ASSET', valuationMethod: 'MANUAL', currency: 'INR', defaultCurrency: 'INR', investedValue: 1000,
  currentValue: 1200, profitLoss: 200, profitLossPercentage: 20, realisedProfitLoss: 0, accruedIncome: 0,
  liquidWithinSevenDays: false, blocked: false, tags: [],
}

const jsonResponse = (body: unknown, ok = true, status = ok ? 200 : 500) =>
  Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as Response)

function renderModal(overrides: Partial<Parameters<typeof StatementImportModal>[0]> = {}) {
  const onClose = vi.fn()
  const onSaved = vi.fn()
  const onRemoved = vi.fn()
  const onTransactionSaved = vi.fn()
  const utils = render(<StatementImportModal
    categories={[category]} holdings={[existingHolding]} displayCurrency="INR"
    onClose={onClose} onSaved={onSaved} onRemoved={onRemoved} onTransactionSaved={onTransactionSaved}
    {...overrides}
  />)
  return { ...utils, onClose, onSaved, onRemoved, onTransactionSaved }
}

const pickFile = async (name = 'statement.csv', content = 'id,holdingId,type\n') => {
  const input = document.querySelector('input[type=file]') as HTMLInputElement
  const file = new File([content], name, { type: 'text/csv' })
  await fireEvent.change(input, { target: { files: [file] } })
  return file
}

describe('StatementImportModal', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    cleanup()
  })

  test('renders the upload entry point with Done enabled before anything is picked', () => {
    renderModal()
    expect(screen.getByText('Upload a statement')).toBeInTheDocument()
    expect(screen.getByText(/Choose a statement/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Done' })).toBeEnabled()
  })

  test('the file picker accepts PDF, XLSX/XLS, CSV/TSV, and XML, and the hint names them', () => {
    renderModal()
    const input = document.querySelector('input[type=file]') as HTMLInputElement
    expect(input.accept).toContain('.pdf')
    expect(input.accept).toContain('.xlsx')
    expect(input.accept).toContain('.xls')
    expect(input.accept).toContain('.csv')
    expect(input.accept).toContain('.tsv')
    expect(input.accept).toContain('.xml')
    expect(screen.getByText(/PDF, XLSX\/XLS, CSV\/TSV, or XML/)).toBeInTheDocument()
  })

  test('accepts an XLSX upload the same way as a CSV one', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ statementType: 'HOLDINGS', holdings: [], toRemove: [], transactions: [], warnings: [] }))
    renderModal()
    await pickFile('holdings.xlsx', 'binary-xlsx-content')

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/statements/import'), expect.objectContaining({ method: 'POST' })))
    expect(screen.getByText('holdings.xlsx')).toBeInTheDocument()
  })

  test('closing the modal calls onClose', () => {
    const { onClose } = renderModal()
    fireEvent.click(screen.getByRole('button', { name: '×' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  test('shows the server error message when extraction fails', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ message: 'Could not read that statement right now — try again shortly.' }, false, 502))
    renderModal()

    await pickFile()

    await waitFor(() => expect(screen.getByText(/Could not read that statement right now/)).toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/statements/import'), expect.objectContaining({ method: 'POST' }))
  })

  test('offers a retry link once a password-related error and a file are both present, and resubmits on click', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ message: 'That password did not work — check it and try again.' }, false, 400))
    renderModal()
    await pickFile('cas.pdf')
    await waitFor(() => expect(screen.getByText(/password did not work/)).toBeInTheDocument())

    fetchMock.mockReturnValueOnce(jsonResponse({ statementType: 'HOLDINGS', holdings: [], toRemove: [], transactions: [], warnings: [] }))
    fireEvent.click(screen.getByRole('button', { name: 'try this file again' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  test('shows holdings and transactions to review, matched holdings, and warnings after a successful upload', async () => {
    const result: StatementExtractionResult = {
      statementType: 'MIXED',
      holdings: [
        { instrumentName: 'Tata Motors', isin: 'INE155A01022', quantity: 10, averageCostPerUnit: 500 },
        { instrumentName: 'Infosys', isin: 'INE009A01021', quantity: 5, matchedHoldingId: 'h-existing' },
      ],
      transactions: [
        { date: '2026-01-15', type: 'BUY', instrumentName: 'Infosys', isin: 'INE009A01021', amount: 6000, quantity: 5, matchedHoldingId: 'h-existing' },
      ],
      toRemove: [{ holdingId: 'h-gone', name: 'Wipro', broker: 'Zerodha' }],
      statementDate: '2026-01-20',
      warnings: ['Skipped a mutual fund line — out of scope.'],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    renderModal()
    await pickFile()

    await waitFor(() => expect(screen.getByText('Holdings to review')).toBeInTheDocument())
    expect(screen.getByText('Transactions to review')).toBeInTheDocument()
    expect(screen.getByText('To be deleted')).toBeInTheDocument()
    expect(screen.getByText('Successfully parsed and mapped')).toBeInTheDocument()
    expect(screen.getByText('Statement as of 2026-01-20.')).toBeInTheDocument()
    expect(screen.getByText(/Skipped a mutual fund line/)).toBeInTheDocument()
    expect(screen.getByText('Tata Motors')).toBeInTheDocument()
    expect(screen.getByText('Wipro')).toBeInTheDocument()

    // One pending holding + one pending transaction block Done.
    expect(screen.getByRole('button', { name: 'Done' })).toBeDisabled()
    expect(screen.getByText(/2 rows still need review/)).toBeInTheDocument()
  })

  test('skipping every pending row re-enables Done, and the matched/toRemove sections stay unaffected', async () => {
    const result: StatementExtractionResult = {
      statementType: 'HOLDINGS',
      holdings: [{ instrumentName: 'Tata Motors', isin: 'INE155A01022', quantity: 10 }],
      transactions: [],
      toRemove: [],
      warnings: [],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Holdings to review')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))

    await waitFor(() => expect(screen.queryByText('Holdings to review')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Done' })).toBeEnabled()
  })

  test('keeping a flagged holding removes it from the to-delete list', async () => {
    const result: StatementExtractionResult = {
      statementType: 'HOLDINGS',
      holdings: [],
      transactions: [],
      toRemove: [{ holdingId: 'h-gone', name: 'Wipro', broker: 'Zerodha' }],
      warnings: [],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('To be deleted')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Keep/ }))

    await waitFor(() => expect(screen.queryByText('To be deleted')).not.toBeInTheDocument())
  })

  test('Done deletes every remaining flagged holding, then reports it and closes', async () => {
    const result: StatementExtractionResult = {
      statementType: 'HOLDINGS',
      holdings: [],
      transactions: [],
      toRemove: [{ holdingId: 'h-gone', name: 'Wipro', broker: 'Zerodha' }],
      warnings: [],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    fetchMock.mockReturnValueOnce(Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) } as Response))

    const { onRemoved, onClose } = renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('To be deleted')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Done' }))

    await waitFor(() => expect(onRemoved).toHaveBeenCalledWith(['h-gone']))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/holdings/h-gone'), expect.objectContaining({ method: 'DELETE' }))
  })

  test('shows a message when the statement yields nothing to extract', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ statementType: 'HOLDINGS', holdings: [], toRemove: [], transactions: [], warnings: [] }))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Nothing we could extract from this statement.')).toBeInTheDocument())
  })

  test('opening a holding row shows the Add Holding review form pre-filled from the extracted row', async () => {
    const result: StatementExtractionResult = {
      statementType: 'HOLDINGS',
      holdings: [{ instrumentName: 'Tata Motors', isin: 'INE155A01022', quantity: 10, averageCostPerUnit: 250, tickerSymbol: 'TATAMOTORS.NS' }],
      transactions: [],
      toRemove: [],
      warnings: [],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Holdings to review')).toBeInTheDocument())

    fireEvent.click(screen.getByText('Tata Motors'))

    const nameInputs = await screen.findAllByDisplayValue('Tata Motors')
    expect(nameInputs.length).toBeGreaterThan(0)
  })

  test('opening a transaction row shows the Log Transaction review form pre-filled from the extracted row', async () => {
    const result: StatementExtractionResult = {
      statementType: 'TRANSACTIONS',
      holdings: [],
      transactions: [{ date: '2026-01-15', type: 'BUY', instrumentName: 'Infosys', isin: 'INE009A01021', amount: 6000, quantity: 5, matchedHoldingId: 'h-existing' }],
      toRemove: [],
      warnings: [],
    }
    fetchMock.mockReturnValueOnce(jsonResponse(result))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Transactions to review')).toBeInTheDocument())

    fireEvent.click(screen.getByText('Infosys'))

    expect(await screen.findByDisplayValue('6000')).toBeInTheDocument()
  })

  test('sends the password header only when one was entered', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ statementType: 'HOLDINGS', holdings: [], toRemove: [], transactions: [], warnings: [] }))
    renderModal()

    fireEvent.change(document.querySelector('input[type=password]') as HTMLInputElement, { target: { value: 'ABCDE1234F' } })
    await pickFile('cas.pdf')
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>)['X-Document-Password']).toBe('ABCDE1234F')
  })
})
