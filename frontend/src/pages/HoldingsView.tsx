import { useEffect, useMemo, useState } from 'react'
import type * as React from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { money, rate, percent, label, since, ago, numeric, blankHoldingForm, frequencies, frequencyLabel, repaymentFrequencies, repaymentLabel, currencies, selectableValuationMethods, valuationMethodLabel } from '../util'
import { Field, InfoTip, TagInput, SymbolSearchInput, SuggestInput } from '../ui'
import type { Holding, Category, ValuationMethod, Frequency, RepaymentFrequency, MarketQuote, ValuationDetail, Transaction } from '../types'

export type HoldingSortKey = 'name' | 'categoryName' | 'broker' | 'investedValue' | 'currentValue' | 'profitLoss'
// ---------------------------------------------------------------------------
// Holdings — a named position, filed under one category and held at one broker.
// ---------------------------------------------------------------------------

export function HoldingsView({ holdings, categories, reload, onEdit, onAdd, onOpen }: {
  holdings: Holding[]; categories: Category[]; reload: () => Promise<void>; onEdit: (h: Holding) => void
  onAdd: () => void; onOpen: (h: Holding) => void
}) {
  const [filter, setFilter] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [sortKey, setSortKey] = useState<HoldingSortKey | null>(null)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [dragId, setDragId] = useState<string | null>(null)
  const [dragOverId, setDragOverId] = useState<string | null>(null)
  const filtered = useMemo(() => holdings.filter(h =>
    `${h.name} ${h.broker ?? ''} ${h.categoryName} ${h.currency} ${h.tags.join(' ')}`.toLowerCase().includes(filter.toLowerCase())
    && (!categoryId || h.categoryId === categoryId)), [holdings, filter, categoryId])
  const sorted = useMemo(() => {
    if (!sortKey) return filtered
    const dir = sortDir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => {
      const av = a[sortKey] ?? '', bv = b[sortKey] ?? ''
      return typeof av === 'string' && typeof bv === 'string' ? av.localeCompare(bv) * dir : ((av as number) - (bv as number)) * dir
    })
  }, [filtered, sortKey, sortDir])

  // Click cycles ascending → descending → back to the custom drag order (Excel-style tri-state).
  const toggleSort = (key: HoldingSortKey) => {
    if (sortKey !== key) { setSortKey(key); setSortDir('asc') }
    else if (sortDir === 'asc') setSortDir('desc')
    else setSortKey(null)
  }
  const arrow = (key: HoldingSortKey) => sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''

  const dropOnto = async (targetId: string) => {
    const draggedId = dragId
    setDragId(null); setDragOverId(null)
    if (!draggedId || draggedId === targetId) return
    const ids = holdings.map(h => h.id)
    const from = ids.indexOf(draggedId), to = ids.indexOf(targetId)
    if (from === -1 || to === -1) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    await api('/api/holdings/reorder', { method: 'PATCH', body: JSON.stringify({ orderedIds: ids }) })
    await reload()
  }

  const remove = async (holding: Holding) => { if (confirm(`Delete ${holding.name}?`)) { await api(`/api/holdings/${holding.id}`, { method: 'DELETE' }); await reload() } }

  return <>
    <section className="holdings-toolbar">
      <div className="search"><span>⌕</span><input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Search holdings, brokers, tags" /></div>
      <select className="filter-select" value={categoryId} onChange={e => setCategoryId(e.target.value)}><option value="">All categories</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select>
      {sortKey && <button className="outline compact" onClick={() => setSortKey(null)}>Clear sort</button>}
      <button className="primary push-end" onClick={onAdd} disabled={!categories.length} title={categories.length ? '' : 'Add a category first'}>+ Add holding</button>
    </section>
    <section className="table-panel"><table>
      <thead><tr>
        <th className="drag-col" title={sortKey ? 'Clear the column sort to drag-reorder rows' : 'Drag rows to reorder'} />
        <th className="sortable" onClick={() => toggleSort('name')}>Holding{arrow('name')}</th>
        <th className="sortable" onClick={() => toggleSort('broker')}>Broker{arrow('broker')}</th>
        <th className="sortable" onClick={() => toggleSort('categoryName')}>Category{arrow('categoryName')}</th>
        <th className="sortable" onClick={() => toggleSort('investedValue')}>Invested{arrow('investedValue')}</th>
        <th className="sortable" onClick={() => toggleSort('currentValue')}>Current value{arrow('currentValue')}</th>
        <th className="sortable" onClick={() => toggleSort('profitLoss')}>P/L{arrow('profitLoss')}</th>
        <th>Quantity</th>
        <th>Tags</th>
        <th />
      </tr></thead>
      <tbody>{sorted.length ? sorted.map(holding => <tr key={holding.id} className={dragOverId === holding.id ? 'drag-over' : ''}
          onDragOver={e => { if (!sortKey) { e.preventDefault(); setDragOverId(holding.id) } }}
          onDragLeave={() => setDragOverId(current => current === holding.id ? null : current)}
          onDrop={e => { e.preventDefault(); void dropOnto(holding.id) }}>
        <td className="drag-col">
          {!sortKey && <span className="drag-handle" draggable onDragStart={() => setDragId(holding.id)} onDragEnd={() => { setDragId(null); setDragOverId(null) }} title="Drag to reorder">⠿</span>}
        </td>
        <td><div className="name-cell">
          <button className="name-button" onClick={() => onOpen(holding)} title={holding.name}><strong>{holding.name}</strong><small className="holding-ref">{holding.holdingId}</small></button>
          {holding.description && <InfoTip text={holding.description} />}
        </div></td>
        <td><span className="trunc-cell" title={holding.broker || ''}>{holding.broker || '—'}</span></td>
        <td><span className={`badge trunc-cell ${holding.kind === 'LIABILITY' ? 'liability' : ''}`} title={holding.categoryName}>{holding.categoryName}</span><small className="owner">{label(holding.valuationMethod)}</small></td>
        <td>{money(holding.investedValue, holding.currency)}</td>
        <td><strong>{money(holding.currentValue, holding.currency)}</strong></td>
        <td>{holding.kind === 'LIABILITY' ? <span className="owner">—</span> : <span className={holding.profitLoss >= 0 ? 'positive' : 'negative'}>{holding.profitLoss >= 0 ? '+' : ''}{money(holding.profitLoss, holding.currency)}<small>{percent(holding.profitLossPercentage)}</small></span>}</td>
        <td>{holding.quantity != null && holding.quantity > 0
          ? <span>{holding.quantity}<small className="owner">avg {rate(holding.investedValue / holding.quantity, holding.currency)}</small></span>
          : <span className="owner">—</span>}</td>
        <td>{holding.liquidWithinSevenDays || holding.blocked || holding.tags.length
          ? <div className="tag-row">
              {holding.liquidWithinSevenDays && <em className="tag-liquid">Liquid</em>}
              {holding.blocked && <em className="tag-npa">NPA</em>}
              {holding.tags.slice(0, 5).map(tag => <em key={tag} title={tag}>{tag}</em>)}
              {holding.tags.length > 5 && <em className="tag-more" title={holding.tags.slice(5).join(', ')}>+{holding.tags.length - 5}</em>}
            </div>
          : <span className="owner">—</span>}</td>
        <td className="actions actions-vertical"><button className="primary-link" onClick={() => onEdit(holding)}>Edit</button><button className="danger-link" onClick={() => void remove(holding)}>Delete</button></td>
      </tr>) : <tr><td colSpan={10} className="empty"><strong>No holdings match</strong><span>Every holding maps to a category and a broker. {categories.length ? 'Add one below.' : 'Add a category first.'}</span><button className="primary" onClick={onAdd} disabled={!categories.length}>Add holding</button></td></tr>}</tbody>
    </table></section>
  </>
}

export function HoldingModal({ holding, category, categories, holdings, onClose, onSaved }: {
  holding: Holding | null; category: Category | null; categories: Category[]; holdings: Holding[]; onClose: () => void; onSaved: () => void
}) {
  const startCategoryId = holding?.categoryId ?? category?.id ?? categories[0]?.id ?? ''
  const [form, setForm] = useState(() => holding
    ? { categoryId: holding.categoryId, name: holding.name, valuationMethod: holding.valuationMethod, tickerSymbol: holding.tickerSymbol || '', currency: holding.currency, fixedAnnualRate: holding.fixedAnnualRate ? String(holding.fixedAnnualRate * 100) : '', compoundingFrequency: holding.compoundingFrequency || 'QUARTERLY', liquidWithinSevenDays: holding.liquidWithinSevenDays, blocked: holding.blocked, tags: [...holding.tags], broker: holding.broker || '', quantity: holding.quantity != null ? String(holding.quantity) : '', investedValue: String(holding.investedValue), currentValue: String(holding.currentValue), fixedRateStartDate: holding.fixedRateStartDate || new Date().toISOString().slice(0, 10), fixedRateEndDate: holding.fixedRateEndDate || '', repaymentFrequency: holding.repaymentFrequency || 'MONTHLY', emiAmount: holding.emiAmount != null ? String(holding.emiAmount) : '', emiDayOfMonth: holding.emiDayOfMonth != null ? String(holding.emiDayOfMonth) : '', loanTermMonths: holding.loanTermMonths != null ? String(holding.loanTermMonths) : '', repaymentDueDate: holding.repaymentDueDate || '', description: holding.description || '' }
    : blankHoldingForm(startCategoryId))
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false)
  const brokerSuggestions = useMemo(() => [...new Set(holdings.map(h => h.broker).filter((b): b is string => !!b))].sort(), [holdings])
  const selectedCategory = categories.find(c => c.id === form.categoryId)
  const isLiability = selectedCategory?.kind === 'LIABILITY'
  const isFixedRate = !isLiability && form.valuationMethod === 'FIXED_RATE'
  const isMarket = !isLiability && form.valuationMethod === 'MARKET_PRICE'
  const isOneTime = isLiability && form.repaymentFrequency === 'ONE_TIME'
  const isEdit = !!holding
  const set = (key: string, value: string | boolean) => setForm(current => ({ ...current, [key]: value }))
  const allowedMethods = selectedCategory?.allowedValuationMethods
  const methodOptions = allowedMethods && allowedMethods.length ? selectableValuationMethods.filter(m => allowedMethods.includes(m)) : selectableValuationMethods

  // The category can restrict which valuation methods its holdings may use — if the current
  // pick falls outside that set (a category change, most often), snap to the first one allowed.
  useEffect(() => {
    if (isLiability || !methodOptions.length || methodOptions.includes(form.valuationMethod)) return
    setForm(current => ({ ...current, valuationMethod: methodOptions[0] }))
  }, [form.categoryId, methodOptions.join(','), isLiability]) // eslint-disable-line react-hooks/exhaustive-deps

  // Market-linked holdings take their name and currency from the ticker, not the user.
  useEffect(() => {
    if (!isMarket) return
    const symbol = form.tickerSymbol.trim()
    if (!symbol) return
    const timer = setTimeout(() => {
      api<MarketQuote>(`/api/market/quote?symbol=${encodeURIComponent(symbol)}`)
        .then(q => setForm(current => current.tickerSymbol.trim().toUpperCase() === symbol.toUpperCase()
          ? { ...current, name: q.name || current.name, currency: q.currency || current.currency }
          : current))
        .catch(() => { /* leave name/currency as-is if the feed is unreachable */ })
    }, 400)
    return () => clearTimeout(timer)
  }, [form.tickerSymbol, isMarket])

  const [tagIdeas, setTagIdeas] = useState<string[]>([])
  useEffect(() => {
    if (!form.categoryId) { setTagIdeas([]); return }
    const query = new URLSearchParams({ categoryId: form.categoryId, valuationMethod: form.valuationMethod, limit: '50' })
    api<string[]>(`/api/holdings/tag-suggestions?${query}`).then(setTagIdeas).catch(() => setTagIdeas([]))
  }, [form.categoryId, form.valuationMethod])

  // A holding is 1-1 with a (name, broker) pair — block a new one that would collide.
  const duplicate = !isEdit && !!form.name.trim() && !!form.broker.trim() && holdings.some(h =>
    h.name.trim().toLowerCase() === form.name.trim().toLowerCase()
    && (h.broker ?? '').trim().toLowerCase() === form.broker.trim().toLowerCase())

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const recurring = isLiability && !isOneTime
    const payload = {
      categoryId: form.categoryId, name: form.name,
      valuationMethod: isLiability ? 'MANUAL' : form.valuationMethod,
      tickerSymbol: isLiability ? null : (form.tickerSymbol || null), broker: form.broker.trim(),
      currency: form.currency, quantity: isLiability ? null : (form.quantity ? numeric(form.quantity) : null),
      investedValue: numeric(form.investedValue), currentValue: isFixedRate ? null : numeric(form.currentValue),
      fixedAnnualRate: (isFixedRate || isLiability) && form.fixedAnnualRate ? numeric(form.fixedAnnualRate) / 100 : null,
      compoundingFrequency: isFixedRate ? form.compoundingFrequency : null,
      fixedRateStartDate: isFixedRate ? form.fixedRateStartDate : null,
      fixedRateEndDate: isFixedRate && form.fixedRateEndDate ? form.fixedRateEndDate : null,
      repaymentFrequency: isLiability ? form.repaymentFrequency : null,
      repaymentDueDate: isOneTime && form.repaymentDueDate ? form.repaymentDueDate : null,
      emiAmount: recurring && form.emiAmount ? numeric(form.emiAmount) : null,
      emiDayOfMonth: recurring && form.emiDayOfMonth ? Math.round(numeric(form.emiDayOfMonth)) : null,
      loanTermMonths: recurring && form.loanTermMonths ? Math.round(numeric(form.loanTermMonths)) : null,
      liquidWithinSevenDays: form.liquidWithinSevenDays, blocked: form.blocked,
      description: form.description || null, notes: null,
      tags: form.tags,
    }
    try { await api(holding ? `/api/holdings/${holding.id}` : '/api/holdings', { method: holding ? 'PUT' : 'POST', body: JSON.stringify(payload) }); onSaved() }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not save holding') } finally { setSaving(false) }
  }

  const kindWord = isLiability ? 'LIABILITY' : 'HOLDING'
  return <div className="modal-backdrop"><section className="modal"><div className="modal-header"><div><p className="eyebrow">{holding ? `EDIT ${kindWord}` : `NEW ${kindWord}`}</p><h2>{holding ? holding.name : category ? `Add ${isLiability ? 'a liability' : 'a holding'} in ${category.name}` : isLiability ? 'Add a liability' : 'Add a holding'}</h2></div><button className="close" onClick={onClose}>×</button></div>
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Category" required>
          <select required disabled={!!category && !holding} value={form.categoryId} onChange={e => set('categoryId', e.target.value)}>
            {!categories.length && <option value="">No categories yet</option>}
            {categories.map(c => <option key={c.id} value={c.id}>{c.name} ({label(c.kind)})</option>)}
          </select>
        </Field>
        {!isLiability && <Field label="Valuation method" required>
          <select value={form.valuationMethod} onChange={e => set('valuationMethod', e.target.value)}>
            {methodOptions.map(m => <option key={m} value={m}>{valuationMethodLabel(m)}</option>)}
          </select>
          {methodOptions.length < selectableValuationMethods.length && <p className="hint">Restricted by {selectedCategory?.name}'s allowed valuation methods.</p>}
        </Field>}
        {isMarket && <Field label="Ticker symbol" required wide><SymbolSearchInput value={form.tickerSymbol} onChange={v => set('tickerSymbol', v)} /></Field>}
        {isEdit
          ? <Field label="Name" required><input required maxLength={128} value={form.name} disabled={isMarket} onChange={e => set('name', e.target.value)} placeholder={isMarket ? 'Filled from the ticker' : isLiability ? 'e.g. HDFC Home Loan' : 'e.g. Reliance Industries, HDFC FD'} /></Field>
          : <div className="field-pair">
              <Field label="Name" required><input required maxLength={128} value={form.name} disabled={isMarket} onChange={e => set('name', e.target.value)} placeholder={isMarket ? 'Filled from the ticker' : isLiability ? 'e.g. HDFC Home Loan' : 'e.g. Reliance Industries'} /></Field>
              <Field label={isLiability ? 'Lender' : 'Broker / platform'} required><SuggestInput required maxLength={96} value={form.broker} suggestions={brokerSuggestions} onChange={v => set('broker', v)} placeholder={isLiability ? 'HDFC Bank, Bajaj Finance…' : 'Kite, Groww, HDFC Bank…'} /></Field>
            </div>}
        <Field label="Description" wide><input value={form.description} onChange={e => set('description', e.target.value)} placeholder="One line — shows in the ⓘ tooltip on the Holdings table" maxLength={1024} /></Field>
        {!isLiability && !isEdit && <Field label="Quantity" required={isMarket}><input required={isMarket} type="number" step="any" min="0" value={form.quantity} onChange={e => set('quantity', e.target.value)} placeholder="Units held" /></Field>}
        {!isLiability && !isEdit && <Field label={isFixedRate ? 'Principal' : 'Invested value'} required><input required type="number" min="0" step="0.01" value={form.investedValue} onChange={e => set('investedValue', e.target.value)} /></Field>}
        {isFixedRate && <>
          <Field label="Annual rate (%)" required><input required type="number" min="0" step="0.01" value={form.fixedAnnualRate} onChange={e => set('fixedAnnualRate', e.target.value)} /></Field>
          <Field label="Interest payout frequency" required><select value={form.compoundingFrequency} onChange={e => set('compoundingFrequency', e.target.value)}>{frequencies.map(item => <option key={item} value={item}>{frequencyLabel(item)}</option>)}</select></Field>
          <Field label="Start date" required><input required type="date" value={form.fixedRateStartDate} onChange={e => set('fixedRateStartDate', e.target.value)} /></Field>
          <Field label="Maturity date"><input type="date" value={form.fixedRateEndDate} min={form.fixedRateStartDate} onChange={e => set('fixedRateEndDate', e.target.value)} /></Field>
        </>}
        {isLiability && <>
          <div className="field-pair">
            <Field label="Total amount" required><input required type="number" min="0" step="0.01" disabled={isEdit} value={form.investedValue} onChange={e => set('investedValue', e.target.value)} placeholder="Original loan amount" /></Field>
            <Field label="Outstanding amount" required><input required type="number" min="0" step="0.01" value={form.currentValue} onChange={e => set('currentValue', e.target.value)} placeholder="Still owed" /></Field>
          </div>
          <div className="field-pair">
            <Field label="Repayment frequency" required><select value={form.repaymentFrequency} onChange={e => set('repaymentFrequency', e.target.value)}>{repaymentFrequencies.map(f => <option key={f} value={f}>{repaymentLabel(f)}</option>)}</select></Field>
            <Field label="Interest rate (% p.a.)"><input type="number" min="0" step="0.01" value={form.fixedAnnualRate} onChange={e => set('fixedAnnualRate', e.target.value)} placeholder="Splits Repay into interest + principal" /></Field>
          </div>
          {isOneTime
            ? <Field label="Due date" required><input required type="date" value={form.repaymentDueDate} onChange={e => set('repaymentDueDate', e.target.value)} /></Field>
            : <>
                <Field label="EMI due day" required><input required type="number" min="1" max="31" step="1" value={form.emiDayOfMonth} onChange={e => set('emiDayOfMonth', e.target.value)} placeholder="1–31" /></Field>
                <div className="field-pair">
                  <Field label="Instalment amount"><input type="number" min="0" step="0.01" value={form.emiAmount} onChange={e => set('emiAmount', e.target.value)} placeholder="Per instalment" /></Field>
                  <Field label="Instalments remaining"><input type="number" min="1" step="1" value={form.loanTermMonths} onChange={e => set('loanTermMonths', e.target.value)} placeholder="Count" /></Field>
                </div>
              </>}
        </>}
        {!isLiability && <div className="field-pair">
          <Field label="Currency"><select disabled={isEdit || isMarket} value={form.currency} onChange={e => set('currency', e.target.value)}>{currencies.map(item => <option key={item}>{item}</option>)}</select></Field>
          <Field label={isFixedRate ? 'Current value (computed)' : isMarket ? 'Current value (live price)' : 'Current value'} required={!isFixedRate && !isMarket}><input required={!isFixedRate && !isMarket} type="number" min="0" step="0.01" disabled={isFixedRate || isMarket} value={form.currentValue} onChange={e => set('currentValue', e.target.value)} placeholder={isMarket ? 'Priced after saving' : isFixedRate ? 'Computed after saving' : ''} /></Field>
        </div>}
        {isLiability && <Field label="Currency"><select disabled={isEdit} value={form.currency} onChange={e => set('currency', e.target.value)}>{currencies.map(item => <option key={item}>{item}</option>)}</select></Field>}
        <div className="check-row">
          <label><input type="checkbox" checked={form.liquidWithinSevenDays} onChange={e => set('liquidWithinSevenDays', e.target.checked)} /> Liquid within 7 days <InfoTip text="Money you could realistically access within a week. Feeds the “liquid within 7 days” figure on the overview so you know how much of the portfolio is reachable in an emergency." /></label>
          <label><input type="checkbox" checked={form.blocked} onChange={e => set('blocked', e.target.checked)} /> Blocked / NPA <InfoTip text="The holding is locked, pledged, in default, or a non-performing asset. It is valued separately from healthy assets and flagged in the Action centre." /></label>
        </div>
        <Field label="Tags" wide>
          <TagInput tags={form.tags} suggestions={tagIdeas} onChange={next => setForm(current => ({ ...current, tags: next }))} />
        </Field>
      </div>
      {isEdit && !isLiability && <p className="form-callout"><span className="form-callout-dot">i</span>
        <span><b>Broker</b> is fixed for the life of a holding, and <b>invested value</b> &amp; <b>quantity</b> are calculated from its transactions — add or edit transactions to change them.</span>
      </p>}
      {isEdit && isLiability && <p className="form-callout"><span className="form-callout-dot">i</span>
        <span><b>Lender</b> and <b>total amount</b> are fixed once a loan exists. Update the <b>outstanding amount</b> here, or mark instalments paid from the Action centre.</span>
      </p>}
      {duplicate && <p className="form-error">A holding named "{form.name.trim()}" at "{form.broker.trim()}" already exists — one holding maps to one broker.</p>}
      {error && <p className="form-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="outline" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || !form.categoryId || !form.name.trim()
        || (isMarket && !form.tickerSymbol.trim())
        || (isMarket && !isEdit && !form.quantity)
        || (!isEdit && !isLiability && !form.investedValue)
        || (!isLiability && form.valuationMethod === 'MANUAL' && !form.currentValue)
        || (isLiability && (!form.investedValue || !form.currentValue || (isOneTime ? !form.repaymentDueDate : (!form.emiDayOfMonth || (!form.emiAmount && !form.loanTermMonths)))))
        || duplicate}>{saving ? 'Saving…' : holding ? 'Save changes' : 'Add holding'}</button></div>
    </form>
  </section></div>
}

export function HoldingDrawer({ holding, displayCurrency, onClose, onEdit }: { holding: Holding; displayCurrency: string; onClose: () => void; onEdit: (holding: Holding) => void }) {
  const [detail, setDetail] = useState<ValuationDetail | null>(null)
  const [txns, setTxns] = useState<Transaction[] | null>(null)
  const [visibleTxns, setVisibleTxns] = useState(10)
  const [calcOpen, setCalcOpen] = useState(false)
  useEffect(() => { api<ValuationDetail>(`/api/holdings/${holding.id}/valuation`).then(setDetail).catch(() => setDetail(null)) }, [holding.id])
  useEffect(() => { setVisibleTxns(10); api<Transaction[]>(`/api/transactions?holdingId=${holding.id}&currency=${displayCurrency}`).then(setTxns).catch(() => setTxns([])) }, [holding.id, displayCurrency])
  const onTxnScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 48) setVisibleTxns(count => count + 10)
  }
  const isLiab = holding.kind === 'LIABILITY'
  const repaid = Math.max(0, holding.investedValue - holding.currentValue)
  return <div className="modal-backdrop" onClick={onClose}><section className="modal drawer" onClick={e => e.stopPropagation()}>
    <div className="modal-header">
      <div><p className="eyebrow">{label(holding.kind)} · {holding.broker || (isLiab ? 'No lender' : 'Unassigned broker')}</p><h2>{holding.name}</h2><p className="drawer-ref">{holding.holdingId}</p></div>
      <button className="close" onClick={onClose}>×</button>
    </div>
    {holding.description && <p className="drawer-description">{holding.description}</p>}
    {isLiab ? <div className="drawer-metrics">
      <div><p>Outstanding</p><strong>{money(holding.currentValue, holding.currency)}</strong></div>
      <div><p>Total borrowed</p><strong>{money(holding.investedValue, holding.currency)}</strong></div>
      <div><p>Repaid</p><strong>{money(repaid, holding.currency)}{holding.investedValue > 0 ? ` · ${percent(repaid / holding.investedValue * 100)}` : ''}</strong></div>
    </div> : <div className="drawer-metrics">
      <div><p>Current value</p><strong>{money(holding.currentValue, holding.currency)}</strong></div>
      <div><p>Invested</p><strong>{money(holding.investedValue, holding.currency)}</strong></div>
      <div><p>Unrealised P/L</p><strong className={holding.profitLoss >= 0 ? 'positive' : 'negative'}>{`${holding.profitLoss >= 0 ? '+' : ''}${money(holding.profitLoss, holding.currency)} · ${percent(holding.profitLossPercentage)}`}</strong></div>
      <div><p>Realised P/L</p><strong className={holding.realisedProfitLoss > 0 ? 'positive' : holding.realisedProfitLoss < 0 ? 'negative' : ''}>{holding.realisedProfitLoss ? `${holding.realisedProfitLoss >= 0 ? '+' : ''}${money(holding.realisedProfitLoss, holding.currency)}` : '—'}</strong></div>
    </div>}
    <div className="drawer-facts">
      <span>{isLiab ? 'Lender' : 'Broker'}<b>{holding.broker || '—'}</b></span>
      <span>Currency<b>{holding.currency}</b></span>
      {!isLiab && <span>Valuation method<b className="fact-with-icon">{label(holding.valuationMethod)}
        <button type="button" className={`calc-toggle${calcOpen ? ' open' : ''}`} aria-expanded={calcOpen} aria-label="How this value is calculated" title="How this value is calculated" onClick={() => setCalcOpen(o => !o)}><i>i</i></button>
      </b></span>}
      <span>Last updated<b>{since(holding.updatedAt)}</b></span>
      {holding.quantity != null && <span>Quantity<b>{holding.quantity}</b></span>}
      {!isLiab && holding.valuationMethod === 'FIXED_RATE' && <span>Payout frequency<b>{holding.compoundingFrequency ? frequencyLabel(holding.compoundingFrequency) : '—'}</b></span>}
      {!isLiab && holding.valuationMethod === 'FIXED_RATE' && <span>Maturity<b>{holding.fixedRateEndDate ? since(holding.fixedRateEndDate) : 'Open-ended'}</b></span>}
      {!isLiab && holding.accruedIncome > 0 && <span>Accrued income<b className="positive">+{money(holding.accruedIncome, holding.currency)}</b></span>}
      {isLiab && holding.fixedAnnualRate != null && holding.fixedAnnualRate > 0 && <span>Interest rate<b>{percent(holding.fixedAnnualRate * 100)} p.a.</b></span>}
      {isLiab && holding.repaymentFrequency && <span>Repayment<b>{repaymentLabel(holding.repaymentFrequency)}</b></span>}
      {isLiab && holding.repaymentFrequency === 'ONE_TIME' && <span>Due date<b>{holding.repaymentDueDate ? since(holding.repaymentDueDate) : '—'}</b></span>}
      {isLiab && holding.repaymentFrequency !== 'ONE_TIME' && holding.repaymentFrequency && <span>Instalment<b>{holding.emiAmount != null ? money(holding.emiAmount, holding.currency) : holding.loanTermMonths ? `${holding.loanTermMonths} left` : '—'}{holding.emiDayOfMonth ? ` · day ${holding.emiDayOfMonth}` : ''}</b></span>}
      {!isLiab && holding.valuationMethod === 'MARKET_PRICE' && holding.tickerSymbol && <span>Live price
        <b className="fact-with-icon"><span className="live-dot" />{holding.tickerSymbol} · {holding.priceUpdatedAt ? ago(holding.priceUpdatedAt) : 'pending'}</b></span>}
    </div>
    {calcOpen && <div className="calc-panel">
      <div className="panel-heading"><h3>How this value is calculated</h3></div>
      {detail ? <ol className="audit-list">{detail.steps.map((step, i) => <li key={i}>{step}</li>)}</ol> : <p className="hint">Loading calculation…</p>}
      {detail?.projectedMaturityValue != null && <p className="hint">By {since(detail.projectedMaturityDate)}, if the rate holds: <b>{money(detail.projectedMaturityValue, holding.currency)}</b>.</p>}
    </div>}

    <div className="panel-heading"><h3>Transactions</h3><span>{txns && txns.length ? `${txns.length}` : ''}</span></div>
    {txns === null ? <p className="hint">Loading transactions…</p>
      : txns.length === 0 ? <p className="hint">No transactions logged against this holding yet.</p>
      : <div className="drawer-txns" onScroll={onTxnScroll}>{txns.slice(0, visibleTxns).map(t => <div className="drawer-txn" key={t.id}>
          <span className={`badge txn-${t.type.toLowerCase()}`}>{label(t.type)}</span>
          <span className="drawer-txn-date">{since(t.date)}</span>
          <span className="drawer-txn-amount">{money(t.amount, t.currency)}{t.type === 'REPAY' && t.principalPortion != null ? ` · ${money(t.principalPortion, t.currency)} principal` : t.quantity != null ? ` · qty ${t.quantity}` : ''}</span>
          {t.notes && <span className="drawer-txn-notes">{t.notes}</span>}
        </div>)}{visibleTxns < txns.length && <p className="hint drawer-txns-more">Scroll for {txns.length - visibleTxns} more</p>}</div>}

    <div className="modal-actions"><button className="outline" onClick={onClose}>Close</button><button className="primary" onClick={() => onEdit(holding)}>Edit holding</button></div>
  </section></div>
}
