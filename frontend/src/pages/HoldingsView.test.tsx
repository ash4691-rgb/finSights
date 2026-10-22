import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { HoldingsView, HoldingDrawer, HoldingModal } from './HoldingsView'
import type { Category, Holding } from '../types'

const holding: Holding = {
  id: 'h1', holdingId: 'HLD-1', categoryId: 'c1', categoryName: 'Equity', name: 'Reliance Industries',
  kind: 'ASSET', valuationMethod: 'MANUAL', currency: 'INR', defaultCurrency: 'INR',
  investedValue: 1000, currentValue: 1200, profitLoss: 200, profitLossPercentage: 20, realisedProfitLoss: 0,
  accruedIncome: 0, liquidWithinSevenDays: false, blocked: false, tags: [],
}

const baseCategory = {
  investedValue: 0, currentValue: 0, profitLoss: 0, profitLossPercentage: 0,
  weightagePercent: 0, liquidAmount: 0, liquidPercent: 0, npaAmount: 0, npaPercent: 0, holdingCount: 0,
}
const realEstateCategory: Category = { id: 'c-re', name: 'Real Estate', kind: 'ASSET', realEstate: true, ...baseCategory }
const equityCategory: Category = { id: 'c-eq', name: 'Growth Equity', kind: 'ASSET', realEstate: false, ...baseCategory }

const okResponse = (body: unknown = {}, status = 200) =>
  Promise.resolve({ ok: true, status, json: () => Promise.resolve(body) } as Response)
const notFoundResponse = () => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as Response)
const errorResponse = (message: string, status = 500) =>
  Promise.resolve({ ok: false, status, json: () => Promise.resolve({ message }) } as Response)

describe('HoldingsView — row delete', () => {
  let confirmSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    confirmSpy.mockRestore()
    cleanup()
  })

  test('shows "Deleting…" and disables the row while the request is in flight, then reloads', async () => {
    let resolveDelete!: (value: Response) => void
    const fetchMock = vi.fn().mockReturnValue(new Promise<Response>(resolve => { resolveDelete = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const reload = vi.fn().mockResolvedValue(undefined)

    render(<HoldingsView holdings={[holding]} categories={[]} reload={reload} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(confirmSpy).toHaveBeenCalledWith('Delete Reliance Industries?')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Deleting…' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled()

    resolveDelete(await okResponse(undefined, 204))
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
  })

  test('shows an error and re-enables the row if the delete fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(errorResponse('Holding has linked transactions')))
    const reload = vi.fn().mockResolvedValue(undefined)

    render(<HoldingsView holdings={[holding]} categories={[]} reload={reload} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.getByText('Holding has linked transactions')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled()
    expect(reload).not.toHaveBeenCalled()
  })

  test('does not call the API when the confirm dialog is dismissed', () => {
    confirmSpy.mockReturnValue(false)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<HoldingsView holdings={[holding]} categories={[]} reload={vi.fn()} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('HoldingDrawer — delete holding', () => {
  let confirmSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    confirmSpy.mockRestore()
    cleanup()
  })

  const renderDrawer = (fetchMock: ReturnType<typeof vi.fn>, onClose = vi.fn(), reload = vi.fn().mockResolvedValue(undefined)) => {
    vi.stubGlobal('fetch', fetchMock)
    render(<HoldingDrawer holding={holding} displayCurrency="INR" fxRatesToBase={{}} onClose={onClose} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={reload} />)
    return { onClose, reload }
  }

  test('shows "Deleting…" while in flight, disables other actions, then closes on success', async () => {
    let resolveDelete!: (value: Response) => void
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'DELETE') return new Promise<Response>(resolve => { resolveDelete = resolve })
      return notFoundResponse()
    })
    const { onClose, reload } = renderDrawer(fetchMock)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete holding' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Deleting…' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Edit holding' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '+ Log transaction' })).toBeDisabled()

    resolveDelete(await okResponse(undefined, 204))
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  test('shows an error and stays open if the delete fails', async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'DELETE') return errorResponse('Could not delete holding')
      return notFoundResponse()
    })
    const { onClose } = renderDrawer(fetchMock)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete holding' }))

    await waitFor(() => expect(screen.getByText('Could not delete holding')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Delete holding' })).toBeEnabled()
    expect(onClose).not.toHaveBeenCalled()
  })
})

// Real estate is one holding per unit — quantity doesn't mean anything, so the Add/Edit Holding
// form swaps it for an area + unit pair whenever the selected category is real estate.
describe('HoldingModal — real estate area/unit', () => {
  const tagSuggestionsOk = () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)

  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('shows Area + Unit instead of Quantity for a real-estate category', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(tagSuggestionsOk()))

    render(<HoldingModal holding={null} category={realEstateCategory} categories={[realEstateCategory]} holdings={[]}
      displayCurrency="INR" onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(await screen.findByLabelText(/^Area/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Unit/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument()
  })

  test('shows Quantity, not Area/Unit, for a non-real-estate category', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(tagSuggestionsOk()))

    render(<HoldingModal holding={null} category={equityCategory} categories={[equityCategory]} holdings={[]}
      displayCurrency="INR" onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(await screen.findByLabelText('Quantity')).toBeInTheDocument()
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
  })

  test('submits area/unit and nulls quantity for a real-estate holding', async () => {
    let posted: Record<string, unknown> | null = null
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'POST' && !url.includes('tag-suggestions')) {
        posted = JSON.parse(init!.body as string)
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ ...holding, id: 'h-re' }) } as Response)
      }
      return tagSuggestionsOk()
    })
    vi.stubGlobal('fetch', fetchMock)
    const onSaved = vi.fn()

    render(<HoldingModal holding={null} category={realEstateCategory} categories={[realEstateCategory]} holdings={[]}
      displayCurrency="INR" onClose={vi.fn()} onSaved={onSaved} />)

    fireEvent.change(await screen.findByLabelText(/^Area/), { target: { value: '1200' } })
    fireEvent.change(screen.getByLabelText(/^Unit/), { target: { value: 'ACRE' } })
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: '2BHK Flat' } })
    fireEvent.change(screen.getByLabelText(/^Broker \/ platform/), { target: { value: 'Self' } })
    fireEvent.change(screen.getByLabelText(/^Invested value/), { target: { value: '5000000' } })
    fireEvent.change(screen.getByLabelText(/^Current value/), { target: { value: '6000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add holding' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({ area: 1200, areaUnit: 'ACRE', quantity: null })
  })
})
