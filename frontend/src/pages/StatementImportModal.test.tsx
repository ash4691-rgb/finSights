import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { StatementImportModal } from './StatementImportModal'
import type { Category, Holding, StatementExtractionResult } from '../types'

const category: Category = {
  id: 'cat-1', name: 'Equities', kind: 'ASSET', investedValue: 0, currentValue: 0, profitLoss: 0,
  profitLossPercentage: 0, weightagePercent: 0, liquidAmount: 0, liquidPercent: 0, npaAmount: 0,
  npaPercent: 0, holdingCount: 0,
}
const fundCategory: Category = { ...category, id: 'cat-2', name: 'Mutual Funds' }

const existingHolding: Holding = {
  id: 'h-existing', holdingId: 'h-existing', categoryId: 'cat-1', categoryName: 'Equities', name: 'Infosys',
  kind: 'ASSET', valuationMethod: 'MANUAL', currency: 'INR', defaultCurrency: 'INR', investedValue: 1000,
  currentValue: 1200, profitLoss: 200, profitLossPercentage: 20, realisedProfitLoss: 0, accruedIncome: 0,
  liquidWithinSevenDays: false, blocked: false, tags: [], broker: 'Zerodha',
}

const jsonResponse = (body: unknown, ok = true, status = ok ? 200 : 500) =>
  Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as Response)

const empty: StatementExtractionResult = { statementType: 'HOLDINGS', holdings: [], toRemove: [], transactions: [], warnings: [] }

function renderModal(overrides: Partial<Parameters<typeof StatementImportModal>[0]> = {}) {
  const onClose = vi.fn()
  const onSaved = vi.fn()
  const onRemoved = vi.fn()
  const onTransactionSaved = vi.fn()
  const utils = render(<StatementImportModal
    categories={[category, fundCategory]} holdings={[existingHolding]} displayCurrency="INR"
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

  // Uploads a file whose first read is `extracted`, confirms with `broker` (typed only when the
  // statement didn't name one), and lands on the review step with `matched` as the reconcile reply.
  const reachReview = async (extracted: StatementExtractionResult, matched: StatementExtractionResult, broker = 'Zerodha') => {
    fetchMock.mockReturnValueOnce(jsonResponse(extracted))
    fetchMock.mockReturnValueOnce(jsonResponse(matched))
    const utils = renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Check what we found')).toBeInTheDocument())
    if (!extracted.broker) fireEvent.change(screen.getByPlaceholderText(/Pick one of yours/), { target: { value: broker } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(screen.getByText(/Matched against your holdings at/)).toBeInTheDocument())
    return utils
  }

  test('renders the upload entry point with Done enabled before anything is picked', () => {
    renderModal()
    expect(screen.getByText('Upload a statement')).toBeInTheDocument()
    expect(screen.getByText(/Choose a statement/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Done' })).toBeEnabled()
  })

  test('the file picker accepts PDF, XLSX/XLS, CSV/TSV, and XML, and the hint names them', () => {
    renderModal()
    const input = document.querySelector('input[type=file]') as HTMLInputElement
    for (const ext of ['.pdf', '.xlsx', '.xls', '.csv', '.tsv', '.xml']) expect(input.accept).toContain(ext)
    expect(screen.getByText(/PDF, XLSX\/XLS, CSV\/TSV, or XML/)).toBeInTheDocument()
  })

  test('accepts an XLSX upload the same way as a CSV one', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse(empty))
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
  })

  test('falls back to a generic message when the failure carries none', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({}, false, 500))
    renderModal()
    await pickFile()
    await waitFor(() => expect(screen.getByText('Could not read that statement')).toBeInTheDocument())
  })

  test('offers a retry link once a password-related error and a file are both present, and resubmits on click', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ message: 'That password did not work — check it and try again.' }, false, 400))
    renderModal()
    await pickFile('cas.pdf')
    await waitFor(() => expect(screen.getByText(/password did not work/)).toBeInTheDocument())

    fetchMock.mockReturnValueOnce(jsonResponse(empty))
    fireEvent.click(screen.getByRole('button', { name: 'try this file again' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  test('sends the password header only when one was entered', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse(empty))
    renderModal()

    fireEvent.change(document.querySelector('input[type=password]') as HTMLInputElement, { target: { value: 'ABCDE1234F' } })
    await pickFile('cas.pdf')
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>)['X-Document-Password']).toBe('ABCDE1234F')
  })

  describe('confirm step', () => {
    const fundStatement: StatementExtractionResult = {
      statementType: 'HOLDINGS', broker: 'Groww', warnings: [], toRemove: [], transactions: [],
      holdings: [{ instrumentName: 'Alpha Small Cap Fund', quantity: 40, kind: 'MUTUAL_FUND', investedValue: 6000, currentValue: 7500, averageCostPerUnit: 150 }],
    }

    test('shows the detected type and broker, and guesses a category per kind', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(fundStatement))
      renderModal()
      await pickFile()

      await waitFor(() => expect(screen.getByText('Check what we found')).toBeInTheDocument())
      expect(screen.getByDisplayValue('Groww')).toBeInTheDocument()
      expect(screen.getByText(/Detected from the statement: Groww/)).toBeInTheDocument()
      expect(screen.getByText('Holdings (detected)')).toBeInTheDocument()
      expect((screen.getByLabelText(/Category for mutual funds/) as HTMLSelectElement).value).toBe('cat-2')
      expect(screen.getByRole('button', { name: 'Done' })).toBeDisabled()
    })

    test('asks for a broker when the statement names none and blocks Continue until one is given', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse({ ...fundStatement, broker: undefined }))
      renderModal()
      await pickFile()
      await waitFor(() => expect(screen.getByText(/couldn't tell which broker/)).toBeInTheDocument())

      expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
      fireEvent.change(screen.getByPlaceholderText(/Pick one of yours/), { target: { value: 'Zerodha' } })
      expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
    })

    test('sends the confirmed type, broker and rows to reconcile', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(fundStatement))
      fetchMock.mockReturnValueOnce(jsonResponse(fundStatement))
      renderModal()
      await pickFile()
      await waitFor(() => expect(screen.getByText('Check what we found')).toBeInTheDocument())

      fireEvent.change(screen.getByDisplayValue('Holdings (detected)'), { target: { value: 'MIXED' } })
      fireEvent.change(screen.getByDisplayValue('Groww'), { target: { value: ' Groww Pro ' } })
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      const [url, init] = fetchMock.mock.calls[1]
      expect(url).toContain('/api/statements/reconcile')
      const body = JSON.parse(init.body as string)
      expect(body).toMatchObject({ statementType: 'MIXED', broker: 'Groww Pro' })
      expect(body.holdings).toHaveLength(1)
    })

    test('lets the user pick a different category for a kind', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(fundStatement))
      renderModal()
      await pickFile()
      const select = await screen.findByLabelText(/Category for mutual funds/)
      fireEvent.change(select, { target: { value: 'cat-1' } })
      expect((select as HTMLSelectElement).value).toBe('cat-1')
    })

    test('shows the reconcile error and stays on the confirm step', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(fundStatement))
      fetchMock.mockReturnValueOnce(jsonResponse({ message: 'Pick the broker this statement belongs to first.' }, false, 400))
      renderModal()
      await pickFile()
      await waitFor(() => expect(screen.getByText('Check what we found')).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

      await waitFor(() => expect(screen.getByText(/Pick the broker this statement belongs to first/)).toBeInTheDocument())
      expect(screen.getByText('Check what we found')).toBeInTheDocument()
    })
  })

  describe('review step', () => {
    const mixed: StatementExtractionResult = {
      statementType: 'MIXED', broker: 'Zerodha',
      holdings: [
        { instrumentName: 'Tata Motors', isin: 'INE155A01022', quantity: 10, averageCostPerUnit: 500 },
        { instrumentName: 'Infosys', isin: 'INE009A01021', quantity: 5 },
      ],
      transactions: [{ date: '2026-01-15', type: 'BUY', instrumentName: 'Infosys', isin: 'INE009A01021', amount: 6000, quantity: 5 }],
      toRemove: [], statementDate: '2026-01-20', warnings: ['Skipped a bond line — out of scope.'],
    }
    const matchedMixed: StatementExtractionResult = {
      ...mixed,
      holdings: [mixed.holdings[0], { ...mixed.holdings[1], matchedHoldingId: 'h-existing' }],
      transactions: [{ ...mixed.transactions[0], matchedHoldingId: 'h-existing' }],
      toRemove: [{ holdingId: 'h-gone', name: 'Wipro', broker: 'Zerodha', quantity: 12 }],
    }

    test('shows holdings, transactions, missing and matched sections, the date and warnings', async () => {
      await reachReview(mixed, matchedMixed)

      expect(screen.getByText('Holdings to review')).toBeInTheDocument()
      expect(screen.getByText('Transactions to review')).toBeInTheDocument()
      expect(screen.getByText('Missing from this statement')).toBeInTheDocument()
      expect(screen.getByText('Successfully parsed and mapped')).toBeInTheDocument()
      expect(screen.getByText(/statement as of 2026-01-20/)).toBeInTheDocument()
      expect(screen.getByText(/Skipped a bond line/)).toBeInTheDocument()
      expect(screen.getByText('Tata Motors')).toBeInTheDocument()
      expect(screen.getByText('Wipro')).toBeInTheDocument()
      expect(screen.getByText(/12 held/)).toBeInTheDocument()

      // One holding, one transaction and one missing holding all block Done.
      expect(screen.getByRole('button', { name: 'Done' })).toBeDisabled()
      expect(screen.getByText(/3 rows still need review/)).toBeInTheDocument()
    })

    test('skipping every pending row and resolving the missing holding re-enables Done', async () => {
      await reachReview(mixed, matchedMixed)

      fireEvent.click(screen.getAllByRole('button', { name: 'Skip' })[0])
      fireEvent.click(screen.getAllByRole('button', { name: 'Skip' })[0])
      fireEvent.click(screen.getByRole('button', { name: 'Keep' }))

      await waitFor(() => expect(screen.getByRole('button', { name: 'Done' })).toBeEnabled())
      expect(screen.queryByText('Missing from this statement')).not.toBeInTheDocument()
    })

    test('Delete holding is applied on Done, which reports it and closes', async () => {
      const { onRemoved, onClose } = await reachReview(empty, { ...empty, broker: 'Zerodha', toRemove: [{ holdingId: 'h-gone', name: 'Wipro', broker: 'Zerodha' }] })
      fetchMock.mockReturnValueOnce(Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) } as Response))

      fireEvent.click(screen.getByRole('button', { name: 'Delete holding' }))
      expect(screen.getByText(/1 holding will be deleted when you click Done/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Done' }))

      await waitFor(() => expect(onRemoved).toHaveBeenCalledWith(['h-gone']))
      expect(onClose).toHaveBeenCalledTimes(1)
      expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/holdings/h-gone'), expect.objectContaining({ method: 'DELETE' }))
    })

    test('a failed deletion is reported and the modal stays open', async () => {
      const { onClose } = await reachReview(empty, { ...empty, broker: 'Zerodha', toRemove: [{ holdingId: 'h-gone', name: 'Wipro' }] })
      fetchMock.mockReturnValueOnce(jsonResponse({ message: 'Nope' }, false, 400))

      fireEvent.click(screen.getByRole('button', { name: 'Delete holding' }))
      fireEvent.click(screen.getByRole('button', { name: 'Done' }))

      await waitFor(() => expect(screen.getByText('Nope')).toBeInTheDocument())
      expect(onClose).not.toHaveBeenCalled()
    })

    test('Log a SELL opens the transaction form for the whole position, and saving resolves the row', async () => {
      const { onTransactionSaved } = await reachReview(empty, { ...empty, broker: 'Zerodha', toRemove: [{ holdingId: 'h-existing', name: 'Infosys', broker: 'Zerodha', quantity: 12 }] })

      fireEvent.click(screen.getByRole('button', { name: 'Log a SELL' }))

      expect(await screen.findByDisplayValue('12')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Not listed on the Zerodha statement')).toBeInTheDocument()
      fetchMock.mockReturnValueOnce(jsonResponse({}))
      fireEvent.change(screen.getByRole('spinbutton', { name: /Sale proceeds/ }), { target: { value: '15000' } })
      fireEvent.click(screen.getByRole('button', { name: 'Log transaction' }))

      await waitFor(() => expect(onTransactionSaved).toHaveBeenCalled())
      await waitFor(() => expect(screen.queryByText('Missing from this statement')).not.toBeInTheDocument())
    })

    test('closing the SELL form leaves the missing holding pending', async () => {
      await reachReview(empty, { ...empty, broker: 'Zerodha', toRemove: [{ holdingId: 'h-existing', name: 'Infosys', broker: 'Zerodha', quantity: 12 }] })
      fireEvent.click(screen.getByRole('button', { name: 'Log a SELL' }))
      await screen.findByDisplayValue('12')

      fireEvent.click(screen.getAllByRole('button', { name: '×' })[1])

      expect(screen.getByText('Missing from this statement')).toBeInTheDocument()
    })

    test('shows a message when the statement yields nothing to extract', async () => {
      await reachReview(empty, { ...empty, broker: 'Zerodha' })
      expect(screen.getByText(/Nothing we could extract from this statement\./)).toBeInTheDocument()
    })

    test('explains why nothing was listed when only warnings came back', async () => {
      await reachReview(empty, { ...empty, broker: 'Zerodha', warnings: ['Skipped a row'] })
      expect(screen.getByText(/couldn't turn any of it into holdings or transactions/)).toBeInTheDocument()
    })

    test('Change broker or type returns to the confirm step', async () => {
      await reachReview(empty, { ...empty, broker: 'Zerodha' })
      fireEvent.click(screen.getByRole('button', { name: 'Change broker or type' }))
      expect(screen.getByText('Check what we found')).toBeInTheDocument()
    })

    test('opening a holding row shows the Add Holding form pre-filled with the broker and the chosen category', async () => {
      const fund: StatementExtractionResult = {
        statementType: 'HOLDINGS', broker: 'Groww', warnings: [], toRemove: [], transactions: [],
        holdings: [{ instrumentName: 'Alpha Small Cap Fund', quantity: 40, kind: 'MUTUAL_FUND', investedValue: 6000, currentValue: 7500, tickerSymbol: 'MF:100' }],
      }
      await reachReview(fund, fund)

      fireEvent.click(screen.getByText('Alpha Small Cap Fund'))

      expect(await screen.findByDisplayValue('Alpha Small Cap Fund')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Groww')).toBeInTheDocument()
      expect(screen.getByDisplayValue('7500')).toBeInTheDocument()
      expect(screen.getByDisplayValue('6000')).toBeInTheDocument()
    })

    test('opening a transaction row shows the Log Transaction form pre-filled from the extracted row', async () => {
      await reachReview(mixed, matchedMixed)

      fireEvent.click(screen.getAllByText('Infosys')[0])

      expect(await screen.findByDisplayValue('6000')).toBeInTheDocument()
    })

    test('saving the pre-filled holding form adds it to the portfolio and resolves the row', async () => {
      const fund: StatementExtractionResult = {
        statementType: 'HOLDINGS', broker: 'Groww', warnings: [], toRemove: [], transactions: [],
        holdings: [{ instrumentName: 'Alpha Small Cap Fund', quantity: 40, kind: 'MUTUAL_FUND', investedValue: 6000, currentValue: 7500 }],
      }
      const { onSaved } = await reachReview(fund, fund)
      fireEvent.click(screen.getByText('Alpha Small Cap Fund'))
      await screen.findByDisplayValue('7500')
      fetchMock.mockReturnValue(jsonResponse({ id: 'h-new', name: 'Alpha Small Cap Fund' }))

      fireEvent.click(screen.getByRole('button', { name: /^(Add|Save) holding$/ }))

      await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ id: 'h-new', name: 'Alpha Small Cap Fund' }))
      await waitFor(() => expect(screen.queryByText('Holdings to review')).not.toBeInTheDocument())
    })

    test('closing a pre-filled form leaves its row pending', async () => {
      await reachReview(mixed, matchedMixed)

      fireEvent.click(screen.getByText('Tata Motors'))
      await screen.findByDisplayValue('Tata Motors')
      fireEvent.click(screen.getAllByRole('button', { name: '×' })[1])
      expect(screen.getByText('Holdings to review')).toBeInTheDocument()

      fireEvent.click(screen.getAllByText('Infosys')[0])
      await screen.findByDisplayValue('6000')
      fireEvent.click(screen.getAllByRole('button', { name: '×' })[1])
      expect(screen.getByText('Transactions to review')).toBeInTheDocument()
    })

    test('saving the pre-filled transaction form logs it and resolves the row', async () => {
      const { onTransactionSaved } = await reachReview(mixed, matchedMixed)
      fireEvent.click(screen.getAllByText('Infosys')[0])
      await screen.findByDisplayValue('6000')
      fetchMock.mockReturnValue(jsonResponse({}))

      fireEvent.click(screen.getByRole('button', { name: 'Log transaction' }))

      await waitFor(() => expect(onTransactionSaved).toHaveBeenCalled())
      await waitFor(() => expect(screen.queryByText('Transactions to review')).not.toBeInTheDocument())
    })

    test('a BUY for a holding you do not have is offered as a new holding built from its first buy', async () => {
      const unmatched: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Zerodha', holdings: [], toRemove: [], warnings: [],
        transactions: [{ date: '2025-04-03', type: 'BUY', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 4882.32, quantity: 6, tickerSymbol: 'EXAMPLE.NS' }],
      }
      await reachReview(unmatched, unmatched)
      expect(screen.getByText('New holdings from these transactions')).toBeInTheDocument()
      expect(screen.getByText(/first buy 2025-04-03/)).toBeInTheDocument()
      expect(screen.getByText(/Conflict: Create its new holding first/)).toBeInTheDocument()

      fireEvent.click(screen.getAllByText('Example Corp')[0])

      expect((await screen.findAllByDisplayValue('4882.32')).length).toBeGreaterThan(0)
      expect(screen.getByDisplayValue('EXAMPLE.NS')).toBeInTheDocument()
    })

    test('saving the new-holding form creates it, resolves the first buy and attaches the later lines to it', async () => {
      const rows: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Zerodha', holdings: [], toRemove: [], warnings: [],
        transactions: [
          { date: '2025-04-03', type: 'BUY', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 4882.32, quantity: 6 },
          { date: '2025-05-03', type: 'BUY', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 1000, quantity: 1 },
        ],
      }
      const { onSaved } = await reachReview(rows, rows)
      fireEvent.click(screen.getByText(/first buy 2025-04-03/))
      await screen.findAllByDisplayValue('4882.32')
      fetchMock.mockReturnValue(jsonResponse({ id: 'h-new', name: 'Example Corp' }))

      fireEvent.click(screen.getByRole('button', { name: /^(Add|Save) holding$/ }))

      await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ id: 'h-new', name: 'Example Corp' }))
      const holdingCall = fetchMock.mock.calls.find(([url, init]) => String(url).includes('/api/holdings') && init?.method === 'POST')!
      expect(JSON.parse(holdingCall[1].body as string)).toMatchObject({ name: 'Example Corp', broker: 'Zerodha', quantity: 6, fixedRateStartDate: '2025-04-03' })
      await waitFor(() => expect(screen.queryByText('New holdings from these transactions')).not.toBeInTheDocument())
      // The later buy is now attached to the new holding and has no conflict left.
      expect(screen.getByText('Transactions to review')).toBeInTheDocument()
      expect(screen.queryByText(/Conflict:/)).not.toBeInTheDocument()
    })

    test('closing the new-holding form leaves the group pending, and Skip drops all of its lines', async () => {
      const rows: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Zerodha', holdings: [], toRemove: [], warnings: [],
        transactions: [
          { date: '2025-04-03', type: 'BUY', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 4882.32, quantity: 6 },
          { date: '2025-06-03', type: 'SELL', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 900, quantity: 1 },
        ],
      }
      await reachReview(rows, rows)

      fireEvent.click(screen.getByText(/first buy 2025-04-03/))
      await screen.findAllByDisplayValue('4882.32')
      fireEvent.click(screen.getAllByRole('button', { name: '×' })[1])
      expect(screen.getByText('New holdings from these transactions')).toBeInTheDocument()

      fireEvent.click(screen.getAllByRole('button', { name: 'Skip' })[0])
      await waitFor(() => expect(screen.queryByText('New holdings from these transactions')).not.toBeInTheDocument())
      expect(screen.queryByText('Transactions to review')).not.toBeInTheDocument()
    })

    test('a SELL for an instrument with no buy anywhere is flagged and cannot be logged', async () => {
      const orphan: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Zerodha', holdings: [], toRemove: [], warnings: [],
        transactions: [{ date: '2025-04-03', type: 'SELL', instrumentName: 'Orphan Corp', isin: 'INE111A01011', amount: 900, quantity: 1 }],
      }
      await reachReview(orphan, orphan)

      expect(screen.getByText(/Conflict: SELL for a holding you don't have at this broker/)).toBeInTheDocument()
      expect(screen.queryByText('New holdings from these transactions')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Log all (0)' })).toBeDisabled()
    })

    test('a transaction with no matching holding can still be opened and needs a holding picked', async () => {
      const unmatched: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Zerodha', holdings: [], toRemove: [], warnings: [],
        transactions: [{ date: '2025-04-03', type: 'INTEREST', instrumentName: 'Example Corp', isin: 'INE000A01010', amount: 12.5 }],
      }
      await reachReview(unmatched, unmatched)
      expect(screen.getByText(/Conflict: No matching holding at this broker/)).toBeInTheDocument()

      fireEvent.click(screen.getByText('Example Corp'))

      expect(await screen.findByDisplayValue('12.5')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Log transaction' })).toBeDisabled()
    })
  })

  describe('Log all', () => {
    const rows: StatementExtractionResult = {
      statementType: 'MIXED', broker: 'Groww', warnings: [], toRemove: [], statementDate: '2026-10-23',
      holdings: [
        { instrumentName: 'Alpha Small Cap Fund', quantity: 40, kind: 'MUTUAL_FUND', investedValue: 6000, currentValue: 7500 },
        { instrumentName: 'Beta Mid Cap Fund', quantity: 10, kind: 'MUTUAL_FUND' },
      ],
      transactions: [
        { date: '2026-01-15', type: 'BUY', instrumentName: 'Infosys', amount: 6000, quantity: 5, matchedHoldingId: 'h-existing' },
        { date: '2026-01-16', type: 'SELL', instrumentName: 'Orphan', amount: 100, quantity: 1 },
      ],
    }

    test('warns that conflicted rows are skipped, then saves the conflict-free ones', async () => {
      const { onSaved, onTransactionSaved } = await reachReview(rows, rows, 'Groww')
      expect(screen.getByText(/Conflict: No invested amount in the statement/)).toBeInTheDocument()
      expect(screen.getByText(/2 rows ready with no conflicts; 2 with conflicts will be skipped/)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Log all (2)' }))
      expect(screen.getByText(/2 rows with conflicts will be skipped/)).toBeInTheDocument()

      fetchMock.mockReturnValueOnce(jsonResponse({ id: 'h-new' }))
      fetchMock.mockReturnValueOnce(jsonResponse({}))
      fireEvent.click(screen.getByRole('button', { name: 'Yes, log 2' }))

      await waitFor(() => expect(screen.getByText('Logged 2 rows.')).toBeInTheDocument())
      expect(onSaved).toHaveBeenCalledWith({ id: 'h-new' })
      expect(onTransactionSaved).toHaveBeenCalledTimes(1)
      const holdingCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/holdings'))!
      expect(JSON.parse(holdingCall[1].body as string)).toMatchObject({ name: 'Alpha Small Cap Fund', broker: 'Groww', categoryId: 'cat-2', quantity: 40, investedValue: 6000, currentValue: 7500 })
      // Only the two conflicted rows remain to review.
      expect(screen.getByText('Beta Mid Cap Fund')).toBeInTheDocument()
      expect(screen.queryByText('Alpha Small Cap Fund')).not.toBeInTheDocument()
      expect(screen.getByText(/2 rows still need review/)).toBeInTheDocument()
    })

    test('cancelling the confirmation saves nothing', async () => {
      await reachReview(rows, rows, 'Groww')
      fireEvent.click(screen.getByRole('button', { name: 'Log all (2)' }))
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      expect(screen.getByRole('button', { name: 'Log all (2)' })).toBeInTheDocument()
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    test('Log all creates a new holding from its first buy and logs the later lines against it', async () => {
      const rows: StatementExtractionResult = {
        statementType: 'TRANSACTIONS', broker: 'Groww', warnings: [], toRemove: [], holdings: [],
        transactions: [
          { date: '2026-01-01', type: 'BUY', instrumentName: 'Alpha Small Cap Fund', kind: 'MUTUAL_FUND', amount: 1000, quantity: 10, tickerSymbol: 'MF:100' },
          { date: '2026-02-01', type: 'BUY', instrumentName: 'Alpha Small Cap Fund', kind: 'MUTUAL_FUND', amount: 500, quantity: 4 },
          { date: '2026-03-01', type: 'SELL', instrumentName: 'Alpha Small Cap Fund', kind: 'MUTUAL_FUND', quantity: 2 },
        ],
      }
      const { onSaved, onTransactionSaved } = await reachReview(rows, rows, 'Groww')
      expect(screen.getByText(/2 rows ready with no conflicts; 1 with conflicts will be skipped/)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Log all (2)' }))
      fetchMock.mockReturnValueOnce(jsonResponse({ id: 'h-new' }))
      fetchMock.mockReturnValueOnce(jsonResponse({}))
      fireEvent.click(screen.getByRole('button', { name: 'Yes, log 2' }))

      await waitFor(() => expect(screen.getByText('Logged 2 rows.')).toBeInTheDocument())
      expect(onSaved).toHaveBeenCalledWith({ id: 'h-new' })
      expect(onTransactionSaved).toHaveBeenCalledTimes(1)
      const txnCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/transactions'))!
      expect(JSON.parse(txnCall[1].body as string)).toMatchObject({ holdingId: 'h-new', type: 'BUY', amount: 500 })
      // Only the SELL, which has no amount, is left to review — now attached to the new holding.
      expect(screen.getByText(/Conflict: No amount in the statement/)).toBeInTheDocument()
      expect(screen.getByText(/1 row still needs review/)).toBeInTheDocument()
    })

    test('a row the server rejects stays in the list and is counted in the summary', async () => {
      const single: StatementExtractionResult = { ...rows, holdings: [rows.holdings[0]], transactions: [] }
      await reachReview(single, single, 'Groww')
      fetchMock.mockReturnValueOnce(jsonResponse({ message: 'Category does not allow that valuation' }, false, 400))

      fireEvent.click(screen.getByRole('button', { name: 'Log all (1)' }))
      fireEvent.click(screen.getByRole('button', { name: 'Yes, log 1' }))

      await waitFor(() => expect(screen.getByText(/Logged 0 rows\. 1 could not be saved — review it below\./)).toBeInTheDocument())
      expect(screen.getByText('Alpha Small Cap Fund')).toBeInTheDocument()
    })

    test('a row with no category chosen is a conflict and is never saved', async () => {
      const stock: StatementExtractionResult = {
        statementType: 'HOLDINGS', broker: 'Groww', warnings: [], toRemove: [], transactions: [],
        holdings: [{ instrumentName: 'Gold Bees', isin: 'INF204KB17I5', quantity: 4, kind: 'ETF', averageCostPerUnit: 50 }],
      }
      await reachReview(stock, stock, 'Groww')

      expect(screen.getByText(/Conflict: No category chosen for etfs/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Log all (0)' })).toBeDisabled()
    })
  })
})
