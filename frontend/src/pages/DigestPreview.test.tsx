import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { DigestPreview } from './DigestPreview'

const jsonResponse = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

describe('DigestPreview', () => {
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  test('loads and shows the digest on request', async () => {
    const fetchMock = vi.fn().mockReturnValue(jsonResponse({ subject: 'Your FinSights week', body: 'Net worth: ₹900' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<DigestPreview />)
    expect(fetchMock).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Preview weekly digest' }))

    await waitFor(() => expect(screen.getByText('Your FinSights week')).toBeInTheDocument())
    expect(screen.getByText('Net worth: ₹900')).toBeInTheDocument()
    expect(fetchMock.mock.calls[0][0]).toContain('/api/notifications/digest/preview')
    expect(screen.getByRole('button', { name: 'Refresh preview' })).toBeEnabled()
  })

  test('shows an error when the preview fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve({ message: 'Nope' }), text: () => Promise.resolve('Nope') } as Response)))
    render(<DigestPreview />)
    fireEvent.click(screen.getByRole('button', { name: 'Preview weekly digest' }))
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull())
  })
})
