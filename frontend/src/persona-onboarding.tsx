import { useState } from 'react'
import { useEscToClose } from './ui'
import { submitPersona, skipPersona } from './persona-api'
import type { InstrumentType, SalaryRange } from './types'

const SALARY_OPTIONS: { value: SalaryRange; label: string }[] = [
  { value: 'UNDER_5L', label: 'Under ₹5L' },
  { value: 'L5_TO_10L', label: '₹5L – ₹10L' },
  { value: 'L10_TO_25L', label: '₹10L – ₹25L' },
  { value: 'L25_TO_50L', label: '₹25L – ₹50L' },
  { value: 'ABOVE_50L', label: 'Above ₹50L' },
  { value: 'PREFER_NOT_TO_SAY', label: 'Prefer not to say' },
]

const INSTRUMENT_OPTIONS: { value: InstrumentType; label: string }[] = [
  { value: 'INDIAN_STOCKS', label: 'Indian Stocks' },
  { value: 'FOREIGN_STOCKS', label: 'Foreign Stocks' },
  { value: 'MUTUAL_FUNDS', label: 'Mutual Funds' },
  { value: 'CRYPTO', label: 'Crypto' },
  { value: 'COMMODITIES', label: 'Commodities' },
  { value: 'FIXED_RETURN', label: 'Fixed Return Instruments' },
  { value: 'REAL_ESTATE', label: 'Real Estate' },
]

// Each option's index (0/1/2) is itself the risk score for that answer — see PersonaService's
// scoring on the backend, which sums these three and buckets the total into a risk profile.
const SCENARIOS: { question: string; options: string[] }[] = [
  {
    question: 'If your portfolio dropped 20% in a month, what would you do?',
    options: ['Sell some to limit further loss', 'Hold and wait it out', 'Buy more while prices are low'],
  },
  {
    question: "How long until you'll likely need this money?",
    options: ['Less than 2 years', '2–7 years', 'More than 7 years'],
  },
  {
    question: 'Which trade-off matters more to you?',
    options: ['Protecting what I have, even for lower returns', 'A balance of growth and safety', 'Maximising growth, even with more ups and downs'],
  },
]

const DETAILS_STEP = 1
const INSTRUMENTS_STEP = 2
const SCENARIO_START_STEP = 3
const TOTAL_STEPS = SCENARIO_START_STEP + SCENARIOS.length

// A data-collecting, skippable onboarding widget — identifies a starter persona (basic profile
// + a risk read from three scenario questions) used to seed a few starter categories. Same
// skip / "don't show again" pattern as CustomLayoutOnboarding and UserOnboarding, but "don't
// show again" here also saves a MODERATE-default persona (via skipPersona) rather than leaving
// the user with none at all — completing the full flow instead saves the real answers.
export function PersonaOnboarding({ onClose, onDismissForever }: { onClose: () => void; onDismissForever: () => void }) {
  const [step, setStep] = useState(0)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  const [age, setAge] = useState('')
  const [occupation, setOccupation] = useState('')
  const [salaryRange, setSalaryRange] = useState<SalaryRange | ''>('')
  const [instruments, setInstruments] = useState<Set<InstrumentType>>(new Set())
  const [answers, setAnswers] = useState<(number | null)[]>([null, null, null])
  const [busy, setBusy] = useState(false)
  useEscToClose(onClose)

  const isLast = step === TOTAL_STEPS - 1

  const toggleInstrument = (value: InstrumentType) => {
    setInstruments(current => {
      const next = new Set(current)
      if (next.has(value)) next.delete(value); else next.add(value)
      return next
    })
  }

  const skipNow = async () => {
    if (dontShowAgain) { onDismissForever(); await skipPersona() }
    onClose()
  }

  const finish = async () => {
    setBusy(true)
    try {
      await submitPersona({
        age: age ? Number(age) : null,
        occupation: occupation || null,
        salaryRange: salaryRange || null,
        instrumentTypes: Array.from(instruments),
        marketDropAnswer: answers[0],
        timeHorizonAnswer: answers[1],
        tradeOffAnswer: answers[2],
      })
    } catch { /* best-effort — still close so the user isn't stuck on a save failure */ }
    onDismissForever()
    onClose()
  }

  const title = step === 0 ? "Let's personalise FinSights"
    : step === DETAILS_STEP ? 'A bit about you'
    : step === INSTRUMENTS_STEP ? 'What do you invest in?'
    : `Scenario ${step - SCENARIO_START_STEP + 1} of ${SCENARIOS.length}`

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">GETTING TO KNOW YOU · STEP {step + 1} OF {TOTAL_STEPS}</p><h2>{title}</h2></div>
      <button className="close" onClick={() => void skipNow()}>×</button>
    </div>

    {step === 0 && <p>A few quick, entirely optional questions — we'll use your answers to set up a couple of starter categories and get a read on your risk comfort. Skip anytime.</p>}

    {step === DETAILS_STEP && <div className="persona-fields">
      <label>Age<input type="number" min={0} max={120} value={age} onChange={e => setAge(e.target.value)} /></label>
      <label>Occupation<input value={occupation} onChange={e => setOccupation(e.target.value)} placeholder="e.g. Software engineer" /></label>
      <label>Annual salary range
        <select value={salaryRange} onChange={e => setSalaryRange(e.target.value as SalaryRange)}>
          <option value="">Select…</option>
          {SALARY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>
    </div>}

    {step === INSTRUMENTS_STEP && <div className="persona-instruments">
      {INSTRUMENT_OPTIONS.map(o => <label key={o.value} className="persona-checkbox">
        <input type="checkbox" checked={instruments.has(o.value)} onChange={() => toggleInstrument(o.value)} />
        {o.label}
      </label>)}
    </div>}

    {step >= SCENARIO_START_STEP && <div className="persona-scenario">
      <p>{SCENARIOS[step - SCENARIO_START_STEP].question}</p>
      {SCENARIOS[step - SCENARIO_START_STEP].options.map((opt, i) => <label key={i} className="persona-checkbox">
        <input type="radio" name={`scenario-${step}`} checked={answers[step - SCENARIO_START_STEP] === i}
          onChange={() => setAnswers(a => { const next = [...a]; next[step - SCENARIO_START_STEP] = i; return next })} />
        {opt}
      </label>)}
    </div>}

    <div className="onboarding-dots">
      {Array.from({ length: TOTAL_STEPS }).map((_, i) => <span key={i} className={`onboarding-dot${i === step ? ' active' : ''}`} />)}
    </div>
    <div className="modal-actions">
      <label className="onboarding-dismiss push-start">
        <input type="checkbox" checked={dontShowAgain} onChange={e => setDontShowAgain(e.target.checked)} />
        Don't show this again
      </label>
      <button type="button" className="outline" onClick={() => void skipNow()}>Skip</button>
      <button type="button" className="primary" disabled={busy} onClick={() => isLast ? void finish() : setStep(s => s + 1)}>
        {isLast ? (busy ? 'Saving…' : 'Finish') : 'Next'}
      </button>
    </div>
  </section></div>
}
