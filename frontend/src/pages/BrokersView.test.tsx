import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { BrokersView } from './BrokersView'
import type { Brokers } from '../types'

const emptyBrokers: Brokers = { brokers: [], sources: [] }

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

describe('BrokersView — manual upload entry point', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(jsonResponse(emptyBrokers)))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    cleanup()
  })

  test('shows a single "Upload a statement" button, enabled once categories exist', async () => {
    render(<BrokersView displayCurrency="INR" dataVersion={0} hasCategories onUploadStatement={vi.fn()} />)

    await waitFor(() => expect(screen.getByText('Upload a statement')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /Upload a statement/ })).toBeEnabled()
    // The old three-card grid is gone.
    expect(screen.queryByText('CAS statement')).not.toBeInTheDocument()
    expect(screen.queryByText('Holding statement')).not.toBeInTheDocument()
  })

  test('disables the upload button until a category exists', async () => {
    render(<BrokersView displayCurrency="INR" dataVersion={0} hasCategories={false} onUploadStatement={vi.fn()} />)

    await waitFor(() => expect(screen.getByText('Upload a statement')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /Upload a statement/ })).toBeDisabled()
  })
})
