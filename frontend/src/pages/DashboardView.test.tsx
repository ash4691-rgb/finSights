import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { DashboardView } from './DashboardView'
import type { Dashboard } from '../types'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

const dashboard: Dashboard = {
  netWorth: 1000, totalAssets: 1200, totalLiabilities: 200, investedAssets: 1000, portfolioProfitLoss: 200,
  byCategory: [], byBroker: [], byTag: [],
}

function stubApi(returns: unknown) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/api/returns')) return returns === null ? Promise.reject(new Error('down')) : jsonResponse(returns)
    if (url.includes('/api/insights/timeline')) return jsonResponse({ weeks: [], capturedToday: false })
    if (url.includes('/api/layouts')) return jsonResponse({})
    return jsonResponse({})
  }))
}

const view = () => <DashboardView dashboard={dashboard} holdings={[]} onManage={vi.fn()} displayCurrency="INR"
  dataVersion={0} layoutEditing={false} layoutNonce={0} />

describe('DashboardView hero', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('shows the portfolio XIRR next to total gain', async () => {
    stubApi({ currency: 'INR', asOf: '2026-01-01', categories: [], holdings: [],
      portfolio: { xirrPercent: 11.5, netInvested: 1000, currentValue: 1200, gain: 200 } })
    render(view())
    await waitFor(() => expect(screen.getByText(/XIRR \+11\.50% p\.a\./)).toBeInTheDocument())
  })

  test('hides XIRR when it cannot be computed or the call fails', async () => {
    stubApi({ currency: 'INR', asOf: '2026-01-01', categories: [], holdings: [],
      portfolio: { xirrPercent: null, netInvested: 0, currentValue: 0, gain: 0 } })
    render(view())
    await waitFor(() => expect(screen.getByText(/total gain\/loss/)).toBeInTheDocument())
    expect(screen.queryByText(/XIRR/)).not.toBeInTheDocument()
    cleanup()

    stubApi(null)
    render(view())
    await waitFor(() => expect(screen.getByText(/total gain\/loss/)).toBeInTheDocument())
    expect(screen.queryByText(/XIRR/)).not.toBeInTheDocument()
  })
})
