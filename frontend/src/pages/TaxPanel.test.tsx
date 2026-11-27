import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent, within } from '@testing-library/react'
import { TaxPanel, realisedCsvRows } from './TaxPanel'
import { AnalysisView } from './AnalysisView'
import type { TaxReport } from '../types'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
const failure = () => Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve({ message: 'Nope' }), text: () => Promise.resolve('Nope') } as Response)

const summary = {
  equityShortTerm: 5000, equityLongTerm: 200000, otherShortTerm: 0, otherLongTerm: -1000, debtSlabGains: 0,
  virtualDigitalAssetGains: 0, interestIncome: 1000, equityShortTermTax: 1000, equityLongTermTax: 9375,
  otherLongTermTax: 0, virtualDigitalAssetTax: 0, estimatedTax: 10375, equityLongTermExemption: 125000, equityLongTermExemptionLeft: 0,
}
const report: TaxReport = {
  financialYear: '2025-26', availableYears: ['2025-26', '2024-25'], asOf: '2025-11-26', summary,
  realised: [{ holdingId: 'A', name: 'Infosys', taxClass: 'LISTED_EQUITY', bought: '2024-01-10', sold: '2025-06-01', units: 100, cost: 100000, proceeds: 300000, gain: 200000, term: 'LONG' }],
  unrealised: [{ holdingId: 'H', name: 'HDFC Bank', taxClass: 'LISTED_EQUITY', shortTermGain: 1000, longTermGain: -50, longTermFrom: '2026-01-02' },
    { holdingId: 'Z', name: 'Zero', taxClass: 'OTHER_CAPITAL', shortTermGain: 0, longTermGain: 0, longTermFrom: null }],
  interest: [{ holdingId: 'F', name: 'SBI FD', received: 700, accrued: 300 }],
  holdings: [{ holdingId: 'A', name: 'Infosys', categoryName: 'Stocks', taxClass: 'LISTED_EQUITY', inferred: true },
    { holdingId: 'G', name: 'Gold', categoryName: 'Commodities', taxClass: 'DEBT_SLAB', inferred: false }],
  notes: ['An estimate to plan with.'],
}

describe('TaxPanel', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('shows the summary, realised, unrealised, interest and notes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse(report)))
    render(<TaxPanel dataVersion={0} />)

    await waitFor(() => expect(screen.getByText('Estimated tax on gains')).toBeInTheDocument())
    expect(screen.getAllByText('Infosys', { selector: 'td' })).toHaveLength(2)
    expect(screen.getByText('HDFC Bank')).toBeInTheDocument()
    expect(screen.getByText('SBI FD')).toBeInTheDocument()
    expect(screen.getByText('An estimate to plan with.')).toBeInTheDocument()
    expect(screen.getByLabelText('Tax treatment for Infosys')).toHaveValue('')
    expect(screen.getByLabelText('Tax treatment for Gold')).toHaveValue('DEBT_SLAB')
  })

  test('changing the year refetches and an override saves through the API', async () => {
    const fetchMock = vi.fn().mockReturnValue(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)
    render(<TaxPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByLabelText('Financial year')).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText('Financial year'), { target: { value: '2024-25' } })
    await waitFor(() => expect(fetchMock.mock.calls.some(c => String(c[0]).endsWith('/api/tax?fy=2024-25'))).toBe(true))

    fireEvent.change(screen.getByLabelText('Tax treatment for Infosys'), { target: { value: 'OTHER_CAPITAL' } })
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(c => c[1]?.method === 'PUT')
      expect(put?.[0]).toContain('/api/tax/holdings/A/class?fy=2024-25')
      expect(put?.[1]?.body).toBe(JSON.stringify({ taxClass: 'OTHER_CAPITAL' }))
    })

    fireEvent.change(screen.getByLabelText('Tax treatment for Gold'), { target: { value: '' } })
    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[1]?.method === 'PUT').at(-1)?.[1]?.body).toBe(JSON.stringify({ taxClass: null })))
  })

  test('a failed override shows an error but keeps the report', async () => {
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => init?.method === 'PUT' ? failure() : jsonResponse(report)))
    render(<TaxPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByLabelText('Tax treatment for Infosys')).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText('Tax treatment for Infosys'), { target: { value: 'EXEMPT' } })
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull())
    expect(screen.getByText('Estimated tax on gains')).toBeInTheDocument()
  })

  test('empty year and load failure states', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse({ ...report, realised: [], unrealised: [], interest: [] })))
    render(<TaxPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByText('No sales in FY 2025-26.')).toBeInTheDocument())
    expect(screen.getByText('Nothing open with a cost basis.')).toBeInTheDocument()
    expect(screen.queryByText('Interest income', { selector: 'h3' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Export gains/ })).toBeDisabled()
    cleanup()

    vi.stubGlobal('fetch', vi.fn().mockReturnValue(failure()))
    render(<TaxPanel dataVersion={0} />)
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull())
  })

  test('CSV rows use readable labels', () => {
    expect(realisedCsvRows(report)).toEqual([['Infosys', 'Listed equity / equity MF', 'Long-term', '2024-01-10', '2025-06-01', 100, 100000, 300000, 200000]])
  })

  test('export downloads a CSV', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse(report)))
    const createObjectURL = vi.fn().mockReturnValue('blob:x')
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
    render(<TaxPanel dataVersion={0} />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Export gains/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /Export gains/ }))
    expect(createObjectURL).toHaveBeenCalled()
  })

  test('AnalysisView switches to the Tax tab', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => jsonResponse(url.includes('/api/tax') ? report : {
      currency: 'INR', asOf: '2026-01-01', categories: [], holdings: [], portfolio: { xirrPercent: null, netInvested: 0, currentValue: 0, gain: 0 } })))
    render(<AnalysisView displayCurrency="INR" dataVersion={0} />)
    fireEvent.click(screen.getByRole('tab', { name: 'Tax (India)' }))
    await waitFor(() => expect(screen.getByText('Estimated tax on gains')).toBeInTheDocument())
    expect(within(screen.getByRole('tablist')).getByRole('tab', { name: 'Tax (India)' })).toHaveAttribute('aria-selected', 'true')
  })
})
