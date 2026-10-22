import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { HoldingsView, HoldingDrawer } from './HoldingsView'
import type { Holding } from '../types'

const holding: Holding = {
  id: 'h1', holdingId: 'HLD-1', categoryId: 'c1', categoryName: 'Equity', name: 'Reliance Industries',
  kind: 'ASSET', valuationMethod: 'MANUAL', currency: 'INR', defaultCurrency: 'INR',
  investedValue: 1000, currentValue: 1200, profitLoss: 200, profitLossPercentage: 20, realisedProfitLoss: 0,
  accruedIncome: 0, liquidWithinSevenDays: false, blocked: false, tags: [],
}

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
