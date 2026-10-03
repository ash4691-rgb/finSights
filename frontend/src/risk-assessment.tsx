import { useState } from 'react'
import { useEscToClose } from './ui'
import { updateRisk } from './persona-api'

// Five areas from the risk-assessment framework, each with its own number of options (3 to 5) —
// an option's index is the raw answer sent to the backend; PersonaService.scoreRisk rescales
// each question against its own option count before combining them into a risk profile, so a
// 5-option question doesn't quietly outweigh a 3-option one.
const SCENARIOS: { area: string; question: string; options: string[] }[] = [
  {
    area: 'Time Horizon',
    question: 'When do you expect to start withdrawing a major portion of your investments?',
    options: ['Under 3 years', '3–5 years', '6–10 years', 'More than 10 years'],
  },
  {
    area: 'Risk Capacity',
    question: 'Over the next few years, how do you expect your annual income to change?',
    options: ['Decrease substantially', 'Decrease moderately', 'Stay the same', 'Grow moderately', 'Grow substantially'],
  },
  {
    area: 'Risk Tolerance',
    question: 'If the performance of your investment dropped by 20% over a short period, how would you feel?',
    options: ['Highly panicked and sell immediately', 'Uneasy but hold', 'View it as an opportunity to buy more'],
  },
  {
    area: 'Investment Objectives',
    question: 'Which statement best describes your overall investment philosophy?',
    options: ['Seeking stable, low-risk capital preservation', 'Balancing moderate growth and safety', 'Seeking high/aggressive capital growth despite major fluctuations'],
  },
  {
    area: 'Liquidity Needs',
    question: 'How much of your total portfolio might you need to access in an emergency within the next 12 months?',
    options: ['None', 'Less than 10%', '10%–30%', 'More than 30%'],
  },
]
// The middle-normalizing index for each question's own option count (matches
// PersonaService.normalize's rounding) — pre-selecting it means a user who clicks straight
// through without touching a question still ends up "moderate" on it.
const MODERATE_ANSWERS = SCENARIOS.map(s => Math.floor((s.options.length - 1) / 2))

// The five-question risk assessment, kept separate from PersonaOnboarding — it's prompted on a
// later login once the basic persona questionnaire is done (see App.tsx's load(), gated on
// settings.riskOnboardingDismissed), not chained into the same sitting, and it's also reused
// as-is for Settings' "Reassess risk profile". Cancelling never persists a dismissal — it simply
// prompts again next login/visit, same as PersonaOnboarding's plain Skip.
export function RiskAssessment({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<number[]>(MODERATE_ANSWERS)
  const [busy, setBusy] = useState(false)
  useEscToClose(onClose)

  const isLast = step === SCENARIOS.length - 1
  const scenario = SCENARIOS[step]

  const finish = async () => {
    setBusy(true)
    try {
      await updateRisk({
        timeHorizonAnswer: answers[0], riskCapacityAnswer: answers[1], riskToleranceAnswer: answers[2],
        investmentObjectivesAnswer: answers[3], liquidityNeedsAnswer: answers[4],
      })
    } catch { /* best-effort — still close so the user isn't stuck on a save failure */ }
    onDone()
  }

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">RISK PROFILE · STEP {step + 1} OF {SCENARIOS.length}</p><h2>{scenario.area}</h2></div>
      <button className="close" onClick={onClose}>×</button>
    </div>

    <div className="persona-scenario">
      <p>{scenario.question}</p>
      {scenario.options.map((opt, i) => <label key={i} className="persona-checkbox">
        <input type="radio" name={`risk-scenario-${step}`} checked={answers[step] === i}
          onChange={() => setAnswers(a => { const next = [...a]; next[step] = i; return next })} />
        {opt}
      </label>)}
    </div>

    <div className="onboarding-dots">
      {SCENARIOS.map((_, i) => <span key={i} className={`onboarding-dot${i === step ? ' active' : ''}`} />)}
    </div>
    <div className="modal-actions">
      <button type="button" className="outline push-start" onClick={onClose}>Cancel</button>
      {step > 0 && <button type="button" className="outline" onClick={() => setStep(s => s - 1)}>Back</button>}
      <button type="button" className="primary" disabled={busy} onClick={() => isLast ? void finish() : setStep(s => s + 1)}>
        {isLast ? (busy ? 'Saving…' : 'Finish') : 'Next'}
      </button>
    </div>
  </section></div>
}
