import { useState } from 'react'
import { api } from '../api'

type DigestEmail = { subject: string; body: string }

// Shows exactly what Monday's digest email would say for this account, without sending it.
export function DigestPreview() {
  const [email, setEmail] = useState<DigestEmail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const load = async () => {
    setLoading(true); setError('')
    try { setEmail(await api<DigestEmail>('/api/notifications/digest/preview')) }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not build the preview') }
    finally { setLoading(false) }
  }
  return <div className="digest-preview">
    <button type="button" className="outline compact" onClick={() => void load()} disabled={loading}>
      {loading ? 'Building preview…' : email ? 'Refresh preview' : 'Preview weekly digest'}
    </button>
    {error && <p className="form-error">{error}</p>}
    {email && <div className="table-panel digest-preview-body">
      <strong>{email.subject}</strong>
      <pre>{email.body}</pre>
    </div>}
  </div>
}
