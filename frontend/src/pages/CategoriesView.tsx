import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { money, percent, label, blankCategoryForm, selectableValuationMethods, valuationMethodLabel } from '../util'
import { Field, InfoTip } from '../ui'
import type { Category, Holding, HoldingKind } from '../types'

// ---------------------------------------------------------------------------
// Categories — user-defined buckets (Name + Asset/Liability type). Invested,
// current, P&L, weightage, and liquid/NPA amounts all roll up from mapped
// holdings; a row click lists those holdings.
// ---------------------------------------------------------------------------

export type CategorySortKey = 'name' | 'kind' | 'investedValue' | 'currentValue' | 'profitLoss' | 'weightagePercent' | 'liquidAmount' | 'npaAmount'
export const categoryColumns: [string, CategorySortKey][] = [
  ['Name', 'name'], ['Type', 'kind'], ['Invested', 'investedValue'], ['Current value', 'currentValue'],
  ['P/L', 'profitLoss'], ['Weightage', 'weightagePercent'], ['Liquid', 'liquidAmount'], ['NPA', 'npaAmount'],
]
export function CategoriesView({ categories, onOpen, onEdit, onAdd, reload }: { categories: Category[]; onOpen: (c: Category) => void; onEdit: (c: Category) => void; onAdd: () => void; reload: () => Promise<void> }) {
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<CategorySortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [dragId, setDragId] = useState<string | null>(null)
  const [dragOverId, setDragOverId] = useState<string | null>(null)

  const filtered = useMemo(() => categories.filter(c => c.name.toLowerCase().includes(filter.toLowerCase())), [categories, filter])
  const sorted = useMemo(() => {
    if (!sortKey) return filtered
    const dir = sortDir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => {
      const av = a[sortKey], bv = b[sortKey]
      return typeof av === 'string' && typeof bv === 'string' ? av.localeCompare(bv) * dir : ((av as number) - (bv as number)) * dir
    })
  }, [filtered, sortKey, sortDir])

  // Click cycles ascending → descending → back to the custom drag order (Excel-style tri-state).
  const toggleSort = (key: CategorySortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: CategorySortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const dropOnto = async (targetId: string) => {
    const draggedId = dragId
    setDragId(null); setDragOverId(null)
    if (!draggedId || draggedId === targetId) return
    const ids = categories.map(c => c.id)
    const from = ids.indexOf(draggedId), to = ids.indexOf(targetId)
    if (from === -1 || to === -1) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    await api('/api/categories/reorder', { method: 'PATCH', body: JSON.stringify({ orderedIds: ids }) })
    await reload()
  }

  return <>
    <section className="holdings-toolbar">
      <div className="search"><span>⌕</span><input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Search categories" /></div>
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary push-end" onClick={onAdd}>+ Add category</button>
    </section>
    <section className="table-panel"><table>
      <thead><tr>
        <th className="drag-col" title={sortKey ? 'Clear the column sort to drag-reorder rows' : 'Drag rows to reorder'} />
        {categoryColumns.map(([text, key]) => <th key={key} className="sortable" onClick={() => toggleSort(key)}>{text}{arrow(key)}</th>)}
        <th />
      </tr></thead>
      <tbody>{sorted.length ? sorted.map(c => <tr key={c.id} className={`clickable-row${dragOverId === c.id ? ' drag-over' : ''}`}
          onClick={() => onOpen(c)}
          onDragOver={e => { if (!sortKey) { e.preventDefault(); setDragOverId(c.id) } }}
          onDragLeave={() => setDragOverId(current => current === c.id ? null : current)}
          onDrop={e => { e.preventDefault(); void dropOnto(c.id) }}>
        <td className="drag-col" onClick={e => e.stopPropagation()}>
          {!sortKey && <span className="drag-handle" draggable onDragStart={() => setDragId(c.id)} onDragEnd={() => { setDragId(null); setDragOverId(null) }} title="Drag to reorder">⠿</span>}
        </td>
        <td>
          <div className="name-cell"><strong title={c.name}>{c.name}</strong>{c.description && <InfoTip text={c.description} />}</div>
          <small className="owner">{c.holdingCount} holding{c.holdingCount === 1 ? '' : 's'}</small>
        </td>
        <td><span className={`badge ${c.kind === 'LIABILITY' ? 'liability' : ''}`}>{label(c.kind)}</span></td>
        <td>{money(c.investedValue)}</td>
        <td><strong>{money(c.currentValue)}</strong></td>
        <td>{c.kind === 'LIABILITY' ? <span className="owner">—</span> : <span className={c.profitLoss >= 0 ? 'positive' : 'negative'}>{c.profitLoss >= 0 ? '+' : ''}{money(c.profitLoss)}<small>{percent(c.profitLossPercentage)}</small></span>}</td>
        <td>{c.holdingCount ? percent(c.weightagePercent) : '—'}</td>
        <td>{money(c.liquidAmount)}<small className="owner">{percent(c.liquidPercent)}</small></td>
        <td>{money(c.npaAmount)}<small className="owner">{percent(c.npaPercent)}</small></td>
        <td className="actions"><button className="primary-link" onClick={e => { e.stopPropagation(); onEdit(c) }}>Edit</button></td>
      </tr>) : <tr><td colSpan={10} className="empty"><strong>No categories yet</strong><span>Categories are simple buckets — "Growth Equity", "Emergency Fund" — that holdings get filed under.</span><button className="primary" onClick={onAdd}>Add category</button></td></tr>}</tbody>
    </table></section>
  </>
}

export function CategoryDrawer({ category, holdings, onClose, onEdit, onAddHolding, onOpenHolding, reload }: {
  category: Category; holdings: Holding[]; onClose: () => void; onEdit: (c: Category) => void
  onAddHolding: (c: Category) => void; onOpenHolding: (h: Holding) => void; reload: () => Promise<void>
}) {
  const removeCategory = async () => {
    if (!confirm(`Delete ${category.name} and its ${holdings.length} holding${holdings.length === 1 ? '' : 's'}? This also removes their transactions.`)) return
    await api(`/api/categories/${category.id}`, { method: 'DELETE' }); await reload(); onClose()
  }
  return <div className="modal-backdrop" onClick={onClose}><section className="modal drawer" onClick={e => e.stopPropagation()}>
    <div className="modal-header">
      <div><p className="eyebrow">{label(category.kind)} CATEGORY</p><h2>{category.name}</h2></div>
      <button className="close" onClick={onClose}>×</button>
    </div>
    {category.description && <p className="drawer-description">{category.description}</p>}
    <div className="drawer-metrics">
      <div><p>Current value</p><strong>{money(category.currentValue)}</strong></div>
      <div><p>Invested</p><strong>{money(category.investedValue)}</strong></div>
      <div><p>P/L</p><strong className={category.kind === 'LIABILITY' ? '' : category.profitLoss >= 0 ? 'positive' : 'negative'}>{category.kind === 'LIABILITY' ? '—' : `${category.profitLoss >= 0 ? '+' : ''}${money(category.profitLoss)} · ${percent(category.profitLossPercentage)}`}</strong></div>
    </div>
    <div className="drawer-facts">
      <span>Weightage<b>{category.holdingCount ? percent(category.weightagePercent) : '—'}</b></span>
      <span>Liquid within 7 days<b>{money(category.liquidAmount)} · {percent(category.liquidPercent)}</b></span>
      <span>Blocked / NPA<b>{money(category.npaAmount)} · {percent(category.npaPercent)}</b></span>
    </div>

    <div className="panel-heading"><h3>Mapped holdings</h3><span>{holdings.length} position{holdings.length === 1 ? '' : 's'}</span></div>
    {holdings.length ? <div className="mapped-holdings">{holdings.map(h => <button key={h.id} className="mapped-holding" onClick={() => onOpenHolding(h)}>
      <div className="mapped-holding-name"><strong title={h.name}>{h.name}</strong><small>{h.broker || 'Unassigned broker'}{h.quantity != null ? ` · qty ${h.quantity}` : ''}</small></div>
      <div className="mapped-holding-value"><span>{money(h.currentValue, h.currency)}</span><small className={h.profitLoss >= 0 ? 'positive' : 'negative'}>{h.kind === 'LIABILITY' ? '—' : `${h.profitLoss >= 0 ? '+' : ''}${percent(h.profitLossPercentage)}`}</small></div>
    </button>)}</div> : <p className="hint">No holdings mapped yet — add one to record an actual position in this category.</p>}
    <button className="outline compact" onClick={() => onAddHolding(category)}>+ Add a holding here</button>

    <div className="modal-actions"><button className="danger-link-btn" onClick={() => void removeCategory()}>Delete category</button><button className="outline" onClick={onClose}>Close</button><button className="primary" onClick={() => onEdit(category)}>Edit category</button></div>
  </section></div>
}

export function CategoryModal({ category, holdings, onClose, onSaved }: { category: Category | null; holdings: Holding[]; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(() => category
    ? { name: category.name, kind: category.kind, description: category.description || '', allowedValuationMethods: category.allowedValuationMethods ?? [] }
    : blankCategoryForm())
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))

  // Holdings already filed under this category whose current valuation method would fall
  // outside the selection being saved — saving the restriction now would strand them.
  const nonCompliant = useMemo(() => {
    if (!category || form.kind !== 'ASSET' || !form.allowedValuationMethods.length) return []
    return holdings.filter(h => h.categoryId === category.id && !form.allowedValuationMethods.includes(h.valuationMethod))
  }, [category, holdings, form.kind, form.allowedValuationMethods])

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    try {
      await api(category ? `/api/categories/${category.id}` : '/api/categories', { method: category ? 'PUT' : 'POST', body: JSON.stringify(form) })
      onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save category') } finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section className="modal narrow"><div className="modal-header"><div><p className="eyebrow">{category ? 'EDIT CATEGORY' : 'NEW CATEGORY'}</p><h2>{category ? category.name : 'Add a category'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Name" required wide><input required maxLength={128} value={form.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Growth Equity, Emergency Fund" /></Field>
        <Field label="Type" required wide><select value={form.kind} onChange={e => set('kind', e.target.value)}><option value="ASSET">Asset</option><option value="LIABILITY">Liability</option></select></Field>
        {form.kind === 'ASSET' && <Field label="Allowed valuation methods" wide>
          <div className="check-row">
            {selectableValuationMethods.map(m => <label key={m}>
              <input type="checkbox" checked={form.allowedValuationMethods.includes(m)}
                onChange={e => setForm(current => ({ ...current, allowedValuationMethods: e.target.checked
                  ? [...current.allowedValuationMethods, m]
                  : current.allowedValuationMethods.filter(x => x !== m) }))} /> {valuationMethodLabel(m)}
            </label>)}
          </div>
          <p className="hint">Leave all unchecked to allow every method. Checked methods are enforced on holdings filed under this category.</p>
        </Field>}
        <Field label="Description" wide><input value={form.description} onChange={e => set('description', e.target.value)} placeholder="One line — shows in the ⓘ tooltip on the Categories table" maxLength={1024} /></Field>
      </div>
      {nonCompliant.length > 0 && <div className="form-callout warn"><span className="form-callout-dot">!</span>
        <span><b>These changes won't be saved</b> — {nonCompliant.length} holding{nonCompliant.length === 1 ? '' : 's'} in this category {nonCompliant.length === 1 ? 'uses' : 'use'} a valuation method outside your selection:
          <ul className="warn-holding-list">{nonCompliant.map(h => <li key={h.id}><span title={`Not compliant — currently ${valuationMethodLabel(h.valuationMethod)}`}>⚠</span> {h.name} <small>({valuationMethodLabel(h.valuationMethod)})</small></li>)}</ul>
          Update or move these holdings first, or widen your selection.</span>
      </div>}
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || nonCompliant.length > 0}>{saving ? 'Saving…' : category ? 'Save changes' : 'Add category'}</button></div>
    </form>
  </section></div>
}
