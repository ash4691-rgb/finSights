import { useState } from 'react'
import { useEscToClose } from './ui'
import { api } from './api'
import { submitPersona, skipPersona } from './persona-api'
import type { Country, InstrumentType, InvestingTenure, InvestorPersona, Persona, PortfolioSize, RiskProfile, SalaryRange, Settings } from './types'

export const SALARY_OPTIONS: { value: SalaryRange; label: string }[] = [
  { value: 'UNDER_5L', label: 'Under ₹5L' },
  { value: 'L5_TO_10L', label: '₹5L – ₹10L' },
  { value: 'L10_TO_25L', label: '₹10L – ₹25L' },
  { value: 'L25_TO_50L', label: '₹25L – ₹50L' },
  { value: 'ABOVE_50L', label: 'Above ₹50L' },
  { value: 'PREFER_NOT_TO_SAY', label: 'Prefer not to say' },
]

// Current total portfolio value — one of the signals (alongside age) the backend derives an
// InvestorPersona archetype from, instead of asking the user to self-select one.
export const PORTFOLIO_SIZE_OPTIONS: { value: PortfolioSize; label: string }[] = [
  { value: 'UNDER_1L', label: 'Under ₹1L' },
  { value: 'L1_TO_10L', label: '₹1L – ₹10L' },
  { value: 'L10_TO_50L', label: '₹10L – ₹50L' },
  { value: 'L50_TO_2CR', label: '₹50L – ₹2Cr' },
  { value: 'ABOVE_2CR', label: 'Above ₹2Cr' },
  { value: 'PREFER_NOT_TO_SAY', label: 'Prefer not to say' },
]
export const PORTFOLIO_SIZE_LABELS: Record<PortfolioSize, string> = Object.fromEntries(
  PORTFOLIO_SIZE_OPTIONS.map(o => [o.value, o.label])) as Record<PortfolioSize, string>

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

// Four-archetype framework, built around age and portfolio size — the backend derives one of
// these (see PersonaService.derivePersona) rather than asking the user to self-select. Kept here
// purely as a display lookup for the Settings tag.
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

// No persona-selection step — the archetype is derived server-side from age + portfolio size
// (see PersonaService.derivePersona), not asked directly. Demographics come first (the signals
// the derivation needs), then what/where the user invests. No risk questions here at all —
// those are a separate RiskAssessment prompted on a later login (see App.tsx).
const DETAILS_STEP = 1
const FINANCES_STEP = 2
const CATEGORIES_STEP = 3
const PLATFORMS_STEP = 4
const TOTAL_STEPS = PLATFORMS_STEP + 1

// A data-collecting, skippable onboarding widget — a few demographic and financial signals used
// to derive a starter persona and seed a few starter categories. Same skip / "don't show again"
// pattern as CustomLayoutOnboarding and UserOnboarding, but "don't show again" here also saves a
// MODERATE-default persona (via skipPersona) rather than leaving the user with none at all —
// completing the full flow instead saves the real answers.
export function PersonaOnboarding({ onClose, onDismissForever, initial, countries, settings }: {
  onClose: () => void; onDismissForever: () => void; initial?: Persona | null; countries: Country[]; settings: Settings
}) {
  const [step, setStep] = useState(0)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  const [age, setAge] = useState(initial?.age ? String(initial.age) : '')
  const [occupation, setOccupation] = useState(initial?.occupation ?? '')
  const [country, setCountry] = useState(settings.country)
  const [salaryRange, setSalaryRange] = useState<SalaryRange | ''>(initial?.salaryRange ?? '')
  const [portfolioSize, setPortfolioSize] = useState<PortfolioSize | ''>(initial?.portfolioSize ?? '')
  const [investingTenure, setInvestingTenure] = useState<InvestingTenure | ''>(initial?.investingTenure ?? '')
  const [instruments, setInstruments] = useState<Set<InstrumentType>>(new Set(initial?.instrumentTypes ?? []))
  const [platforms, setPlatforms] = useState<Set<string>>(new Set(initial?.platforms ?? []))
  const [platformInput, setPlatformInput] = useState('')
  const [busy, setBusy] = useState(false)
  useEscToClose(onClose)

  const isLast = step === TOTAL_STEPS - 1
  const canGoBack = step > 0

  const toggleInstrument = (value: InstrumentType) => {
    setInstruments(current => {
      const next = new Set(current)
      if (next.has(value)) next.delete(value); else next.add(value)
      return next
    })
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
      // Sequential, not Promise.all: both calls load-modify-save the same UserAccount row (this
      // one touches country/currency, submitPersona() touches personaOnboardingDismissed),
      // UserAccount has no optimistic-locking @Version column, and SettingsService persists the
      // whole entity it loaded — so firing them concurrently is a lost-update race where whichever
      // request's save() lands second silently overwrites the other's change. Running them one
      // after another means each starts from the previous one's already-committed state.
      // Country drives the account's base currency (see Settings) — only worth a write if the
      // user actually changed it from what's already on file.
      if (country !== settings.country) {
        await api('/api/settings', { method: 'PUT', body: JSON.stringify({
          country, displayName: settings.displayName, phone: settings.phone ?? '', numberFormat: settings.numberFormat,
          notifyEmail: settings.notifyEmail, notifySms: settings.notifySms, notifyPush: settings.notifyPush,
          notifyThresholdPercent: settings.notifyThresholdPercent,
          dailyThresholdPercent: settings.dailyThresholdPercent, weeklyThresholdPercent: settings.weeklyThresholdPercent,
          monthlyThresholdPercent: settings.monthlyThresholdPercent, quarterlyThresholdPercent: settings.quarterlyThresholdPercent,
          yearlyThresholdPercent: settings.yearlyThresholdPercent,
        }) })
      }
      await submitPersona({
        age: age ? Number(age) : null,
        occupation: occupation || null,
        salaryRange: salaryRange || null,
        portfolioSize: portfolioSize || null,
        investingTenure: investingTenure || null,
        instrumentTypes: Array.from(instruments),
        platforms: Array.from(platforms),
      })
    } catch { /* best-effort — still close so the user isn't stuck on a save failure */ }
    onDismissForever()
    onClose()
  }

  const title = step === 0 ? "Let's personalise FinSights"
    : step === DETAILS_STEP ? 'A bit about you'
    : step === FINANCES_STEP ? 'Your investments so far'
    : step === CATEGORIES_STEP ? 'What do you invest in?'
    : 'Which platforms do you use?'

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">GETTING TO KNOW YOU · STEP {step + 1} OF {TOTAL_STEPS}</p><h2>{title}</h2></div>
      <button className="close" onClick={() => void skipNow()}>×</button>
    </div>

    {step === 0 && <p>A few quick, entirely optional questions — we'll use your answers to set up a couple of starter categories. Skip anytime.</p>}

    {step === DETAILS_STEP && <div className="persona-fields">
      <label>Age<input type="number" min={0} max={120} value={age} onChange={e => setAge(e.target.value)} /></label>
      <label>Occupation<input value={occupation} onChange={e => setOccupation(e.target.value)} placeholder="e.g. Software engineer" /></label>
      <label>Country of residence
        <select value={country} onChange={e => setCountry(e.target.value)}>
          {countries.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}
        </select>
      </label>
      <p className="hint">Your country sets your default currency — you can always change it later from Settings.</p>
    </div>}

    {step === FINANCES_STEP && <div className="persona-fields">
      <label>Annual income range
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
      <label>Current portfolio size
        <select value={portfolioSize} onChange={e => setPortfolioSize(e.target.value as PortfolioSize)}>
          <option value="">Select…</option>
          {PORTFOLIO_SIZE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>
    </div>}

    {step === CATEGORIES_STEP && <>
      <p className="hint">Check everything you're currently investing in, or thinking about investing in.</p>
      <div className="persona-instruments">
        {INSTRUMENT_OPTIONS.map(o => <label key={o.value} className="persona-checkbox">
          <input type="checkbox" checked={instruments.has(o.value)} onChange={() => toggleInstrument(o.value)} />
          {o.label}
        </label>)}
      </div>
    </>}

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
