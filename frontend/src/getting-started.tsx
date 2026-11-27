import { useState } from 'react'
import { useEscToClose } from './ui'
import { dismissUserOnboarding } from './layout-api'
import { PersonaOnboarding } from './persona-onboarding'
import { RiskAssessment } from './risk-assessment'
import type { Country, Persona, Settings } from './types'

// The one first-run flow: profile questions, then the risk questionnaire, then a single
// "here's where things are" screen, all in one sitting. Each part is skipped once the account has
// completed or dismissed it, so a returning user only sees what's still outstanding.
export type Phase = 'profile' | 'risk' | 'welcome'
const ORDER: Phase[] = ['profile', 'risk', 'welcome']

export function isPending(phase: Phase, settings: Settings): boolean {
  if (phase === 'profile') return !settings.personaOnboardingDismissed
  if (phase === 'risk') return !settings.riskOnboardingDismissed
  return !settings.userOnboardingDismissed
}

/** The first phase still to do, starting after {@code current} (or from the top). Null = nothing left. */
export function nextPhase(settings: Settings, current?: Phase): Phase | null {
  const from = current ? ORDER.indexOf(current) + 1 : 0
  return ORDER.slice(from).find(p => isPending(p, settings)) ?? null
}

const PLACES: [string, string][] = [
  ['Overview', 'Net worth, XIRR and how it moved week to week.'],
  ['Data', 'Categories, holdings and transactions. Or upload a statement from Sources.'],
  ['Signals', "What's due, what moved past your thresholds, and news on what you hold."],
  ['Analysis', 'Returns, India tax, target allocation and goals.'],
]

export function WelcomeScreen({ onDone }: { onDone: () => void }) {
  useEscToClose(onDone)
  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">GETTING STARTED</p><h2>You're set</h2></div>
      <button className="close" onClick={onDone} aria-label="Close">×</button>
    </div>
    <p>Here's where everything lives:</p>
    <ul className="welcome-places">{PLACES.map(([name, text]) => <li key={name}><strong>{name}</strong> {text}</li>)}</ul>
    <p className="hint">Goku (⚡, bottom right) can answer questions about your portfolio, if it's switched on for you.</p>
    <div className="modal-actions"><button type="button" className="primary" onClick={onDone}>Start using FinSights</button></div>
  </section></div>
}

export function GettingStarted({ settings, persona, countries, onReload, onSettingsChange, onFinished }: {
  settings: Settings; persona: Persona | null; countries: Country[]
  onReload: (newBaseCurrency?: string) => void
  onSettingsChange: (patch: Partial<Settings>) => void
  onFinished: () => void
}) {
  const [phase, setPhase] = useState<Phase | null>(() => nextPhase(settings))
  const advance = (from: Phase) => {
    const next = nextPhase(settings, from)
    setPhase(next)
    if (next === null) onFinished()
  }

  if (phase === 'profile') return <PersonaOnboarding initial={persona} countries={countries} settings={settings}
    onClose={newBaseCurrency => { onReload(newBaseCurrency); advance('profile') }}
    onDismissForever={() => onSettingsChange({ personaOnboardingDismissed: true })} />
  if (phase === 'risk') return <RiskAssessment
    onClose={() => advance('risk')}
    onDone={() => { onSettingsChange({ riskOnboardingDismissed: true }); onReload(); advance('risk') }} />
  if (phase === 'welcome') return <WelcomeScreen onDone={() => {
    onSettingsChange({ userOnboardingDismissed: true }); void dismissUserOnboarding(); advance('welcome')
  }} />
  return null
}
