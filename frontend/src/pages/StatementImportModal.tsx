import { useMemo, useState } from 'react'
import { useEscToClose } from '../ui'
import { ConfirmStep, UploadStep } from './StatementImportSteps'
import { StatementReview } from './StatementReview'
import { guessCategoryId, kindsPresent, readStatement, reconcileStatement } from './statementImportLogic'
import type { Category, Holding, InstrumentKind, StatementExtractionResult, StatementType } from '../types'

// ---------------------------------------------------------------------------
// Statement import — one upload box for a CAS statement, a broker holding statement, or a broker
// transaction statement (PDF, XLSX/XLS, or a machine-readable CSV/TSV/XML), in three steps:
//   1. upload — the file is read once; the model reports what kind of statement it is and which
//      broker it came from, if the statement says so.
//   2. confirm — the user confirms (or corrects) the statement type and broker, and picks a
//      category per kind of instrument. Rows are only ever matched against that one broker's
//      holdings: the same fund or stock can be held at several brokers.
//   3. review (see StatementReview) — each row is reviewed one at a time through the ordinary Add
//      Holding / Log Transaction form (pre-filled, not auto-saved), or every conflict-free row is
//      saved at once with "Log all". Holdings the statement no longer lists are offered as a SELL.
// Nothing is written until the user acts — see the backend's StatementImportService for why.
// ---------------------------------------------------------------------------

type Stage = 'upload' | 'confirm' | 'review'

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
  const [stage, setStage] = useState<Stage>('upload')
  const [busy, setBusy] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [extracted, setExtracted] = useState<StatementExtractionResult | null>(null)
  const [statementType, setStatementType] = useState<StatementType>('HOLDINGS')
  const [broker, setBroker] = useState('')
  const [categoryByKind, setCategoryByKind] = useState<Partial<Record<InstrumentKind, string>>>({})
  const [result, setResult] = useState<StatementExtractionResult | null>(null)

  const brokerSuggestions = useMemo(() => [...new Set(holdings.map(h => h.broker).filter((b): b is string => !!b))].sort(), [holdings])

  // Takes the password as an argument rather than closing over state — a "retry with this
  // password" click needs to send what was *just* typed, before React has necessarily re-rendered
  // with it, and reusing the already-picked file this way means a wrong password doesn't force
  // the user back to their file browser to pick the same statement again.
  const upload = async (pickedFile: File, pdfPassword: string) => {
    setBusy(true); setError(''); setResult(null); setExtracted(null); setStage('upload'); setFile(pickedFile)
    try {
      const parsed = await readStatement(pickedFile, pdfPassword)
      setExtracted(parsed); setStatementType(parsed.statementType); setBroker(parsed.broker ?? '')
      setCategoryByKind(Object.fromEntries(kindsPresent(parsed.holdings).map(kind => [kind, guessCategoryId(kind, categories)])))
      setStage('confirm')
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not read that statement') }
    finally { setBusy(false) }
  }

  const reconcile = async () => {
    if (!extracted) return
    setBusy(true); setError('')
    try {
      setResult(await reconcileStatement(extracted, statementType, broker.trim()))
      setStage('review')
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not match that statement') }
    finally { setBusy(false) }
  }

  return <div className="modal-backdrop"><section className="modal" onClick={e => e.stopPropagation()}>
    <div className="modal-header"><div><p className="eyebrow">BULK IMPORT</p><h2>Upload a statement</h2></div><button className="close" onClick={onClose}>×</button></div>
    <UploadStep busy={busy} reading={busy && stage === 'upload'} file={file} password={password} onPassword={setPassword} error={error}
      onPick={f => void upload(f, password)} onRetry={() => file && void upload(file, password)} />

    {stage === 'confirm' && extracted && <ConfirmStep extracted={extracted} statementType={statementType} onStatementType={setStatementType}
      broker={broker} onBroker={setBroker} brokerSuggestions={brokerSuggestions} kinds={kindsPresent(extracted.holdings)} categories={categories}
      categoryByKind={categoryByKind} onCategory={(kind, id) => setCategoryByKind(current => ({ ...current, [kind]: id }))}
      busy={busy} onContinue={() => void reconcile()} />}

    {stage === 'review' && result
      ? <StatementReview result={result} brokerName={broker.trim()} categoryByKind={categoryByKind} categories={categories} holdings={holdings}
          displayCurrency={displayCurrency} onChangeBroker={() => { setResult(null); setStage('confirm') }}
          onClose={onClose} onSaved={onSaved} onRemoved={onRemoved} onTransactionSaved={onTransactionSaved} />
      : <div className="modal-actions"><button type="button" className="outline" disabled={stage === 'confirm'} onClick={onClose}>Done</button></div>}
  </section></div>
}
