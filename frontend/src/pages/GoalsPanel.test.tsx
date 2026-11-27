import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { GoalsPanel, requestOf } from './GoalsPanel'
import { AnalysisView } from './AnalysisView'
import type { Goal } from '../types'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
const failure = () => Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve({ message: 'Bad goal' }), text: () => Promise.resolve('Bad goal') } as Response)
const goal: Goal = { id: 'g-1', name: 'House', currency: 'INR', targetAmount: 200000, targetDate: '2027-01-01', monthlyContribution: 1000,
  expectedReturnPercent: 10, categoryIds: ['eq', 'gone'], currentValue: 60000, progressPercent: 30, monthsLeft: 12,
  projectedValue: 80000, onTrack: false, requiredMonthly: 9500 }
const categories = [{ id: 'eq', name: 'Equity', kind: 'ASSET' }, { id: 'ln', name: 'Loans', kind: 'LIABILITY' }]

function stub(goals: Goal[], write: () => Promise<Response> = () => jsonResponse(goal)) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method && init.method !== 'GET') return write()
    if (url.includes('/api/categories')) return jsonResponse(categories)
    return jsonResponse(goals)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('GoalsPanel', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

  test('builds the request with optional fields as null', () => {
    expect(requestOf({ name: 'A', targetAmount: '100', targetDate: '2030-01-01', monthlyContribution: '', expectedReturnPercent: '', categoryIds: [] }))
      .toEqual({ name: 'A', targetAmount: 100, targetDate: '2030-01-01', monthlyContribution: null, expectedReturnPercent: null, categoryIds: [] })
  })

  test('shows each goal with status, progress and linked categories', async () => {
    stub([goal, { ...goal, id: 'g-2', name: 'Buffer', onTrack: true, categoryIds: [] }])
    render(<GoalsPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByText('House')).toBeInTheDocument())
    expect(screen.getByText(/Needs ₹9,500\/month/)).toHaveClass('negative')
    expect(screen.getByText('On track')).toHaveClass('positive')
    expect(screen.getByText(/Equity, Deleted category/)).toBeInTheDocument()
    expect(screen.getByText(/^All assets/)).toBeInTheDocument()
  })

  test('adds a goal linked to a ticked asset category', async () => {
    const fetchMock = stub([])
    render(<GoalsPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByText(/No goals yet/)).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '+ Add goal' }))
    expect(screen.queryByLabelText(/Loans/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('e.g. House down payment'), { target: { value: 'Car' } })
    const [amount, monthly] = screen.getAllByRole('spinbutton')
    fireEvent.change(amount, { target: { value: '500000' } })
    fireEvent.change(monthly, { target: { value: '5000' } })
    fireEvent.change(document.querySelector('input[type="date"]')!, { target: { value: '2028-06-01' } })
    fireEvent.click(screen.getByLabelText(/Equity/))
    fireEvent.click(screen.getByLabelText(/Equity/))
    fireEvent.click(screen.getByLabelText(/Equity/))
    fireEvent.click(screen.getByRole('button', { name: 'Add goal' }))

    await waitFor(() => {
      const post = fetchMock.mock.calls.find(c => c[1]?.method === 'POST')
      expect(post?.[0]).toContain('/api/goals')
      expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ name: 'Car', targetAmount: 500000, monthlyContribution: 5000, expectedReturnPercent: 10, categoryIds: ['eq'] })
    })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Add goal' })).not.toBeInTheDocument())
  })

  test('edits, cancels, deletes after confirming and shows write errors', async () => {
    const fetchMock = stub([goal], failure)
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
    render(<GoalsPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByText('House')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.getByDisplayValue('House')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save goal' }))
    await waitFor(() => expect(screen.getByText('Bad goal')).toBeInTheDocument())
    expect(fetchMock.mock.calls.some(c => c[1]?.method === 'PUT' && String(c[0]).endsWith('/api/goals/g-1'))).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Save goal' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(fetchMock.mock.calls.some(c => c[1]?.method === 'DELETE')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(c => c[1]?.method === 'DELETE')).toBe(true))
    await waitFor(() => expect(screen.getByText('Bad goal')).toBeInTheDocument())
  })

  test('a failed load shows an error and an empty list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(failure()))
    render(<GoalsPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByText('Bad goal')).toBeInTheDocument())
    expect(screen.getByText(/No goals yet/)).toBeInTheDocument()
  })

  test('AnalysisView shows allocation and goals together', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/api/allocation')) return jsonResponse({ currency: 'INR', totalAssets: 0, targetSum: 0, complete: false, rows: [] })
      if (url.includes('/api/goals')) return jsonResponse([])
      if (url.includes('/api/categories')) return jsonResponse([])
      return jsonResponse({ currency: 'INR', asOf: '2026-01-01', categories: [], holdings: [], portfolio: { xirrPercent: null, netInvested: 0, currentValue: 0, gain: 0 } })
    }))
    render(<AnalysisView displayCurrency="INR" dataVersion={0} />)
    fireEvent.click(screen.getByRole('tab', { name: 'Allocation & goals' }))
    await waitFor(() => expect(screen.getByText('Add an asset category first.')).toBeInTheDocument())
    expect(screen.getByText(/No goals yet/)).toBeInTheDocument()
  })
})
