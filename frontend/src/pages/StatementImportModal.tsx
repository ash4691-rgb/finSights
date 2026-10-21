import { useState } from 'react'
import { api, API_URL } from '../api'
import { blankHoldingForm } from '../util'
import { Field, useEscToClose } from '../ui'
import { HoldingModal } from './HoldingsView'
import { TransactionModal } from './TransactionsView'
import type { Category, ExtractedHolding, ExtractedTransaction, Holding, StatementExtractionResult } from '../types'

// ---------------------------------------------------------------------------
// Statement import — one upload box for a CAS statement, a broker holding statement, or a broker
// transaction statement (PDF, or a machine-readable CSV/XML). The model itself reports which kind
// of statement it found; each extracted row is then reviewed one at a time through the ordinary
// Add Holding / Log Transaction form (pre-filled, not auto-saved), and saved (or skipped) exactly
// like a manual entry. The upload step only ever extracts; nothing here writes anything on its
// own — see the backend's StatementImportService for why.
// ---------------------------------------------------------------------------

// A pre-filled holding is seeded at its own average cost (quantity × avg cost per unit), same
// reasoning as a fresh MARKET_PRICE holding: a sane non-zero starting value beats leaving current
// value at 0 until the user has a reason to change it. Left blank when the statement didn't state
// an average cost — better an empty field the user must fill than a silently wrong one. A resolved
// ticker (best-effort, see StatementImportService.resolveTicker) pre-selects MARKET_PRICE with it;
// no match leaves the usual MANUAL default — either way the user can still change both in the form.
const draftFromExtractedHolding = (row: ExtractedHolding): Partial<ReturnType<typeof blankHoldingForm>> => {
  const seedValue = row.averageCostPerUnit != null ? String(Math.round(row.quantity * row.averageCostPerUnit * 100) / 100) : ''
  return {
    name: row.instrumentName,
    quantity: String(row.quantity),
    investedValue: seedValue,
    currentValue: seedValue,
    description: row.isin ? `ISIN ${row.isin}` : '',
    ...(row.tickerSymbol ? { valuationMethod: 'MARKET_PRICE' as const, tickerSymbol: row.tickerSymbol } : {}),
  }
}

const draftFromExtractedTransaction = (row: ExtractedTransaction) => ({
  holdingId: row.matchedHoldingId,
  type: row.type,
  date: row.date,
  amount: row.amount != null ? String(row.amount) : '',
  quantity: row.quantity != null ? String(row.quantity) : '',
  notes: row.notes || '',
})

export function StatementImportModal({ categories, holdings, displayCurrency, onClose, onSaved, onRemoved, onTransactionSaved }: {
  categories: Category[]; holdings: Holding[]; displayCurrency: string; onClose: () => void
  onSaved: (saved: Holding) => void
  // Called once, with every holding id actually deleted, right before the modal closes itself —
  // the caller reconciles its own holdings list; this modal doesn't own that state.
  onRemoved: (holdingIds: string[]) => void
  // Called after a transaction is logged — unlike a saved holding, a transaction isn't spliced
  // into any list this modal holds, so the caller just needs to know to reload.
  onTransactionSaved: () => void
}) {
  useEscToClose(onClose)
  const [busy, setBusy] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [result, setResult] = useState<StatementExtractionResult | null>(null)
  // Indexes (prefixed `h` for holdings, `t` for transactions) into result that have been either
  // saved or explicitly skipped — once every new row is in here, Done unlocks. A matched holding
  // row needs no review (it's already in the portfolio) so it's never in this set at all.
  const [resolved, setResolved] = useState<Set<string>>(new Set())
  const [reviewingHolding, setReviewingHolding] = useState<number | null>(null)
  const [reviewingTxn, setReviewingTxn] = useState<number | null>(null)
  // holdingIds the user explicitly chose to keep despite not appearing in this statement —
  // removed from the to-delete list, and never deleted on Done, however many times the file is
  // re-read.
  const [keptIds, setKeptIds] = useState<Set<string>>(new Set())
  const [finishing, setFinishing] = useState(false)

  // Takes the password as an argument rather than closing over state — a "retry with this
  // password" click needs to send what was *just* typed, before React has necessarily re-rendered
  // with it, and reusing the already-picked file this way means a wrong password doesn't force
  // the user back to their file browser to pick the same statement again.
  const upload = async (pickedFile: File, pdfPassword: string) => {
    setBusy(true); setError(''); setResult(null); setResolved(new Set()); setKeptIds(new Set()); setFile(pickedFile)
    try {
      const body = await pickedFile.arrayBuffer()
      const response = await fetch(`${API_URL}/api/statements/import`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/octet-stream', 'X-Demo-User': 'demo@finsights.local', ...(pdfPassword.trim() ? { 'X-Document-Password': pdfPassword.trim() } : {}) }, body,
      })
      const parsed = await response.json()
      if (!response.ok) throw new Error(parsed.message ?? 'Could not read that statement')
      setResult(parsed)
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not read that statement') }
    finally { setBusy(false) }
  }

  const newHoldingRows = result ? result.holdings.map((row, index) => ({ row, index })).filter(({ row }) => !row.matchedHoldingId) : []
  const matchedHoldingRows = result ? result.holdings.filter(row => row.matchedHoldingId) : []
  const txnRows = result ? result.transactions.map((row, index) => ({ row, index })) : []
  const pendingHoldingRows = newHoldingRows.filter(({ index }) => !resolved.has(`h${index}`))
  const pendingTxnRows = txnRows.filter(({ index }) => !resolved.has(`t${index}`))
  const toRemove = result ? result.toRemove.filter(r => !keptIds.has(r.holdingId)) : []
  const canFinish = pendingHoldingRows.length === 0 && pendingTxnRows.length === 0 && !finishing

  const finish = async () => {
    if (!canFinish) return
    setFinishing(true); setError('')
    try {
      for (const r of toRemove) await api(`/api/holdings/${r.holdingId}`, { method: 'DELETE' })
      onRemoved(toRemove.map(r => r.holdingId))
      onClose()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not finish removing the holdings flagged above') }
    finally { setFinishing(false) }
  }

  return <div className="modal-backdrop"><section className="modal" onClick={e => e.stopPropagation()}>
    <div className="modal-header"><div><p className="eyebrow">BULK IMPORT</p><h2>Upload a statement</h2></div><button className="close" onClick={onClose}>×</button></div>
    <p className="hint">Upload a CAS statement, a broker holding statement, or a broker transaction statement — PDF, CSV, or XML. Nothing is saved or deleted automatically — review what's below, then click Done to apply it.</p>

    <Field label="Statement password"><input type="password" autoComplete="off" value={password} disabled={busy} onChange={e => setPassword(e.target.value)} placeholder="Only needed for a password-protected PDF (usually your PAN, e.g. ABCDE1234F)" /></Field>

    <div className="import-upload">
      <label className="outline file-label">{busy ? 'Reading…' : file ? 'Choose a different file' : '↑ Choose a statement'}
        <input type="file" accept=".pdf,.csv,.xml,application/pdf,text/csv,text/xml,application/xml" hidden disabled={busy}
          onChange={e => { const f = e.target.files?.[0]; if (f) { void upload(f, password) } e.target.value = '' }} />
      </label>
      {file && <span className="hint">{file.name}</span>}
    </div>

    {error && <p className="form-error">{error}
      {file && /password/i.test(error) && <> — update it above, then <button type="button" className="text-link" disabled={busy} onClick={() => void upload(file, password)}>try this file again</button>.</>}
    </p>}

    {result && <>
      {result.statementDate && <p className="hint">Statement as of {result.statementDate}.</p>}
      {result.holdings.length === 0 && result.toRemove.length === 0 && result.transactions.length === 0
        && <p className="hint">Nothing we could extract from this statement.</p>}

      {pendingHoldingRows.length > 0 && <>
        <div className="panel-heading"><h3>Holdings to review</h3><span>{pendingHoldingRows.length}</span></div>
        <div className="mapped-holdings">{pendingHoldingRows.map(({ row, index }) => <div key={`h:${row.isin}:${row.depository ?? ''}`} className="mapped-holding status-new">
            <button type="button" className="mapped-holding-name" onClick={() => setReviewingHolding(index)}>
              <strong title={row.instrumentName}>{row.instrumentName}</strong>
              <small>{row.isin}{row.tickerSymbol ? ` · ${row.tickerSymbol}` : ''}{row.depository && row.depository !== 'UNKNOWN' ? ` · ${row.depository}` : ''}</small>
            </button>
            <div className="mapped-holding-value"><span>Qty {row.quantity}</span>
              <small><button type="button" className="text-link" onClick={() => setResolved(current => new Set(current).add(`h${index}`))}>Skip</button></small>
            </div>
          </div>)}</div>
      </>}

      {pendingTxnRows.length > 0 && <>
        <div className="panel-heading"><h3>Transactions to review</h3><span>{pendingTxnRows.length}</span></div>
        <div className="mapped-holdings">{pendingTxnRows.map(({ row, index }) => <div key={`t:${row.isin}:${row.date}:${index}`} className="mapped-holding status-new">
            <button type="button" className="mapped-holding-name" onClick={() => setReviewingTxn(index)}>
              <strong title={row.instrumentName}>{row.instrumentName}</strong>
              <small>{row.date} · {row.type}{row.isin ? ` · ${row.isin}` : ''}</small>
            </button>
            <div className="mapped-holding-value">{row.amount != null && <span>{row.amount}</span>}
              <small><button type="button" className="text-link" onClick={() => setResolved(current => new Set(current).add(`t${index}`))}>Skip</button></small>
            </div>
          </div>)}</div>
      </>}

      {toRemove.length > 0 && <>
        <div className="panel-heading"><h3>To be deleted</h3><span>{toRemove.length}</span></div>
        <p className="hint">Tagged from an earlier statement import, but not found in this one.</p>
        <div className="mapped-holdings">{toRemove.map(r => <div key={r.holdingId} className="mapped-holding status-remove">
            <div className="mapped-holding-name"><strong title={r.name}>{r.name}</strong><small>{r.broker || 'Unassigned broker'}</small></div>
            <div className="mapped-holding-value"><small><button type="button" className="text-link" onClick={() => setKeptIds(current => new Set(current).add(r.holdingId))}>Keep — don't delete</button></small></div>
          </div>)}</div>
      </>}

      {matchedHoldingRows.length > 0 && <>
        <div className="panel-heading"><h3>Successfully parsed and mapped</h3><span>{matchedHoldingRows.length}</span></div>
        <div className="mapped-holdings">{matchedHoldingRows.map(row => <div key={`m:${row.isin}:${row.depository ?? ''}`} className="mapped-holding status-matched">
            <div className="mapped-holding-name"><strong title={row.instrumentName}>{row.instrumentName}</strong><small>{row.isin} · already in your portfolio</small></div>
            <div className="mapped-holding-value"><span>Qty {row.quantity}</span></div>
          </div>)}</div>
      </>}

      {result.warnings.length > 0 && <section className="import-summary">
        <strong>{result.warnings.length} note{result.warnings.length === 1 ? '' : 's'} from the extraction</strong>
        {result.warnings.map(w => <span key={w}>{w}</span>)}
      </section>}
    </>}

    <div className="modal-actions">
      {(pendingHoldingRows.length > 0 || pendingTxnRows.length > 0) && <p className="hint push-start">
        {pendingHoldingRows.length + pendingTxnRows.length} row{pendingHoldingRows.length + pendingTxnRows.length === 1 ? '' : 's'} still need{pendingHoldingRows.length + pendingTxnRows.length === 1 ? 's' : ''} review before you can finish.
      </p>}
      <button type="button" className="outline" disabled={!canFinish} title={canFinish ? '' : 'Review or skip every pending row first'} onClick={() => void finish()}>{finishing ? 'Finishing…' : 'Done'}</button>
    </div>

    {reviewingHolding != null && result && <HoldingModal holding={null} category={null} categories={categories} holdings={holdings}
      displayCurrency={displayCurrency} draft={draftFromExtractedHolding(result.holdings[reviewingHolding])}
      onClose={() => setReviewingHolding(null)}
      onSaved={saved => { onSaved(saved); setResolved(current => new Set(current).add(`h${reviewingHolding}`)); setReviewingHolding(null) }} />}

    {reviewingTxn != null && result && <TransactionModal transaction={null} holdings={holdings} displayCurrency={displayCurrency}
      draft={draftFromExtractedTransaction(result.transactions[reviewingTxn])}
      onClose={() => setReviewingTxn(null)}
      onSaved={() => { onTransactionSaved(); setResolved(current => new Set(current).add(`t${reviewingTxn}`)); setReviewingTxn(null) }} />}
  </section></div>
}
