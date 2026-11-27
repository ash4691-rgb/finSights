import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { AllocationPanel, moveText } from './AllocationPanel'
import type { Allocation, AllocationRow } from '../types'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
const failure = () => Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve({ message: 'Targets add up to more than 100%' }), text: () => Promise.resolve('Targets add up to more than 100%') } as Response)
const row = (over: Partial<AllocationRow>): AllocationRow => ({ categoryId: 'eq', name: 'Equity', currentValue: 800, currentPercent: 80, targetPercent: 60, driftPercent: 20, amountToTarget: -200, ...over })
const allocation: Allocation = { currency: 'INR', totalAssets: 1000, targetSum: 60, complete: false, rows: [
  row({}), row({ categoryId: 'gd', name: 'Gold', currentValue: 200, currentPercent: 20, targetPercent: null, driftPercent: null, amountToTarget: null }),
] }

describe('AllocationPanel', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('describes what to move', () => {
    expect(moveText(row({}), 'INR')).toMatch(/^Trim ₹200$/)
    expect(moveText(row({ amountToTarget: 150 }), 'INR')).toMatch(/^Add ₹150$/)
    expect(moveText(row({ amountToTarget: 0.4 }), 'INR')).toBe('On target')
    expect(moveText(row({ amountToTarget: null }), 'INR')).toBe('—')
  })

  test('shows drift, saves edited targets and blocks a sum over 100', async () => {
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => jsonResponse(init?.method === 'PUT'
      ? { ...allocation, rows: [row({ targetPercent: 70 }), allocation.rows[1]] } : allocation))
    vi.stubGlobal('fetch', fetchMock)
    render(<AllocationPanel displayCurrency="INR" dataVersion={0} />)

    await waitFor(() => expect(screen.getByText('+20.0 pts')).toHaveClass('negative'))
    const save = screen.getByRole('button', { name: 'Save targets' })
    expect(save).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Target for Gold'), { target: { value: '50' } })
    expect(screen.getByText(/Targets add up to 110.0%/)).toHaveClass('negative')
    expect(save).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Target for Gold'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Target for Equity'), { target: { value: '70' } })
    expect(screen.getByText('Targets add up to 100.0%')).toBeInTheDocument()
    fireEvent.click(save)

    await waitFor(() => {
      const put = fetchMock.mock.calls.find(c => c[1]?.method === 'PUT')
      expect(put?.[0]).toContain('/api/allocation/targets?currency=INR')
      expect(JSON.parse(String(put?.[1]?.body))).toEqual({ targets: { eq: 70, gd: 30 } })
    })
  })

  test('clearing a target sends null and a save error is shown', async () => {
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => init?.method === 'PUT' ? failure() : jsonResponse(allocation)))
    render(<AllocationPanel displayCurrency="INR" dataVersion={0} />)
    await waitFor(() => expect(screen.getByLabelText('Target for Equity')).toHaveValue(60))
    fireEvent.change(screen.getByLabelText('Target for Equity'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save targets' }))
    await waitFor(() => expect(screen.getByText('Targets add up to more than 100%')).toBeInTheDocument())
  })

  test('empty and failed loads', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse({ ...allocation, rows: [] })))
    render(<AllocationPanel displayCurrency="INR" dataVersion={0} />)
    await waitFor(() => expect(screen.getByText('Add an asset category first.')).toBeInTheDocument())
    cleanup()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(failure()))
    render(<AllocationPanel displayCurrency="INR" dataVersion={0} />)
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull())
  })
})
