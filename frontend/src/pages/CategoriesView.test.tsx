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

// Real estate is a property of the category, not the holding kind — the checkbox marks a
// category so every holding filed under it takes an area + unit instead of a quantity.
describe('CategoryModal — real estate flag', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('new asset category defaults the real-estate checkbox to unchecked', () => {
    render(<CategoryModal category={null} holdings={[]} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByLabelText(/Holdings in this category are land\/property/)).not.toBeChecked()
  })

  test('editing a real-estate category pre-checks the checkbox', () => {
    render(<CategoryModal category={{ ...category, realEstate: true }} holdings={[]} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByLabelText(/Holdings in this category are land\/property/)).toBeChecked()
  })

  test('checking real estate and saving posts realEstate: true', async () => {
    let posted: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      posted = JSON.parse(init!.body as string)
      return okResponse({ ...category, realEstate: true })
    }))
    const onSaved = vi.fn()

    render(<CategoryModal category={category} holdings={[]} onClose={vi.fn()} onSaved={onSaved} />)
    fireEvent.click(screen.getByLabelText(/Holdings in this category are land\/property/))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(posted).toMatchObject({ realEstate: true })
  })

  test('the real-estate checkbox is hidden for a liability category', () => {
    render(<CategoryModal category={{ ...category, kind: 'LIABILITY' }} holdings={[]} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.queryByLabelText(/Holdings in this category are land\/property/)).not.toBeInTheDocument()
  })
})
