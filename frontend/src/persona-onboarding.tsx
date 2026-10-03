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

// A few common Indian brokers/platforms to one-click add on the Platforms step — anything else
// is free text, since brokers are an open-ended set (Holding.broker is likewise a plain string,
// not an enum).
const PLATFORM_SUGGESTIONS = [
  'Zerodha', 'Groww', 'Upstox', 'ICICI Direct', 'HDFC Securities', 'Angel One', 'Paytm Money', 'INDmoney',
]

// Self-identified investor persona — how the user describes themselves, not computed. A
// four-archetype framework built around age, portfolio size, and what the user is actually
// trying to do, rather than a plain experience level.
export const PERSONA_OPTIONS: { value: InvestorPersona; label: string; icon: string; hint: string }[] = [
  { value: 'WEALTH_BUILDER', label: 'Wealth Builder', icon: '🙂', hint: '22–35 · Early career — automating contributions, learning the basics, long time horizon' },
  { value: 'ACTIVE_ACCUMULATOR', label: 'Active Accumulator', icon: '🧑‍💼', hint: '35–50 · Peak earning years — maximising 401(k)/IRA, outperforming the market' },
  { value: 'HIGH_NET_WORTH_TACTICIAN', label: 'Advanced Tactician', icon: '🧐', hint: '35–65 · Experienced/high earner — capital preservation, estate planning, non-correlated alpha' },
  { value: 'DEFENSIVE_CONSUMER', label: 'Defensive Wall', icon: '🧓', hint: '55+ · Pre-retirement/retirement — income yield, protecting principal, RMD planning' },
]
export const PERSONA_LABELS: Record<InvestorPersona, string> = Object.fromEntries(
  PERSONA_OPTIONS.map(o => [o.value, o.label])) as Record<InvestorPersona, string>
// A face/person emoji standing in for each archetype — shown on the Settings tag.
export const PERSONA_ICONS: Record<InvestorPersona, string> = Object.fromEntries(
  PERSONA_OPTIONS.map(o => [o.value, o.icon])) as Record<InvestorPersona, string>
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

// RiskProfile is computed server-side (see PersonaService.scoreRisk) from RiskAssessment's
// scenario answers — the backend's enum constants are stable identifiers; these are just
// friendlier labels, used here (not in RiskAssessment) since Settings is the main consumer.
export const RISK_LABELS: Record<RiskProfile, string> = {
  CONSERVATIVE: 'Long-term investor', MODERATE: 'Swing trader', AGGRESSIVE: 'High growth trader',
}
// Reused as the Settings tag's hover tooltip.
export const RISK_DESCRIPTIONS: Record<RiskProfile, string> = {
  CONSERVATIVE: 'Prioritises protecting capital, even for lower returns.',
  MODERATE: 'Balances growth and safety for a moderate risk-reward profile.',
  AGGRESSIVE: 'Seeks aggressive capital growth despite bigger swings.',
}
// Traffic-light colour coding for the Settings risk tag — conservative reads as "safe" (green),
// aggressive as "hot" (red), moderate in between (amber).
export const RISK_TAG_CLASS: Record<RiskProfile, string> = {
  CONSERVATIVE: 'profile-tag-conservative', MODERATE: 'profile-tag-moderate', AGGRESSIVE: 'profile-tag-aggressive',
}
// Allocation guideline shown alongside the risk profile — not enforced anywhere, purely
// informational. MODERATE is the framework's default (see RiskAssessment's pre-selected answers
// and PersonaService.skip on the backend), not just its midpoint.
export const RISK_ALLOCATION: Record<RiskProfile, { equity: string; debtCash: string; coreFocus: string }> = {
  CONSERVATIVE: { equity: '0% – 20%', debtCash: '80% – 100%', coreFocus: 'Capital preservation' },
  MODERATE: { equity: '40% – 50%', debtCash: '50% – 60%', coreFocus: 'Balanced growth' },
  AGGRESSIVE: { equity: '70% – 90%', debtCash: '10% – 30%', coreFocus: 'Long-term wealth' },
}
export const SALARY_LABELS: Record<SalaryRange, string> = Object.fromEntries(
  SALARY_OPTIONS.map(o => [o.value, o.label])) as Record<SalaryRange, string>

// Persona (the hook) comes first; categories and platforms follow; the optional, more sensitive
// demographic questions are deferred to the end, where skipping them costs nothing — Finish
// works with or without them, and they're always editable later from Settings. No risk questions
// here at all — those are a separate RiskAssessment prompted on a later login (see App.tsx).
const PERSONA_STEP = 1
const CATEGORIES_STEP = 2
const PLATFORMS_STEP = 3
const DETAILS_STEP = 4
const TOTAL_STEPS = DETAILS_STEP + 1

// A data-collecting, skippable onboarding widget — identifies a starter persona (basic profile,
// a self-identified investor archetype, the instrument categories the user already holds or
// wants to, and the platforms they use) used to seed a few starter categories. Same skip /
// "don't show again" pattern as CustomLayoutOnboarding and UserOnboarding, but "don't show
// again" here also saves a MODERATE-default persona (via skipPersona) rather than leaving the
// user with none at all — completing the full flow instead saves the real answers.
export function PersonaOnboarding({ onClose, onDismissForever, initial }: {
  onClose: () => void; onDismissForever: () => void; initial?: Persona | null
}) {
  const [step, setStep] = useState(0)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  const [age, setAge] = useState(initial?.age ? String(initial.age) : '')
  const [occupation, setOccupation] = useState(initial?.occupation ?? '')
  const [salaryRange, setSalaryRange] = useState<SalaryRange | ''>(initial?.salaryRange ?? '')
  const [investorPersona, setInvestorPersona] = useState<InvestorPersona | ''>(initial?.investorPersona ?? '')
  const [investingTenure, setInvestingTenure] = useState<InvestingTenure | ''>(initial?.investingTenure ?? '')
  const [currentInstruments, setCurrentInstruments] = useState<Set<InstrumentType>>(new Set(initial?.instrumentTypes ?? []))
  const [interestedInstruments, setInterestedInstruments] = useState<Set<InstrumentType>>(new Set(initial?.interestedInstrumentTypes ?? []))
  const [platforms, setPlatforms] = useState<Set<string>>(new Set(initial?.platforms ?? []))
  const [platformInput, setPlatformInput] = useState('')
  const [busy, setBusy] = useState(false)
  useEscToClose(onClose)

  const isLast = step === TOTAL_STEPS - 1
  const canGoBack = step > 0

  const toggle = (set: Set<InstrumentType>, setSet: (next: Set<InstrumentType>) => void, value: InstrumentType) => {
    const next = new Set(set)
    if (next.has(value)) next.delete(value); else next.add(value)
    setSet(next)
  }

  const addPlatform = () => {
    const name = platformInput.trim()
    if (!name) return
    setPlatforms(current => new Set(current).add(name))
    setPlatformInput('')
  }
  const removePlatform = (name: string) => {
    setPlatforms(current => { const next = new Set(current); next.delete(name); return next })
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
        investorPersona: investorPersona || null,
        investingTenure: investingTenure || null,
        instrumentTypes: Array.from(currentInstruments),
        interestedInstrumentTypes: Array.from(interestedInstruments),
        platforms: Array.from(platforms),
      })
    } catch { /* best-effort — still close so the user isn't stuck on a save failure */ }
    onDismissForever()
    onClose()
  }

  const title = step === 0 ? "Let's personalise FinSights"
    : step === PERSONA_STEP ? 'Which investor profile fits you best?'
    : step === CATEGORIES_STEP ? 'What do you invest in?'
    : step === PLATFORMS_STEP ? 'Which platforms do you use?'
    : 'A bit about you'

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">GETTING TO KNOW YOU · STEP {step + 1} OF {TOTAL_STEPS}</p><h2>{title}</h2></div>
      <button className="close" onClick={() => void skipNow()}>×</button>
    </div>

    {step === 0 && <p>A few quick, entirely optional questions — we'll use your answers to set up a couple of starter categories. Skip anytime.</p>}

    {step === PERSONA_STEP && <div className="persona-experience">
      <p className="hint">Which of these best describes you?</p>
      <div className="persona-radio-cards">
        {PERSONA_OPTIONS.map(o => <label key={o.value} className="persona-radio-card">
          <input type="radio" name="investor-persona" checked={investorPersona === o.value} onChange={() => setInvestorPersona(o.value)} />
          <span><b>{o.label}</b><small>{o.hint}</small></span>
        </label>)}
      </div>
    </div>}

    {step === CATEGORIES_STEP && <div className="persona-categories">
      <div className="persona-category-group">
        <p className="hint">Currently investing in</p>
        <div className="persona-instruments">
          {INSTRUMENT_OPTIONS.map(o => <label key={o.value} className="persona-checkbox">
            <input type="checkbox" checked={currentInstruments.has(o.value)} onChange={() => toggle(currentInstruments, setCurrentInstruments, o.value)} />
            {o.label}
          </label>)}
        </div>
      </div>
      <div className="persona-category-group">
        <p className="hint">Want to start investing in</p>
        <div className="persona-instruments">
          {INSTRUMENT_OPTIONS.map(o => <label key={o.value} className="persona-checkbox">
            <input type="checkbox" checked={interestedInstruments.has(o.value)} onChange={() => toggle(interestedInstruments, setInterestedInstruments, o.value)} />
            {o.label}
          </label>)}
        </div>
      </div>
    </div>}

    {step === PLATFORMS_STEP && <div className="persona-platforms">
      <p className="hint">Add the brokers or platforms you use.</p>
      <div className="platform-input-row">
        <input list="platform-suggestions" value={platformInput} onChange={e => setPlatformInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addPlatform() } }}
          placeholder="e.g. Zerodha" />
        <datalist id="platform-suggestions">
          {PLATFORM_SUGGESTIONS.map(p => <option key={p} value={p} />)}
        </datalist>
        <button type="button" className="outline" onClick={addPlatform}>Add</button>
      </div>
      {platforms.size > 0 && <div className="platform-tags">
        {Array.from(platforms).map(p => <span key={p} className="platform-tag">{p}
          <button type="button" onClick={() => removePlatform(p)} aria-label={`Remove ${p}`}>×</button>
        </span>)}
      </div>}
    </div>}

    {step === DETAILS_STEP && <div className="persona-fields">
      <p className="hint">Optional — skip anything you'd rather not share now; you can always fill it in later from Settings.</p>
      <label>Age<input type="number" min={0} max={120} value={age} onChange={e => setAge(e.target.value)} /></label>
      <label>Occupation<input value={occupation} onChange={e => setOccupation(e.target.value)} placeholder="e.g. Software engineer" /></label>
      <label>Annual salary range
        <select value={salaryRange} onChange={e => setSalaryRange(e.target.value as SalaryRange)}>
          <option value="">Select…</option>
          {SALARY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>
      <label>How long have you been actively investing?
        <select value={investingTenure} onChange={e => setInvestingTenure(e.target.value as InvestingTenure)}>
          <option value="">Select…</option>
          {TENURE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>
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
      {canGoBack && <button type="button" className="outline" onClick={() => setStep(s => s - 1)}>Back</button>}
      <button type="button" className="primary" disabled={busy} onClick={() => isLast ? void finish() : setStep(s => s + 1)}>
        {isLast ? (busy ? 'Saving…' : 'Finish') : 'Next'}
      </button>
    </div>
  </section></div>
}
