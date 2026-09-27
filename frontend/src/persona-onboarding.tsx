import { useState } from 'react'
import { useEscToClose } from './ui'
import { submitPersona, skipPersona } from './persona-api'
import type { InstrumentType, InvestingTenure, InvestorPersona, Persona, RiskProfile, SalaryRange } from './types'

export const SALARY_OPTIONS: { value: SalaryRange; label: string }[] = [
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

// Self-identified investor persona — how the user describes themselves, not computed. A
// four-archetype framework built around age, portfolio size, and what the user is actually
// trying to do, rather than a plain experience level.
export const PERSONA_OPTIONS: { value: InvestorPersona; label: string; hint: string }[] = [
  { value: 'WEALTH_BUILDER', label: 'Wealth Builder', hint: '22–35 · Early career — automating contributions, learning the basics, long time horizon' },
  { value: 'ACTIVE_ACCUMULATOR', label: 'Active Accumulator', hint: '35–50 · Peak earning years — maximising 401(k)/IRA, outperforming the market' },
  { value: 'HIGH_NET_WORTH_TACTICIAN', label: 'Advanced Tactician', hint: '35–65 · Experienced/high earner — capital preservation, estate planning, non-correlated alpha' },
  { value: 'DEFENSIVE_CONSUMER', label: 'Defensive Wall', hint: '55+ · Pre-retirement/retirement — income yield, protecting principal, RMD planning' },
]
export const PERSONA_LABELS: Record<InvestorPersona, string> = Object.fromEntries(
  PERSONA_OPTIONS.map(o => [o.value, o.label])) as Record<InvestorPersona, string>
// Same copy as each option's onboarding hint — reused as the Settings tag's hover tooltip.
export const PERSONA_DESCRIPTIONS: Record<InvestorPersona, string> = Object.fromEntries(
  PERSONA_OPTIONS.map(o => [o.value, o.hint])) as Record<InvestorPersona, string>

export const TENURE_OPTIONS: { value: InvestingTenure; label: string }[] = [
  { value: 'UNDER_1_YEAR', label: 'Less than a year' },
  { value: 'ONE_TO_3_YEARS', label: '1 – 3 years' },
  { value: 'THREE_TO_10_YEARS', label: '3 – 10 years' },
  { value: 'OVER_10_YEARS', label: 'More than 10 years' },
]
export const TENURE_LABELS: Record<InvestingTenure, string> = {
  UNDER_1_YEAR: 'Less than a year', ONE_TO_3_YEARS: '1 – 3 years',
  THREE_TO_10_YEARS: '3 – 10 years', OVER_10_YEARS: 'More than 10 years',
}

// RiskProfile is computed server-side (see PersonaService.scoreRisk) from the scenario answers
// below — the backend's enum constants are stable identifiers; these are just friendlier labels.
export const RISK_LABELS: Record<RiskProfile, string> = {
  CONSERVATIVE: 'Long-term investor', MODERATE: 'Swing trader', AGGRESSIVE: 'High growth trader',
}
// Reused as the Settings tag's hover tooltip.
export const RISK_DESCRIPTIONS: Record<RiskProfile, string> = {
  CONSERVATIVE: 'Prioritises protecting capital, even for lower returns.',
  MODERATE: 'Balances growth and safety for a moderate risk-reward profile.',
  AGGRESSIVE: 'Seeks aggressive capital growth despite bigger swings.',
}
// Allocation guideline shown alongside the risk profile — not enforced anywhere, purely
// informational. MODERATE is the framework's default (see the pre-selected scenario answers
// below and PersonaService.skip on the backend), not just its midpoint.
export const RISK_ALLOCATION: Record<RiskProfile, { equity: string; debtCash: string; coreFocus: string }> = {
  CONSERVATIVE: { equity: '0% – 20%', debtCash: '80% – 100%', coreFocus: 'Capital preservation' },
  MODERATE: { equity: '40% – 50%', debtCash: '50% – 60%', coreFocus: 'Balanced growth' },
  AGGRESSIVE: { equity: '70% – 90%', debtCash: '10% – 30%', coreFocus: 'Long-term wealth' },
}
export const SALARY_LABELS: Record<SalaryRange, string> = Object.fromEntries(
  SALARY_OPTIONS.map(o => [o.value, o.label])) as Record<SalaryRange, string>

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
// through without touching a scenario question still ends up "moderate" on that question.
const MODERATE_ANSWERS = SCENARIOS.map(s => Math.floor((s.options.length - 1) / 2))

const DETAILS_STEP = 1
const PERSONA_STEP = 2
const INSTRUMENTS_STEP = 3
const SCENARIO_START_STEP = 4
const TOTAL_STEPS = SCENARIO_START_STEP + SCENARIOS.length

// A data-collecting, skippable onboarding widget — identifies a starter persona (basic profile,
// a self-identified investor archetype, and a risk read from five scenario questions) used to
// seed a few starter categories. Same skip / "don't show again" pattern as CustomLayoutOnboarding
// and UserOnboarding, but "don't show again" here also saves a MODERATE-default persona (via
// skipPersona) rather than leaving the user with none at all — completing the full flow instead
// saves the real answers.
//
// mode="risk-only" (Settings' "Reassess risk profile") skips straight to the five scenario
// questions — age/occupation/salary/persona/instruments stay exactly as they were (the state
// below is still seeded from `initial`, just never edited in this mode), so re-submitting only
// ever changes the computed risk profile. No "don't show again" checkbox (nothing to dismiss —
// onboarding is already long done), and the close/Skip button is a plain Cancel.
export function PersonaOnboarding({ onClose, onDismissForever, initial, mode = 'onboarding' }: {
  onClose: () => void; onDismissForever: () => void; initial?: Persona | null; mode?: 'onboarding' | 'risk-only'
}) {
  const isRiskOnly = mode === 'risk-only'
  const startStep = isRiskOnly ? SCENARIO_START_STEP : 0
  const [step, setStep] = useState(startStep)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  const [age, setAge] = useState(initial?.age ? String(initial.age) : '')
  const [occupation, setOccupation] = useState(initial?.occupation ?? '')
  const [salaryRange, setSalaryRange] = useState<SalaryRange | ''>(initial?.salaryRange ?? '')
  const [investorPersona, setInvestorPersona] = useState<InvestorPersona | ''>(initial?.investorPersona ?? '')
  const [investingTenure, setInvestingTenure] = useState<InvestingTenure | ''>(initial?.investingTenure ?? '')
  const [instruments, setInstruments] = useState<Set<InstrumentType>>(new Set(initial?.instrumentTypes ?? []))
  // Pre-selected to each question's moderate option — matches the backend's own default (a
  // missing answer normalizes to "moderate", and skipping saves MODERATE outright), so a user
  // who clicks straight through without changing anything ends up with the same result either
  // way, rather than an implicit "most conservative" default from an all-null start.
  const [answers, setAnswers] = useState<number[]>(MODERATE_ANSWERS)
  const [busy, setBusy] = useState(false)
  useEscToClose(onClose)

  const isLast = step === TOTAL_STEPS - 1
  const canGoBack = step > startStep

  const toggleInstrument = (value: InstrumentType) => {
    setInstruments(current => {
      const next = new Set(current)
      if (next.has(value)) next.delete(value); else next.add(value)
      return next
    })
  }

  const skipNow = async () => {
    if (isRiskOnly) { onClose(); return }
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
        investorPersona: investorPersona || null,
        investingTenure: investingTenure || null,
        instrumentTypes: Array.from(instruments),
        timeHorizonAnswer: answers[0],
        riskCapacityAnswer: answers[1],
        riskToleranceAnswer: answers[2],
        investmentObjectivesAnswer: answers[3],
        liquidityNeedsAnswer: answers[4],
      })
    } catch { /* best-effort — still close so the user isn't stuck on a save failure */ }
    if (!isRiskOnly) onDismissForever()
    onClose()
  }

  const title = step === 0 ? "Let's personalise FinSights"
    : step === DETAILS_STEP ? 'A bit about you'
    : step === PERSONA_STEP ? 'Which investor profile fits you best?'
    : step === INSTRUMENTS_STEP ? 'What do you invest in?'
    : SCENARIOS[step - SCENARIO_START_STEP].area

  // Risk-only mode only ever shows the five scenario steps — number them 1-5 on their own,
  // rather than as steps 5-9 of a nine-step flow the user never sees the rest of.
  const displayStepNumber = isRiskOnly ? step - SCENARIO_START_STEP + 1 : step + 1
  const displayTotalSteps = isRiskOnly ? SCENARIOS.length : TOTAL_STEPS

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">{isRiskOnly ? 'REASSESS RISK PROFILE' : 'GETTING TO KNOW YOU'} · STEP {displayStepNumber} OF {displayTotalSteps}</p><h2>{title}</h2></div>
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

    {step === PERSONA_STEP && <div className="persona-experience">
      <p className="hint">Which of these best describes you?</p>
      <div className="persona-radio-cards">
        {PERSONA_OPTIONS.map(o => <label key={o.value} className="persona-radio-card">
          <input type="radio" name="investor-persona" checked={investorPersona === o.value} onChange={() => setInvestorPersona(o.value)} />
          <span><b>{o.label}</b><small>{o.hint}</small></span>
        </label>)}
      </div>
      <div className="persona-fields">
        <label>How long have you been actively investing?
          <select value={investingTenure} onChange={e => setInvestingTenure(e.target.value as InvestingTenure)}>
            <option value="">Select…</option>
            {TENURE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      </div>
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
      {Array.from({ length: displayTotalSteps }).map((_, i) => <span key={i} className={`onboarding-dot${i === displayStepNumber - 1 ? ' active' : ''}`} />)}
    </div>
    <div className="modal-actions">
      {!isRiskOnly && <label className="onboarding-dismiss push-start">
        <input type="checkbox" checked={dontShowAgain} onChange={e => setDontShowAgain(e.target.checked)} />
        Don't show this again
      </label>}
      <button type="button" className={isRiskOnly ? 'outline push-start' : 'outline'} onClick={() => void skipNow()}>{isRiskOnly ? 'Cancel' : 'Skip'}</button>
      {canGoBack && <button type="button" className="outline" onClick={() => setStep(s => s - 1)}>Back</button>}
      <button type="button" className="primary" disabled={busy} onClick={() => isLast ? void finish() : setStep(s => s + 1)}>
        {isLast ? (busy ? 'Saving…' : 'Finish') : 'Next'}
      </button>
    </div>
  </section></div>
}
