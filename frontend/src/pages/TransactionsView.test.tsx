import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { TransactionsView } from './TransactionsView'
import type { Transaction } from '../types'

const transaction: Transaction = {
  id: 't1', holdingId: 'h1', holdingName: 'Reliance Industries', categoryId: 'c1', categoryName: 'Equity',
  currency: 'INR', type: 'BUY', date: '2026-01-01', amount: 1000, quantity: 10,
}

const okResponse = (body: unknown = {}, status = 200) =>
  Promise.resolve({ ok: true, status, json: () => Promise.resolve(body) } as Response)
const errorResponse = (message: string, status = 500) =>
  Promise.resolve({ ok: false, status, json: () => Promise.resolve({ message }) } as Response)

describe('TransactionsView — delete', () => {
  let confirmSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    confirmSpy.mockRestore()
    cleanup()
  })

  const renderView = (fetchMock: ReturnType<typeof vi.fn>, reload = vi.fn().mockResolvedValue(undefined)) => {
    vi.stubGlobal('fetch', fetchMock)
    render(<TransactionsView holdings={[]} displayCurrency="INR" fxRatesToBase={{}} dataVersion={0} reload={reload} />)
    return { reload }
  }

  test('shows "Deleting…" while in flight, disables the row, then reloads on success', async () => {
    let resolveDelete!: (value: Response) => void
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'DELETE') return new Promise<Response>(resolve => { resolveDelete = resolve })
      return okResponse([transaction])
    })
    const { reload } = renderView(fetchMock)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(confirmSpy).toHaveBeenCalledWith('Delete this BUY transaction?')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Deleting…' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled()

    resolveDelete(await okResponse(undefined, 204))
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
  })

  test('shows an error and re-enables the row if the delete fails', async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'DELETE') return errorResponse('Could not delete transaction')
      return okResponse([transaction])
    })
    const { reload } = renderView(fetchMock)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.getByText('Could not delete transaction')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled()
    expect(reload).not.toHaveBeenCalled()
  })
})
