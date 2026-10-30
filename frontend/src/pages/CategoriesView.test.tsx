import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { CategoryModal } from './CategoriesView'
import type { Category } from '../types'

const okResponse = (body: unknown = {}, status = 200) =>
  Promise.resolve({ ok: true, status, json: () => Promise.resolve(body) } as Response)

const category: Category = {
  id: 'c-1', name: 'Growth Equity', kind: 'ASSET', realEstate: false,
  investedValue: 0, currentValue: 0, profitLoss: 0, profitLossPercentage: 0,
  weightagePercent: 0, liquidAmount: 0, liquidPercent: 0, npaAmount: 0, npaPercent: 0, holdingCount: 0,
}

// Real estate is now a manual type chosen on the holding form, so the category form no longer
// exposes the flag — but a legacy flagged category must keep it when saved.
describe('CategoryModal — real estate flag', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('new asset category does not show a real-estate checkbox', () => {
    render(<CategoryModal category={null} holdings={[]} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.queryByLabelText(/Holdings in this category are land\/property/)).not.toBeInTheDocument()
  })

  test('editing a real-estate category does not show the checkbox', () => {
    render(<CategoryModal category={{ ...category, realEstate: true }} holdings={[]} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.queryByLabelText(/Holdings in this category are land\/property/)).not.toBeInTheDocument()
  })

  test('saving a legacy real-estate category preserves realEstate: true', async () => {
    let posted: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      posted = JSON.parse(init!.body as string)
      return okResponse({ ...category, realEstate: true })
    }))
    const onSaved = vi.fn()

    render(<CategoryModal category={{ ...category, realEstate: true }} holdings={[]} onClose={vi.fn()} onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({ realEstate: true })
  })

  test('saving a new category posts realEstate: false', async () => {
    let posted: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      posted = JSON.parse(init!.body as string)
      return okResponse(category)
    }))
    const onSaved = vi.fn()

    render(<CategoryModal category={null} holdings={[]} onClose={vi.fn()} onSaved={onSaved} />)
    fireEvent.change(screen.getByPlaceholderText(/Growth Equity/), { target: { value: 'Gold' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({ name: 'Gold', realEstate: false })
  })
})
