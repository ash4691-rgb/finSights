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

// A real-estate holding shows its area + unit, not quantity, wherever a holding's quantity
// is otherwise displayed — the Holdings table row and the holding drawer.
describe('real-estate holdings show area, not quantity', () => {
  afterEach(() => cleanup())

  const realEstateHolding: Holding = {
    ...holding, id: 'h-re', name: '2BHK Flat, Koramangala', categoryName: 'Real Estate',
    quantity: undefined, area: 1200, areaUnit: 'SQ_FT', manualType: 'REAL_ESTATE',
    manual: { purchaseUnitPrice: 4000, currentUnitPrice: 4500 },
  }

  test('Holdings table shows area + unit instead of quantity', () => {
    render(<HoldingsView holdings={[realEstateHolding]} categories={[]} reload={vi.fn()} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)

    expect(screen.getByText('1200')).toBeInTheDocument()
    expect(screen.getByText(/sq\. ft/)).toBeInTheDocument()
    expect(screen.getByText(/MANUAL · Real estate/)).toBeInTheDocument()
  })

  test('a non-real-estate holding still shows quantity in the table', () => {
    render(<HoldingsView holdings={[holding]} categories={[]} reload={vi.fn()} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)

    expect(screen.queryByText('sq. ft')).not.toBeInTheDocument()
  })

  test('the holding drawer shows Area, not Quantity, for a real-estate holding', () => {
    render(<HoldingDrawer holding={realEstateHolding} displayCurrency="INR" fxRatesToBase={{}}
      onClose={vi.fn()} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={vi.fn()} />)

    expect(screen.getByText('Area')).toBeInTheDocument()
    expect(screen.getByText('1200 sq. ft')).toBeInTheDocument()
    expect(screen.getByText('Purchase unit price')).toBeInTheDocument()
    expect(screen.getByText('Current unit price')).toBeInTheDocument()
    expect(screen.queryByText('Quantity')).not.toBeInTheDocument()
  })

  test('the holding drawer still shows Quantity for a non-real-estate holding', () => {
    render(<HoldingDrawer holding={{ ...holding, quantity: 5 }} displayCurrency="INR" fxRatesToBase={{}}
      onClose={vi.fn()} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={vi.fn()} />)

    expect(screen.getByText('Quantity')).toBeInTheDocument()
    expect(screen.queryByText('Area')).not.toBeInTheDocument()
  })
})

// Typed manual holdings: the Manual type dropdown (Default / Real estate / EPFO) drives which inputs
// the form asks for, and invested/current values are calculated from them rather than typed in.
describe('HoldingModal — manual types', () => {
  const tagSuggestionsOk = () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
  let posted: Record<string, unknown> | null = null

  const stubFetch = () => {
    posted = null
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const method = (init?.method ?? 'GET').toUpperCase()
      if (method === 'POST' && !url.includes('tag-suggestions')) {
        posted = JSON.parse(init!.body as string)
        return okResponse({ ...holding, id: 'h-new' }, 201)
      }
      return tagSuggestionsOk()
    }))
  }
  const renderNew = (category: Category, onSaved = vi.fn()) => {
    stubFetch()
    render(<HoldingModal holding={null} category={category} categories={[realEstateCategory, equityCategory]} holdings={[]}
      displayCurrency="INR" onClose={vi.fn()} onSaved={onSaved} />)
    return onSaved
  }
  const fillBasics = (name: string) => {
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: name } })
    fireEvent.change(screen.getByLabelText(/^Broker \/ platform/), { target: { value: 'Self' } })
  }

  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('a real-estate category preselects Manual + Real estate and asks for area, unit and unit prices, not quantity', async () => {
    renderNew(realEstateCategory)

    expect(await screen.findByLabelText(/^Area/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Unit/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Purchase unit price/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Current unit price/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Manual type/)).toHaveValue('REAL_ESTATE')
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^Invested value$/)).not.toBeInTheDocument()
  })

  test('liquid is unchecked by default for a real-estate holding', async () => {
    renderNew(realEstateCategory)

    expect(await screen.findByLabelText(/Liquid within 7 days/)).not.toBeChecked()
  })

  test('a plain category keeps Quantity and the Default manual type', async () => {
    renderNew(equityCategory)

    expect(await screen.findByLabelText('Quantity')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Manual type/)).toHaveValue('DEFAULT')
    expect(screen.queryByLabelText(/^Area/)).not.toBeInTheDocument()
  })

  test('calculates invested and current values from area x unit price, defaulting current to purchase', async () => {
    renderNew(realEstateCategory)

    fireEvent.change(await screen.findByLabelText(/^Area/), { target: { value: '1200' } })
    fireEvent.change(screen.getByLabelText(/^Purchase unit price/), { target: { value: '4000' } })

    expect(screen.getByLabelText('Invested value (calculated)')).toHaveValue('4800000')
    expect(screen.getByLabelText('Current value (calculated)')).toHaveValue('4800000')

    fireEvent.change(screen.getByLabelText(/^Current unit price/), { target: { value: '5000' } })
    expect(screen.getByLabelText('Current value (calculated)')).toHaveValue('6000000')
  })

  test('submits the real-estate payload with calculated values and no quantity', async () => {
    const onSaved = renderNew(realEstateCategory)

    fireEvent.change(await screen.findByLabelText(/^Area/), { target: { value: '1200' } })
    fireEvent.change(screen.getByLabelText(/^Unit/), { target: { value: 'ACRE' } })
    fireEvent.change(screen.getByLabelText(/^Purchase unit price/), { target: { value: '4000' } })
    fillBasics('2BHK Flat')
    fireEvent.click(screen.getByRole('button', { name: 'Add holding' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({
      manualType: 'REAL_ESTATE', area: 1200, areaUnit: 'ACRE', quantity: null, valuationMethod: 'MANUAL',
      investedValue: 4800000, currentValue: 4800000, manual: { purchaseUnitPrice: 4000 },
    })
  })

  test('Add holding stays disabled until the unit price is filled in', async () => {
    renderNew(realEstateCategory)

    fireEvent.change(await screen.findByLabelText(/^Area/), { target: { value: '10' } })
    fillBasics('Plot')

    expect(screen.getByRole('button', { name: 'Add holding' })).toBeDisabled()
  })

  test('choosing EPFO swaps in the contribution fields and sums the split into the calculated values', async () => {
    renderNew(equityCategory)

    fireEvent.change(await screen.findByLabelText(/^Manual type/), { target: { value: 'EPFO' } })
    fireEvent.change(screen.getByLabelText(/^Employee contribution/), { target: { value: '100000' } })
    fireEvent.change(screen.getByLabelText(/^Employer contribution/), { target: { value: '60000' } })
    fireEvent.change(screen.getByLabelText(/^Interest accumulated/), { target: { value: '12000' } })

    expect(screen.getByLabelText(/^Total balance/)).toBeDisabled()
    expect(screen.getByLabelText('Invested value (calculated)')).toHaveValue('148000')
    expect(screen.getByLabelText('Current value (calculated)')).toHaveValue('160000')
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument()
  })

  test('submits an EPFO holding with current = balance, invested = balance - interest and the rate as a fraction', async () => {
    const onSaved = renderNew(equityCategory)

    fireEvent.change(await screen.findByLabelText(/^Manual type/), { target: { value: 'EPFO' } })
    fireEvent.change(screen.getByLabelText(/^Total balance/), { target: { value: '200000' } })
    fireEvent.change(screen.getByLabelText(/^Interest accumulated/), { target: { value: '10000' } })
    fireEvent.change(screen.getByLabelText(/^Expected interest rate/), { target: { value: '8.25' } })
    fillBasics('EPF - Acme')
    fireEvent.click(screen.getByRole('button', { name: 'Add holding' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({
      manualType: 'EPFO', quantity: null, area: null, investedValue: 190000, currentValue: 200000,
      manual: { interestAccumulated: 10000, expectedInterestRate: 0.0825 },
    })
  })

  test('an EPFO balance with interest above it blocks saving', async () => {
    renderNew(equityCategory)

    fireEvent.change(await screen.findByLabelText(/^Manual type/), { target: { value: 'EPFO' } })
    fireEvent.change(screen.getByLabelText(/^Total balance/), { target: { value: '5000' } })
    fireEvent.change(screen.getByLabelText(/^Interest accumulated/), { target: { value: '6000' } })
    fillBasics('EPF')

    expect(screen.getByRole('button', { name: 'Add holding' })).toBeDisabled()
  })

  test('picking a real-estate category on a new holding preselects the real-estate type', async () => {
    renderNew(equityCategory)

    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: 'c-re' } })

    expect(screen.getByLabelText(/^Manual type/)).toHaveValue('REAL_ESTATE')
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: 'c-eq' } })
    expect(screen.getByLabelText(/^Manual type/)).toHaveValue('DEFAULT')
  })

  test('editing a real-estate holding asks for area and both unit prices and recalculates', async () => {
    const existing: Holding = {
      ...holding, id: 'h-re', categoryId: 'c-re', name: 'Flat', broker: 'Self', valuationMethod: 'MANUAL',
      manualType: 'REAL_ESTATE', area: 1000, areaUnit: 'SQ_FT', investedValue: 4000000, currentValue: 4000000,
      manual: { purchaseUnitPrice: 4000, currentUnitPrice: 4000 },
    }
    vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('tag-suggestions') ? tagSuggestionsOk() : okResponse(existing)))

    render(<HoldingModal holding={existing} categories={[realEstateCategory]} holdings={[]}
      displayCurrency="INR" onClose={vi.fn()} onSaved={vi.fn()} />)

    const area = await screen.findByLabelText(/^Area/)
    expect(area).toHaveValue(1000)
    expect(screen.getByLabelText(/^Purchase unit price/)).toHaveValue(4000)
    await waitFor(() => expect(screen.getByLabelText(/^Area/)).not.toBeDisabled())
    fireEvent.change(screen.getByLabelText(/^Current unit price/), { target: { value: '4500' } })

    expect(screen.getByLabelText('Current value (calculated)')).toHaveValue('4500000')
    expect(screen.getByText(/adjustment transaction/)).toBeInTheDocument()
  })
})

describe('manual type display', () => {
  afterEach(() => cleanup())

  const epfoHolding: Holding = {
    ...holding, id: 'h-epf', name: 'EPF', manualType: 'EPFO',
    manual: { employeeContribution: 100000, employerContribution: 60000, interestAccumulated: 12000, expectedInterestRate: 0.0825 },
  }

  test('the drawer shows the EPFO contributions and rate', () => {
    render(<HoldingDrawer holding={epfoHolding} displayCurrency="INR" fxRatesToBase={{}}
      onClose={vi.fn()} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={vi.fn()} />)

    expect(screen.getByText('Employee contribution')).toBeInTheDocument()
    expect(screen.getByText('Employer contribution')).toBeInTheDocument()
    expect(screen.getByText('Interest accumulated')).toBeInTheDocument()
    expect(screen.getByText('Expected rate')).toBeInTheDocument()
    expect(screen.queryByText('Quantity')).not.toBeInTheDocument()
  })

  test('the drawer skips EPFO facts that were not entered', () => {
    render(<HoldingDrawer holding={{ ...epfoHolding, manual: {} }} displayCurrency="INR" fxRatesToBase={{}}
      onClose={vi.fn()} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={vi.fn()} />)

    expect(screen.getByText('Manual type')).toBeInTheDocument()
    expect(screen.queryByText('Employee contribution')).not.toBeInTheDocument()
  })

  test('a plain holding shows no manual facts', () => {
    render(<HoldingDrawer holding={holding} displayCurrency="INR" fxRatesToBase={{}}
      onClose={vi.fn()} onEdit={vi.fn()} onHoldingUpdated={vi.fn()} reload={vi.fn()} />)

    expect(screen.queryByText('Manual type')).not.toBeInTheDocument()
  })

  test('the table labels an EPFO holding', () => {
    render(<HoldingsView holdings={[epfoHolding]} categories={[]} reload={vi.fn()} onEdit={vi.fn()} onAdd={vi.fn()} onOpen={vi.fn()} />)

    expect(screen.getByText(/MANUAL · EPFO/)).toBeInTheDocument()
  })
})
