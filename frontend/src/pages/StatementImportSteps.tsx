import { Field, SuggestInput } from '../ui'
import { HoldingModal } from './HoldingsView'
import { TransactionModal } from './TransactionsView'
import { draftFromExtractedHolding, draftFromExtractedTransaction, kindLabel, sellDraft } from './statementImportLogic'
import type { Category, ExtractedHolding, ExtractedTransaction, Holding, HoldingToRemove, InstrumentKind, StatementExtractionResult, StatementType } from '../types'

// The two non-upload steps of the statement import: confirming what the first read found (type,
// broker, category per kind) before anything is matched, and the per-row review lists after.

const typeLabel: Record<StatementType, string> = { HOLDINGS: 'Holdings', TRANSACTIONS: 'Transactions', MIXED: 'Both holdings and transactions' }

export function ConfirmStep({ extracted, statementType, onStatementType, broker, onBroker, brokerSuggestions,
  kinds, categories, categoryByKind, onCategory, busy, onContinue }: Readonly<{
  extracted: StatementExtractionResult; statementType: StatementType; onStatementType: (t: StatementType) => void
  broker: string; onBroker: (b: string) => void; brokerSuggestions: string[]
  kinds: InstrumentKind[]; categories: Category[]; categoryByKind: Partial<Record<InstrumentKind, string>>
  onCategory: (kind: InstrumentKind, categoryId: string) => void; busy: boolean; onContinue: () => void
}>) {
  const assetCategories = categories.filter(c => c.kind === 'ASSET' && !c.realEstate)
  return <section className="import-confirm">
    <div className="panel-heading"><h3>Check what we found</h3></div>
    <p className="hint">We found {extracted.holdings.length} holding{extracted.holdings.length === 1 ? '' : 's'} and {extracted.transactions.length} transaction{extracted.transactions.length === 1 ? '' : 's'}. Confirm these before we match them against your portfolio.</p>
    <div className="form-grid">
      <Field label="Statement type" required><select value={statementType} onChange={e => onStatementType(e.target.value as StatementType)}>
        {(Object.keys(typeLabel) as StatementType[]).map(t => <option key={t} value={t}>{typeLabel[t]}{t === extracted.statementType ? ' (detected)' : ''}</option>)}
      </select></Field>
      <Field label="Broker / platform" required>
        <SuggestInput required maxLength={96} value={broker} suggestions={brokerSuggestions} onChange={onBroker} placeholder="Pick one of yours or type a new broker" />
      </Field>
    </div>
    <p className="hint">{extracted.broker
      ? `Detected from the statement: ${extracted.broker}. Holdings are only matched against this one broker — the same fund can be held at several.`
      : 'We couldn\'t tell which broker this statement is from — pick one of yours, or type a new one. Holdings are only matched against this one broker.'}</p>
    {kinds.length > 0 && <div className="form-grid">{kinds.map(kind => <Field key={kind} label={`Category for ${kindLabel[kind].toLowerCase()}s`}>
      <select value={categoryByKind[kind] ?? ''} onChange={e => onCategory(kind, e.target.value)}>
        <option value="">Choose a category…</option>
        {assetCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
    </Field>)}</div>}
    <div className="modal-actions"><button type="button" className="primary" disabled={busy || !broker.trim()} onClick={onContinue}>{busy ? 'Matching…' : 'Continue'}</button></div>
  </section>
}

type HoldingRow = { row: ExtractedHolding; index: number }
type TxnRow = { row: ExtractedTransaction; index: number }

export function HoldingReviewList({ rows, conflicts, onOpen, onSkip }: Readonly<{
  rows: HoldingRow[]; conflicts: Record<number, string | null>; onOpen: (index: number) => void; onSkip: (index: number) => void
}>) {
  if (rows.length === 0) return null
  return <>
    <div className="panel-heading"><h3>Holdings to review</h3><span>{rows.length}</span></div>
    <div className="mapped-holdings">{rows.map(({ row, index }) => <div key={`h:${row.isin ?? row.instrumentName}:${row.depository ?? ''}`} className="mapped-holding status-new">
      <button type="button" className="mapped-holding-name" onClick={() => onOpen(index)}>
        <strong title={row.instrumentName}>{row.instrumentName}</strong>
        <small>{[row.kind ? kindLabel[row.kind] : null, row.isin, row.tickerSymbol, row.depository && row.depository !== 'UNKNOWN' ? row.depository : null].filter(Boolean).join(' · ')}</small>
        {conflicts[index] && <small className="conflict-note">Conflict: {conflicts[index]}</small>}
      </button>
      <div className="mapped-holding-value"><span>{row.kind === 'MUTUAL_FUND' ? 'Units' : 'Qty'} {row.quantity}</span>
        <small><button type="button" className="text-link" onClick={() => onSkip(index)}>Skip</button></small>
      </div>
    </div>)}</div>
  </>
}

export function TransactionReviewList({ rows, conflicts, onOpen, onSkip }: Readonly<{
  rows: TxnRow[]; conflicts: Record<number, string | null>; onOpen: (index: number) => void; onSkip: (index: number) => void
}>) {
  if (rows.length === 0) return null
  return <>
    <div className="panel-heading"><h3>Transactions to review</h3><span>{rows.length}</span></div>
    <div className="mapped-holdings">{rows.map(({ row, index }) => <div key={`t:${row.isin}:${row.date}:${index}`} className="mapped-holding status-new">
      <button type="button" className="mapped-holding-name" onClick={() => onOpen(index)}>
        <strong title={row.instrumentName}>{row.instrumentName}</strong>
        <small>{row.date} · {row.type}{row.isin ? ` · ${row.isin}` : ''}</small>
        {conflicts[index] && <small className="conflict-note">Conflict: {conflicts[index]}</small>}
      </button>
      <div className="mapped-holding-value">{row.amount != null && <span>{row.amount}</span>}
        <small><button type="button" className="text-link" onClick={() => onSkip(index)}>Skip</button></small>
      </div>
    </div>)}</div>
  </>
}

export function MissingHoldingsList({ rows, broker, onSell, onDelete, onKeep }: Readonly<{
  rows: HoldingToRemove[]; broker: string
  onSell: (row: HoldingToRemove) => void; onDelete: (row: HoldingToRemove) => void; onKeep: (row: HoldingToRemove) => void
}>) {
  if (rows.length === 0) return null
  return <>
    <div className="panel-heading"><h3>Missing from this statement</h3><span>{rows.length}</span></div>
    <p className="hint">You hold these at {broker}, but this holdings statement doesn't list them — most likely you sold them. Log the SELL, or keep or delete the holding.</p>
    <div className="mapped-holdings">{rows.map(r => <div key={r.holdingId} className="mapped-holding status-remove">
      <div className="mapped-holding-name"><strong title={r.name}>{r.name}</strong><small>{r.broker || 'Unassigned broker'}{r.quantity != null ? ` · ${r.quantity} held` : ''}</small></div>
      <div className="mapped-holding-value missing-actions">
        <button type="button" className="outline" onClick={() => onSell(r)}>Log a SELL</button>
        <small><button type="button" className="text-link" onClick={() => onKeep(r)}>Keep</button> · <button type="button" className="text-link" onClick={() => onDelete(r)}>Delete holding</button></small>
      </div>
    </div>)}</div>
  </>
}

export function ReviewStep({ result, brokerName, holdingConflicts, txnConflicts, loggableCount, skippedCount, pendingHoldingRows, pendingTxnRows, pendingMissing,
  confirmingLogAll, setConfirmingLogAll, loggingAll, logAllNote, deleteCount, onLogAll, onChangeBroker, onOpenHolding, onOpenTxn, onSell, onResolve, onDelete }: Readonly<{
  result: StatementExtractionResult; brokerName: string
  holdingConflicts: Record<number, string | null>; txnConflicts: Record<number, string | null>
  loggableCount: number; skippedCount: number
  pendingHoldingRows: HoldingRow[]; pendingTxnRows: TxnRow[]; pendingMissing: HoldingToRemove[]
  confirmingLogAll: boolean; setConfirmingLogAll: (confirming: boolean) => void; loggingAll: boolean; logAllNote: string; deleteCount: number
  onLogAll: () => void; onChangeBroker: () => void; onOpenHolding: (index: number) => void; onOpenTxn: (index: number) => void
  onSell: (row: HoldingToRemove) => void; onResolve: (key: string) => void; onDelete: (row: HoldingToRemove) => void
}>) {
  const matchedHoldingRows = result.holdings.filter(row => row.matchedHoldingId)
  const nothingExtracted = result.holdings.length === 0 && result.toRemove.length === 0 && result.transactions.length === 0
  return <>
    <p className="hint">Matched against your holdings at <strong>{brokerName}</strong>{result.statementDate ? ` · statement as of ${result.statementDate}` : ''}. <button type="button" className="text-link" onClick={onChangeBroker}>Change broker or type</button></p>
    {nothingExtracted && <p className="hint">{result.warnings.length > 0
      ? 'We read this statement but couldn\'t turn any of it into holdings or transactions — see the notes below for why.'
      : 'Nothing we could extract from this statement. Make sure it\'s a holdings or transaction statement (not a P&L or tax report).'}</p>}

    {loggableCount + skippedCount > 0 && <LogAllBar loggableCount={loggableCount} skippedCount={skippedCount} confirming={confirmingLogAll}
      setConfirming={setConfirmingLogAll} running={loggingAll} onConfirm={onLogAll} />}
    {logAllNote && <p className="hint">{logAllNote}</p>}

    <HoldingReviewList rows={pendingHoldingRows} conflicts={holdingConflicts} onOpen={onOpenHolding} onSkip={index => onResolve(`h${index}`)} />
    <TransactionReviewList rows={pendingTxnRows} conflicts={txnConflicts} onOpen={onOpenTxn} onSkip={index => onResolve(`t${index}`)} />
    <MissingHoldingsList rows={pendingMissing} broker={brokerName} onSell={onSell} onKeep={r => onResolve(`m${r.holdingId}`)} onDelete={onDelete} />
    {deleteCount > 0 && <p className="hint">{deleteCount} holding{deleteCount === 1 ? '' : 's'} will be deleted when you click Done.</p>}

    {matchedHoldingRows.length > 0 && <>
      <div className="panel-heading"><h3>Successfully parsed and mapped</h3><span>{matchedHoldingRows.length}</span></div>
      <div className="mapped-holdings">{matchedHoldingRows.map(row => <div key={`m:${row.isin ?? row.instrumentName}:${row.depository ?? ''}`} className="mapped-holding status-matched">
        <div className="mapped-holding-name"><strong title={row.instrumentName}>{row.instrumentName}</strong><small>{row.isin ? `${row.isin} · ` : ''}already in your portfolio</small></div>
        <div className="mapped-holding-value"><span>{row.kind === 'MUTUAL_FUND' ? 'Units' : 'Qty'} {row.quantity}</span></div>
      </div>)}</div>
    </>}

    {result.warnings.length > 0 && <section className="import-summary">
      <strong>{result.warnings.length} note{result.warnings.length === 1 ? '' : 's'} from the extraction</strong>
      {result.warnings.map(w => <span key={w}>{w}</span>)}
    </section>}
  </>
}

// "Log all" saves every row that has no conflict. Clicking it first asks for confirmation, naming
// how many conflicted rows will be skipped, so nothing is silently left behind.
function LogAllBar({ loggableCount, skippedCount, confirming, setConfirming, running, onConfirm }: Readonly<{
  loggableCount: number; skippedCount: number; confirming: boolean; setConfirming: (confirming: boolean) => void; running: boolean; onConfirm: () => void
}>) {
  const plural = (n: number) => n === 1 ? '' : 's'
  if (confirming) {
    const pronoun = skippedCount === 1 ? 'it' : 'them'
    const skipNote = skippedCount === 0 ? '' : ` ${skippedCount} row${plural(skippedCount)} with conflicts will be skipped — review ${pronoun} by hand.`
    return <div className="import-log-all">
      <p className="hint">Log {loggableCount} row{plural(loggableCount)} now?{skipNote}</p>
      <button type="button" className="primary" onClick={onConfirm}>Yes, log {loggableCount}</button>
      <button type="button" className="outline" onClick={() => setConfirming(false)}>Cancel</button>
    </div>
  }
  const skipNote = skippedCount === 0 ? '' : `; ${skippedCount} with conflicts will be skipped`
  return <div className="import-log-all">
    <p className="hint">{loggableCount} row{plural(loggableCount)} ready with no conflicts{skipNote}.</p>
    <button type="button" className="primary" disabled={loggableCount === 0 || running} onClick={() => setConfirming(true)}>{running ? 'Logging…' : `Log all (${loggableCount})`}</button>
  </div>
}

function pickLabel(reading: boolean, file: File | null): string {
  if (reading) return 'Reading…'
  if (file) return 'Choose a different file'
  return '↑ Choose a statement'
}

export function UploadStep({ busy, reading, file, password, onPassword, error, onPick, onRetry }: Readonly<{
  busy: boolean; reading: boolean; file: File | null; password: string; onPassword: (password: string) => void
  error: string; onPick: (file: File) => void; onRetry: () => void
}>) {
  return <>
    <p className="hint">Upload a CAS statement, or a holding or transaction statement from any broker or investing platform — PDF, XLSX/XLS, CSV/TSV, or XML, up to 15MB for a PDF or 10MB (max 10,000 rows) for a spreadsheet. Nothing is saved or deleted automatically — review what's below, then click Done to apply it.</p>

    <Field label="Statement password"><input type="password" autoComplete="off" value={password} disabled={busy} onChange={e => onPassword(e.target.value)} placeholder="Only needed for a password-protected PDF (usually your PAN, e.g. ABCDE1234F)" /></Field>

    <div className="import-upload statement-upload">
      <label className="outline file-label">{pickLabel(reading, file)}
        <input type="file" accept=".pdf,.csv,.tsv,.xml,.xlsx,.xls,application/pdf,text/csv,text/tab-separated-values,text/xml,application/xml,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel" hidden disabled={busy}
          onChange={e => {
            const picked = e.target.files?.[0]
            if (picked) {
              onPick(picked)
            }
            e.target.value = ''
          }} />
      </label>
      {file && <span className="hint">{file.name}</span>}
    </div>

    {error && <p className="form-error">{error}
      {file && /password/i.test(error) && <> — update it above, then <button type="button" className="text-link" disabled={busy} onClick={onRetry}>try this file again</button>.</>}
    </p>}
  </>
}

// The pre-filled Add Holding / Log Transaction forms a row (or a SELL suggestion) opens into.
export function ReviewDialogs({ result, brokerName, categories, holdings, displayCurrency, categoryFor, reviewingHolding, reviewingTxn, reviewingSell,
  onCloseHolding, onCloseTxn, onCloseSell, onHoldingSaved, onTxnSaved, onSellSaved }: Readonly<{
  result: StatementExtractionResult; brokerName: string; categories: Category[]; holdings: Holding[]; displayCurrency: string
  categoryFor: (row: ExtractedHolding) => string
  reviewingHolding: number | null; reviewingTxn: number | null; reviewingSell: HoldingToRemove | null
  onCloseHolding: () => void; onCloseTxn: () => void; onCloseSell: () => void
  onHoldingSaved: (saved: Holding) => void; onTxnSaved: () => void; onSellSaved: () => void
}>) {
  return <>
    {reviewingHolding != null && <HoldingModal holding={null} category={null} categories={categories} holdings={holdings} displayCurrency={displayCurrency}
      draft={draftFromExtractedHolding(result.holdings[reviewingHolding], brokerName, categoryFor(result.holdings[reviewingHolding]))}
      onClose={onCloseHolding} onSaved={onHoldingSaved} />}
    {reviewingTxn != null && <TransactionModal transaction={null} holdings={holdings} displayCurrency={displayCurrency}
      draft={draftFromExtractedTransaction(result.transactions[reviewingTxn])} onClose={onCloseTxn} onSaved={onTxnSaved} />}
    {reviewingSell && <TransactionModal transaction={null} holdings={holdings} displayCurrency={displayCurrency}
      draft={sellDraft(reviewingSell, result.statementDate, brokerName)} onClose={onCloseSell} onSaved={onSellSaved} />}
  </>
}
