import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { InsightsView } from './InsightsView'
import type { ActionItem, Dashboard, Settings } from '../types'

const contribution: ActionItem = {
  kind: 'PROVIDENT_FUND_CONTRIBUTION', severity: 'INFO', title: 'Confirm EPF contribution', detail: 'January 2026 · ₹3,600',
  holdingId: 'h-epf', period: '2026-01', key: 'PROVIDENT_FUND_CONTRIBUTION|h-epf|2026-01',
}

const review: ActionItem = {
  kind: 'MANUAL_VALUATION_REVIEW', severity: 'WARN', title: 'Review valuation — Flat', detail: 'Consider updating its balance or valuation by 2026-10-31.',
  holdingId: 'h-flat', period: '2026', key: 'MANUAL_VALUATION_REVIEW|h-flat|2026',
}

describe('InsightsView — provident fund contribution task', () => {
  const calls: { url: string; method?: string }[] = []

  beforeEach(() => {
    calls.length = 0
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method })
      const body = url.includes('/insights/actions') ? [contribution, review] : []
      return Promise.resolve({ ok: true, status: url.includes('/confirm') ? 204 : 200, json: () => Promise.resolve(body) } as Response)
    }))
  })
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  const renderView = (reload = vi.fn().mockResolvedValue(undefined)) => {
    render(<InsightsView displayCurrency="INR" dataVersion={0} settings={{} as Settings} dashboard={{} as Dashboard} reload={reload} onOpen={vi.fn()} />)
    return reload
  }

  test('confirming the task posts the month and reloads', async () => {
    const reload = renderView()

    fireEvent.click(await screen.findByRole('button', { name: 'Confirm contribution' }))

    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
    expect(calls).toContainEqual({ url: 'http://localhost:8080/api/provident-fund/h-epf/contributions/confirm?month=2026-01', method: 'POST' })
  })

  test('the yearly valuation review is listed as a task without a confirm button of its own', async () => {
    renderView()

    expect(await screen.findByText('Review valuation — Flat')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Confirm contribution' })).toHaveLength(1)
  })
})
