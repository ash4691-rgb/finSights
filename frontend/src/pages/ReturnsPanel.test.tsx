import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { ReturnsPanel, xirrText } from './ReturnsPanel'
import { AnalysisView } from './AnalysisView'
import type { ReturnsReport } from '../types'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

const report: ReturnsReport = {
  currency: 'INR', asOf: '2026-01-01',
  portfolio: { xirrPercent: 12.345, netInvested: 1000, currentValue: 1200, gain: 200, since: '2024-01-01', note: null },
  categories: [{ categoryId: 'c-1', categoryName: 'Equity', figure: { xirrPercent: -4.2, netInvested: 500, currentValue: 480, gain: -20, since: '2024-01-01' } }],
  holdings: [{ holdingId: 'h-1', name: 'Nifty fund', categoryName: 'Equity', figure: { xirrPercent: null, netInvested: 100, currentValue: 100, gain: 0, since: null, note: 'Held under 30 days, too early to annualise.' } }],
}

describe('ReturnsPanel', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('formats XIRR with a sign, or a dash when missing', () => {
    expect(xirrText({ xirrPercent: 5, netInvested: 0, currentValue: 0, gain: 0 })).toBe('+5.00%')
    expect(xirrText({ xirrPercent: -1.234, netInvested: 0, currentValue: 0, gain: 0 })).toBe('-1.23%')
    expect(xirrText({ xirrPercent: 0, netInvested: 0, currentValue: 0, gain: 0 })).toBe('0.00%')
    expect(xirrText({ xirrPercent: null, netInvested: 0, currentValue: 0, gain: 0 })).toBe('—')
  })

  test('shows the portfolio XIRR and per-category / per-holding rows', async () => {
    const fetchMock = vi.fn().mockReturnValue(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)
    render(<ReturnsPanel displayCurrency="INR" dataVersion={0} />)

    await waitFor(() => expect(screen.getByText('+12.35%')).toBeInTheDocument())
    expect(fetchMock.mock.calls[0][0]).toContain('/api/returns?currency=INR')
    expect(screen.getByText('-4.20%')).toHaveClass('negative')
    expect(screen.getByText('Nifty fund')).toBeInTheDocument()
    expect(screen.getByTitle('Held under 30 days, too early to annualise.')).toHaveTextContent('—')
  })

  test('shows the portfolio note and an empty state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse({
      ...report, categories: [], holdings: [],
      portfolio: { xirrPercent: null, netInvested: 0, currentValue: 0, gain: 0, note: 'Not enough transactions to compute a return.' },
    })))
    render(<ReturnsPanel displayCurrency="INR" dataVersion={0} />)

    await waitFor(() => expect(screen.getByText('Not enough transactions to compute a return.')).toBeInTheDocument())
    expect(screen.getByText('No asset holdings yet.')).toBeInTheDocument()
  })

  test('surfaces a load error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve({ message: 'Nope' }), text: () => Promise.resolve('Nope') } as Response)))
    render(<ReturnsPanel displayCurrency="INR" dataVersion={0} />)
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull())
  })

  test('AnalysisView opens on the Returns tab', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse(report)))
    render(<AnalysisView displayCurrency="INR" dataVersion={0} />)
    expect(screen.getByRole('tab', { name: 'Returns' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(screen.getByText('+12.35%')).toBeInTheDocument())
  })
})
