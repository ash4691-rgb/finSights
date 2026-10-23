import { useState } from 'react'
import { api } from '../api'
import { ReviewDialogs, ReviewStep } from './StatementImportSteps'
import { derivePending, kindOf, logAllRows } from './statementImportLogic'
import type { Category, ExtractedHolding, Holding, HoldingToRemove, InstrumentKind, StatementExtractionResult } from '../types'

// The review step of a statement import: owns everything that happens after the rows are matched —
// what's been saved or skipped, "Log all", the per-row forms, and applying deletions on Done.
export function StatementReview({ result, brokerName, categoryByKind, categories, holdings, displayCurrency, onChangeBroker, onClose, onSaved, onRemoved, onTransactionSaved }: {
  result: StatementExtractionResult; brokerName: string; categoryByKind: Partial<Record<InstrumentKind, string>>
  categories: Category[]; holdings: Holding[]; displayCurrency: string
  onChangeBroker: () => void; onClose: () => void
  onSaved: (saved: Holding) => void; onRemoved: (holdingIds: string[]) => void; onTransactionSaved: () => void
}) {
  // Keys (prefixed `h` holdings, `t` transactions, `m` missing holdings by id) that have been
  // saved, skipped, or otherwise dealt with — once every row is in here, Done unlocks. A matched
  // holding row needs no review (it's already in the portfolio) so it's never in this set at all.
  const [resolved, setResolved] = useState<Set<string>>(new Set())
  const [deleteIds, setDeleteIds] = useState<Set<string>>(new Set())
  const [reviewingHolding, setReviewingHolding] = useState<number | null>(null)
  const [reviewingTxn, setReviewingTxn] = useState<number | null>(null)
  const [reviewingSell, setReviewingSell] = useState<HoldingToRemove | null>(null)
  const [finishing, setFinishing] = useState(false)
  const [confirmingLogAll, setConfirmingLogAll] = useState(false)
  const [loggingAll, setLoggingAll] = useState(false)
  const [logAllNote, setLogAllNote] = useState('')
  const [error, setError] = useState('')

  const resolve = (key: string) => setResolved(current => new Set(current).add(key))
  const categoryFor = (row: ExtractedHolding) => categoryByKind[kindOf(row)] ?? ''
  const pending = derivePending(result, resolved, categoryFor, brokerName, holdings)
  const canFinish = pending.pendingCount === 0 && !finishing && !loggingAll

  const logAll = async () => {
    setLoggingAll(true); setConfirmingLogAll(false)
    const outcome = await logAllRows(pending.loggable, brokerName, categoryFor, displayCurrency, onSaved)
    if (pending.loggable.transactions.length > 0) onTransactionSaved()
    setResolved(current => new Set([...current, ...outcome.savedKeys]))
    setLogAllNote(outcome.note)
    setLoggingAll(false)
  }

  const finish = async () => {
    if (!canFinish) return
    setFinishing(true); setError('')
    try {
      const ids = [...deleteIds]
      for (const id of ids) await api(`/api/holdings/${id}`, { method: 'DELETE' })
      onRemoved(ids)
      onClose()
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not finish removing the holdings flagged above') }
    finally { setFinishing(false) }
  }

  const markForDeletion = (row: HoldingToRemove) => { setDeleteIds(current => new Set(current).add(row.holdingId)); resolve(`m${row.holdingId}`) }

  return <>
    {error && <p className="form-error">{error}</p>}
    <ReviewStep result={result} brokerName={brokerName}
      holdingConflicts={pending.holdingConflicts} txnConflicts={pending.txnConflicts} loggableCount={pending.loggableCount} skippedCount={pending.skippedCount}
      pendingHoldingRows={pending.pendingHoldingRows} pendingTxnRows={pending.pendingTxnRows} pendingMissing={pending.pendingMissing}
      confirmingLogAll={confirmingLogAll} setConfirmingLogAll={setConfirmingLogAll} loggingAll={loggingAll} logAllNote={logAllNote} deleteCount={deleteIds.size}
      onLogAll={() => void logAll()} onChangeBroker={onChangeBroker}
      onOpenHolding={setReviewingHolding} onOpenTxn={setReviewingTxn} onSell={setReviewingSell} onResolve={resolve} onDelete={markForDeletion} />

    <div className="modal-actions">
      {pending.pendingCount > 0 && <p className="hint push-start">
        {pending.pendingCount} row{pending.pendingCount === 1 ? '' : 's'} still need{pending.pendingCount === 1 ? 's' : ''} review before you can finish.
      </p>}
      <button type="button" className="outline" disabled={!canFinish} title={canFinish ? '' : 'Review or skip every pending row first'} onClick={() => void finish()}>{finishing ? 'Finishing…' : 'Done'}</button>
    </div>

    <ReviewDialogs result={result} brokerName={brokerName} categories={categories} holdings={holdings} displayCurrency={displayCurrency}
      categoryFor={categoryFor} reviewingHolding={reviewingHolding} reviewingTxn={reviewingTxn} reviewingSell={reviewingSell}
      onCloseHolding={() => setReviewingHolding(null)} onCloseTxn={() => setReviewingTxn(null)} onCloseSell={() => setReviewingSell(null)}
      onHoldingSaved={saved => { onSaved(saved); resolve(`h${reviewingHolding}`); setReviewingHolding(null) }}
      onTxnSaved={() => { onTransactionSaved(); resolve(`t${reviewingTxn}`); setReviewingTxn(null) }}
      onSellSaved={() => { onTransactionSaved(); if (reviewingSell) resolve(`m${reviewingSell.holdingId}`); setReviewingSell(null) }} />
  </>
}
