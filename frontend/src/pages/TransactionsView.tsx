import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { api, API_URL } from '../api'
import { money, rate, label, since, numeric, shortId, transactionTypes, assetTxnTypes, liabilityTxnTypes } from '../util'
import { Field, InfoTip } from '../ui'
import type { Transaction, Holding, TransactionType, ImportResult } from '../types'

// ---------------------------------------------------------------------------
// Transactions — the only page with bulk CSV/XML import; each row is linked
// to an existing holdingId.
// ---------------------------------------------------------------------------

export type TransactionSortKey = 'date' | 'type' | 'holdingName' | 'broker' | 'amount' | 'quantity'

export function TransactionsView({ holdings, displayCurrency, dataVersion, reload }: { holdings: Holding[]; displayCurrency: string; dataVersion: number; reload: () => Promise<void> }) {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [holdingId, setHoldingId] = useState('')
  const [type, setType] = useState('')
  const [broker, setBroker] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [sortKey, setSortKey] = useState<TransactionSortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const brokers = useMemo(() => [...new Set(holdings.map(h => h.broker).filter((b): b is string => !!b))].sort(), [holdings])

  const load = async () => {
    setLoading(true); setError('')
    const params = new URLSearchParams({ currency: displayCurrency })
    if (holdingId) params.set('holdingId', holdingId)
    if (type) params.set('type', type)
    if (broker) params.set('broker', broker)
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    try { setTransactions(await api<Transaction[]>(`/api/transactions?${params}`)) }
    catch (err) { setError(err instanceof Error ? err.message : 'Unable to load transactions') }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [displayCurrency, dataVersion, holdingId, type, broker, from, to])

  const sorted = useMemo(() => {
    if (!sortKey) return transactions
    const dir = sortDir === 'asc' ? 1 : -1
    return [...transactions].sort((a, b) => {
      const av = a[sortKey], bv = b[sortKey]
      return typeof av === 'number' || typeof bv === 'number'
        ? ((av as number ?? -Infinity) - (bv as number ?? -Infinity)) * dir
        : String(av ?? '').localeCompare(String(bv ?? '')) * dir
    })
  }, [transactions, sortKey, sortDir])

  // Click cycles ascending → descending → back to the default (server-returned) order.
  const toggleSort = (key: TransactionSortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: TransactionSortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const remove = async (t: Transaction) => { if (confirm(`Delete this ${label(t.type)} transaction?`)) { await api(`/api/transactions/${t.id}`, { method: 'DELETE' }); await reload() } }

  return <>
    <section className="holdings-toolbar">
      <HoldingPicker className="holding-filter" holdings={holdings} value={holdingId} onChange={setHoldingId} allowClear />
      <select className="filter-select" value={type} onChange={e => setType(e.target.value)}><option value="">All types</option>{transactionTypes.map(t => <option key={t} value={t}>{label(t)}</option>)}</select>
      <select className="filter-select" value={broker} onChange={e => setBroker(e.target.value)}><option value="">All brokers</option>{brokers.map(b => <option key={b} value={b}>{b}</option>)}</select>
      <input className="filter-select date-filter" type="date" value={from} onChange={e => setFrom(e.target.value)} title="From date" />
      <input className="filter-select date-filter" type="date" value={to} onChange={e => setTo(e.target.value)} title="To date" />
      {(holdingId || type || broker || from || to) && <button className="outline compact" onClick={() => { setHoldingId(''); setType(''); setBroker(''); setFrom(''); setTo('') }}>Clear filters</button>}
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary compact push-end" onClick={() => setCreating(true)} disabled={!holdings.length} title={holdings.length ? '' : 'Add a holding first'}>+ Log transaction</button>
    </section>
    {error && <p className="hint">{error}</p>}
    <section className="table-panel"><table>
      <thead><tr>
        <th className="sortable" onClick={() => toggleSort('date')}>Date{arrow('date')}</th>
        <th className="sortable" onClick={() => toggleSort('type')}>Type{arrow('type')}</th>
        <th className="sortable" onClick={() => toggleSort('holdingName')}>Holding{arrow('holdingName')}</th>
        <th className="sortable" onClick={() => toggleSort('broker')}>Broker{arrow('broker')}</th>
        <th className="sortable" onClick={() => toggleSort('amount')}>Amount{arrow('amount')}</th>
        <th className="sortable" onClick={() => toggleSort('quantity')}>Quantity{arrow('quantity')}</th>
        <th>Notes</th>
        <th />
      </tr></thead>
      <tbody>{loading ? <tr><td colSpan={8} className="empty"><strong>Loading…</strong></td></tr> : sorted.length ? sorted.map(t => <tr key={t.id}>
        <td>{since(t.date)}</td>
        <td><span className={`badge txn-${t.type.toLowerCase()}`}>{label(t.type)}</span></td>
        <td><strong className="trunc-name" title={t.holdingName}>{t.holdingName}</strong></td>
        <td><span className="trunc-cell" title={t.broker || ''}>{t.broker || '—'}</span></td>
        <td><strong>{money(t.amount, t.currency)}</strong></td>
        <td>{t.quantity ?? '—'}</td>
        <td className="txn-notes">{t.notes || '—'}</td>
        <td className="actions actions-vertical"><button className="primary-link" onClick={() => setEditing(t)}>Edit</button><button className="danger-link" onClick={() => void remove(t)}>Delete</button></td>
      </tr>) : <tr><td colSpan={8} className="empty"><strong>No transactions match</strong><span>Log a buy, sell, split, interest, or adjustment against a holding.</span>{holdings.length > 0 && <button className="primary" onClick={() => setCreating(true)}>Log transaction</button>}</td></tr>}</tbody>
    </table></section>
    {(creating || editing) && <TransactionModal transaction={editing} holdings={holdings} onClose={() => { setCreating(false); setEditing(null) }} onSaved={() => { setCreating(false); setEditing(null); void reload() }} />}
  </>
}

export const txnTypeHint: Partial<Record<TransactionType, string>> = {
  BUY: 'Adds a lot — increases invested amount and quantity.',
  SELL: 'Matches lots oldest-first (FIFO) to book realised P/L; cuts invested and quantity.',
  SPLIT: 'Multiplies quantity by the number entered (e.g. 2 for a 2-for-1). No amount for a split.',
  INTEREST: 'Coupon or dividend — adds to current value only. No change to quantity or invested.',
  ADJUSTMENT: 'Nudges invested amount and/or quantity by the values entered.',
  REPAY: 'A loan repayment — interest for the period is settled first, the rest cuts the outstanding balance.',
}

export function TransactionModal({ transaction, holdings, onClose, onSaved }: { transaction: Transaction | null; holdings: Holding[]; onClose: () => void; onSaved: () => void }) {
  const holdingOf = (id: string) => holdings.find(h => h.id === id)
  const isLiab = (id: string) => holdingOf(id)?.kind === 'LIABILITY'
  const startHoldingId = transaction?.holdingId ?? holdings[0]?.id ?? ''
  const [form, setForm] = useState(() => transaction
    ? { holdingId: transaction.holdingId, type: transaction.type, date: transaction.date.slice(0, 10), amount: String(transaction.amount), quantity: transaction.quantity != null ? String(transaction.quantity) : '', interestPaid: !!transaction.interestPaid, notes: transaction.notes || '' }
    : { holdingId: startHoldingId, type: (isLiab(startHoldingId) ? 'REPAY' : 'BUY') as TransactionType, date: new Date().toISOString().slice(0, 10), amount: '', quantity: '', interestPaid: false, notes: '' })
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const liability = isLiab(form.holdingId)
  const allowedTypes = liability ? liabilityTxnTypes : assetTxnTypes
  const set = (key: string, value: string | boolean) => setForm(current => {
    const next = { ...current, [key]: value }
    if (key === 'holdingId') {
      const nowLiab = holdings.find(h => h.id === value)?.kind === 'LIABILITY'
      if (nowLiab && next.type !== 'REPAY' && next.type !== 'ADJUSTMENT') next.type = 'REPAY'
      if (!nowLiab && !assetTxnTypes.includes(next.type)) next.type = 'BUY'
    }
    return next
  })
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const payload = { holdingId: form.holdingId, type: form.type, date: form.date, amount: form.type === 'SPLIT' ? 0 : numeric(form.amount), quantity: form.quantity ? numeric(form.quantity) : null, interestPaid: form.type === 'INTEREST' ? form.interestPaid : null, notes: form.notes || null }
    try {
      await api(transaction ? `/api/transactions/${transaction.id}` : '/api/transactions', { method: transaction ? 'PUT' : 'POST', body: JSON.stringify(payload) })
      onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not save transaction') } finally { setSaving(false) }
  }
  const showQuantity = ['BUY', 'SELL', 'SPLIT', 'ADJUSTMENT'].includes(form.type)
  const typeHint = form.type === 'INTEREST'
    ? (form.interestPaid ? 'Cash received — adds to realised P/L.' : 'Accrues onto current value. No change to quantity or invested.')
    : (txnTypeHint[form.type] ?? '')
  return <div className="modal-backdrop"><section className="modal"><div className="modal-header"><div><p className="eyebrow">{transaction ? 'EDIT TRANSACTION' : 'NEW TRANSACTION'}</p><h2>{transaction ? label(transaction.type) : 'Log a transaction'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}><div className="form-grid">
      <Field label="Holding" required wide><HoldingPicker holdings={holdings} value={form.holdingId} onChange={id => set('holdingId', id)} /></Field>
      <Field label={<>Type <InfoTip text={typeHint} /></>} required><select required value={form.type} onChange={e => set('type', e.target.value)}>{allowedTypes.map(t => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
      <Field label="Date" required><input required type="date" value={form.date} onChange={e => set('date', e.target.value)} /></Field>
      {form.type !== 'SPLIT' && <Field label={form.type === 'SELL' ? 'Sale proceeds (total)' : form.type === 'REPAY' ? 'Repayment amount' : form.type === 'INTEREST' ? 'Income' : 'Amount'}><input type="number" min="0" step="0.01" value={form.amount} onChange={e => set('amount', e.target.value)} /></Field>}
      {showQuantity && <Field label={form.type === 'SPLIT' ? 'Split multiplier' : 'Quantity'}><input type="number" step="any" value={form.quantity} onChange={e => set('quantity', e.target.value)} /></Field>}
      {form.type === 'INTEREST' && <div className="check-row"><label><input type="checkbox" checked={form.interestPaid} onChange={e => set('interestPaid', e.target.checked)} /> Received in cash <InfoTip text="On: the income is booked as realised P/L. Off: it accrues onto the holding's current value." /></label></div>}
      <Field label="Notes" wide><textarea maxLength={1024} value={form.notes} onChange={e => set('notes', e.target.value)} placeholder="Optional notes" /></Field>
    </div>
    {error && <p className="form-error">{error}</p>}
    <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || !form.holdingId}>{saving ? 'Saving…' : transaction ? 'Save changes' : 'Log transaction'}</button></div>
    </form>
  </section></div>
}

// Client-side type-ahead over the loaded holdings — the plain dropdown gets unusable past a few dozen.
// `allowClear` adds an explicit "clear back to no selection" affordance (a dropdown row plus an
// inline × once something is picked) — used by the Transactions filter's "All holdings" state.
export function HoldingPicker({ holdings, value, onChange, allowClear, clearLabel = 'All holdings', className }: {
  holdings: Holding[]; value: string; onChange: (id: string) => void; allowClear?: boolean; clearLabel?: string; className?: string
}) {
  const selected = holdings.find(h => h.id === value)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])
  const q = query.trim().toLowerCase()
  const matches = holdings.filter(h => !q || `${h.name} ${h.broker ?? ''} ${h.categoryName}`.toLowerCase().includes(q)).slice(0, 25)
  return <div className={`tag-input${className ? ` ${className}` : ''}`} ref={boxRef}>
    <div className="tag-input-field">
      <input value={open ? query : (selected ? `${selected.name} · ${selected.broker || 'unassigned'}` : '')}
        placeholder={allowClear && !selected ? clearLabel : 'Search holdings by name or broker…'}
        onFocus={() => { setQuery(''); setOpen(true) }}
        onChange={e => { setQuery(e.target.value); setOpen(true) }} />
      {allowClear && value && !open && <button type="button" className="tag-input-clear" title={clearLabel} aria-label={clearLabel} onMouseDown={e => e.preventDefault()} onClick={() => onChange('')}>×</button>}
      {open && (matches.length > 0 || allowClear) && <ul className="tag-menu">
        {allowClear && <li><button type="button" className="tag-menu-create" onMouseDown={e => e.preventDefault()} onClick={() => { onChange(''); setOpen(false); setQuery('') }}>{clearLabel}</button></li>}
        {matches.map(h => <li key={h.id}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => { onChange(h.id); setOpen(false); setQuery('') }}>
          <b>{h.name}</b> <span>{h.broker || 'unassigned'} · {h.categoryName}</span>
        </button></li>)}
      </ul>}
    </div>
  </div>
}

export function ImportModal({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const [tab, setTab] = useState<'csv' | 'xml'>('csv')
  const [busy, setBusy] = useState(false)
  const [fileName, setFileName] = useState('')
  const [result, setResult] = useState<ImportResult | null>(null)
  const csvExample = 'id,holdingId,type,date,amount,quantity,notes\n,h-abc123,BUY,2026-01-15,100000,40,Initial buy\n,h-abc123,SELL,2026-03-01,20000,5,Partial profit booking'
  const xmlExample = '<transactions>\n  <transaction>\n    <holdingId>h-abc123</holdingId>\n    <type>BUY</type>\n    <date>2026-01-15</date>\n    <amount>100000</amount>\n    <quantity>40</quantity>\n    <notes>Initial buy</notes>\n  </transaction>\n</transactions>'

  const importFile = async (file: File) => {
    setBusy(true); setResult(null); setFileName(file.name)
    try {
      const isXml = file.name.toLowerCase().endsWith('.xml')
      const text = await file.text()
      const response = await fetch(`${API_URL}/api/transactions/import`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': isXml ? 'application/xml' : 'text/csv', 'X-Demo-User': 'demo@finsights.local' }, body: text,
      })
      const parsed = await response.json()
      if (!response.ok) throw new Error(parsed.message ?? 'Import failed')
      setResult(parsed)
      if (parsed.created || parsed.updated) onImported()
    } catch (err) { setResult({ created: 0, updated: 0, skipped: 0, errors: [{ row: 0, message: err instanceof Error ? err.message : 'Import failed' }] }) }
    finally { setBusy(false) }
  }

  return <div className="modal-backdrop" onClick={onClose}><section className="modal" onClick={e => e.stopPropagation()}>
    <div className="modal-header"><div><p className="eyebrow">BULK UPDATE</p><h2>Import transactions</h2></div><button className="close" onClick={onClose}>×</button></div>

    <div className="import-upload">
      <label className="outline file-label">{busy ? 'Importing…' : fileName ? 'Choose a different file' : '↑ Choose a CSV or XML file'}
        <input type="file" accept=".csv,.xml,text/csv,text/xml,application/xml" hidden disabled={busy}
          onChange={e => { const f = e.target.files?.[0]; if (f) void importFile(f); e.target.value = '' }} />
      </label>
      {fileName && <span className="hint">{fileName}</span>}
    </div>
    {result && <section className={`import-summary ${result.errors.length ? 'has-errors' : ''}`}>
      <strong>{result.created} created · {result.updated} updated · {result.skipped} skipped</strong>
      {result.errors.map((err, i) => <span key={i}>{err.row ? `Row ${err.row}: ` : ''}{err.message}</span>)}
    </section>}

    <p className="hint">Instruments and holdings stay manual — this bulk-imports <b>transactions only</b>, each linked to an existing <b>holdingId</b>. Leave <b>id</b> blank to log a new transaction, or include an existing transaction's id to update it. Use "↓ Export" on this page with your current transactions to get real holding ids to work from.</p>
    <div className="format-tabs">
      <button type="button" className={tab === 'csv' ? 'active' : ''} onClick={() => setTab('csv')}>CSV</button>
      <button type="button" className={tab === 'xml' ? 'active' : ''} onClick={() => setTab('xml')}>XML</button>
    </div>
    <div className="format-fields-scroll"><table className="format-fields">
      <thead><tr><th>Field</th><th>Required</th><th>Notes</th></tr></thead>
      <tbody>
        <tr><td>id</td><td>No</td><td>Existing transaction id → updates it. Blank → logs a new one.</td></tr>
        <tr><td>holdingId</td><td>Yes</td><td>The holding (instrument + broker) this transaction belongs to.</td></tr>
        <tr><td>type</td><td>Yes</td><td>BUY, SELL, SPLIT, INTEREST, ADJUSTMENT.</td></tr>
        <tr><td>date</td><td>Yes</td><td>YYYY-MM-DD.</td></tr>
        <tr><td>amount</td><td>No</td><td>Plain number; default 0.</td></tr>
        <tr><td>quantity</td><td>No</td><td>Plain number, decimals allowed.</td></tr>
        <tr><td>notes</td><td>No</td><td>Free text.</td></tr>
      </tbody>
    </table></div>
    <div className="panel-heading"><h3>Example {tab.toUpperCase()}</h3></div>
    <pre className="format-example">{tab === 'csv' ? csvExample : xmlExample}</pre>
    <div className="modal-actions"><button className="primary" onClick={onClose}>Close</button></div>
  </section></div>
}
