import { useEffect, useState } from 'react'
import { api } from '../api'
import { money } from '../util'
import { InfoTip } from '../ui'
import type { Allocation, AllocationRow } from '../types'

const DRIFT_TOLERANCE = 5   // percentage points before a category is flagged

export function moveText(row: AllocationRow, currency: string): string {
  if (row.amountToTarget == null) return '—'
  if (Math.abs(row.amountToTarget) < 1) return 'On target'
  const verb = row.amountToTarget > 0 ? 'Add' : 'Trim'
  return `${verb} ${money(Math.abs(row.amountToTarget), currency)}`
}

function driftClass(drift: number | null): string {
  if (drift == null || Math.abs(drift) < DRIFT_TOLERANCE) return ''
  return 'negative'
}

const draftOf = (allocation: Allocation) =>
  Object.fromEntries(allocation.rows.map(r => [r.categoryId, r.targetPercent == null ? '' : String(r.targetPercent)]))

export function AllocationPanel({ displayCurrency, dataVersion }: { displayCurrency: string; dataVersion: number }) {
  const [allocation, setAllocation] = useState<Allocation | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api<Allocation>(`/api/allocation?currency=${displayCurrency}`)
      .then(a => { setAllocation(a); setDraft(draftOf(a)) })
      .catch(e => setError(e instanceof Error ? e.message : 'Could not load allocation'))
  }, [displayCurrency, dataVersion])

  if (!allocation) return error ? <p className="form-error">{error}</p> : <p className="hint">Loading allocation…</p>
  const draftSum = Object.values(draft).reduce((sum, v) => sum + (v === '' ? 0 : Number(v)), 0)
  const dirty = allocation.rows.some(r => (draft[r.categoryId] ?? '') !== (r.targetPercent == null ? '' : String(r.targetPercent)))

  const save = async () => {
    setSaving(true); setError('')
    const targets = Object.fromEntries(Object.entries(draft).map(([id, v]) => [id, v === '' ? null : Number(v)]))
    try {
      const saved = await api<Allocation>(`/api/allocation/targets?currency=${displayCurrency}`, { method: 'PUT', body: JSON.stringify({ targets }) })
      setAllocation(saved); setDraft(draftOf(saved))
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save targets') }
    finally { setSaving(false) }
  }

  return <section className="panel">
    <div className="panel-heading">
      <h3>Target allocation <InfoTip text="Set the share of your assets you want in each category. FinSights shows how far you've drifted and how much would bring each back to target. It's information, not advice: nothing is bought or sold." /></h3>
      <span>{money(allocation.totalAssets, allocation.currency)} in assets</span>
    </div>
    {allocation.rows.length === 0 ? <p className="hint">Add an asset category first.</p> : <>
      <div className="table-panel"><table>
        <thead><tr><th>Category</th><th>Value</th><th>Now</th><th>Target %</th><th>Drift</th><th>To get back on target</th></tr></thead>
        <tbody>{allocation.rows.map(r => <tr key={r.categoryId}>
          <td>{r.name}</td><td>{money(r.currentValue, allocation.currency)}</td><td>{r.currentPercent.toFixed(1)}%</td>
          <td><input type="number" min="0" max="100" step="0.5" className="target-input" aria-label={`Target for ${r.name}`}
            value={draft[r.categoryId] ?? ''} placeholder="—" onChange={e => setDraft(d => ({ ...d, [r.categoryId]: e.target.value }))} /></td>
          <td className={driftClass(r.driftPercent)}>{r.driftPercent == null ? '—' : `${r.driftPercent > 0 ? '+' : ''}${r.driftPercent.toFixed(1)} pts`}</td>
          <td>{moveText(r, allocation.currency)}</td>
        </tr>)}</tbody>
      </table></div>
      <div className="allocation-footer">
        <span className={draftSum > 100 ? 'negative' : ''}>Targets add up to {draftSum.toFixed(1)}%{draftSum !== 100 && ' (aim for 100%)'}</span>
        <button className="primary" onClick={() => void save()} disabled={saving || !dirty || draftSum > 100}>{saving ? 'Saving…' : 'Save targets'}</button>
      </div>
    </>}
    {error && <p className="form-error">{error}</p>}
  </section>
}
