import { api } from './api'
import type { InstrumentType, InvestingTenure, Persona, PortfolioSize, SalaryRange } from './types'

// No investorPersona field — it's derived server-side from age/salaryRange/portfolioSize (see
// the backend's PersonaService.derivePersona) rather than submitted directly. No risk-answer
// fields either — the basic onboarding flow (persona-onboarding.tsx) no longer asks them at
// all; submitting here always scores a MODERATE risk default until the dedicated RiskAssessment
// (risk-assessment.tsx) runs separately via updateRisk() below.
export type PersonaSubmission = {
  age: number | null
  occupation: string | null
  salaryRange: SalaryRange | null
  portfolioSize: PortfolioSize | null
  investingTenure: InvestingTenure | null
  instrumentTypes: InstrumentType[]
  platforms: string[]
}

// Null both when the request fails and when the user hasn't completed or skipped the
// questionnaire yet (204 No Content) — either way there's nothing to show.
export async function fetchPersona(): Promise<Persona | null> {
  try { return (await api<Persona | undefined>('/api/persona')) ?? null }
  catch { return null }
}

export async function submitPersona(payload: PersonaSubmission): Promise<void> {
  await api('/api/persona', { method: 'POST', body: JSON.stringify(payload) })
}

// Best-effort, same as the other onboarding dismiss calls: if this fails the widget just
// replays next time, which is a fine fallback rather than blocking the user on it.
export async function skipPersona(): Promise<void> {
  try { await api('/api/persona/skip', { method: 'POST' }) }
  catch { /* replays next time — not worth surfacing an error for */ }
}

export type PersonaDetails = {
  age: number | null
  occupation: string | null
  salaryRange: SalaryRange | null
  portfolioSize: PortfolioSize | null
  investingTenure: InvestingTenure | null
}

// Settings' inline "edit your details" save — deliberately narrower than submitPersona: it never
// touches riskProfile/instrumentTypes (Settings has no way to resupply the five scenario
// answers, so resubmitting the full questionnaire from here would silently reset an
// already-computed risk profile back to the "missing answers" default). investorPersona DOES get
// recomputed server-side from the new age/salaryRange/portfolioSize, same as on initial submit.
export async function updatePersonaDetails(payload: PersonaDetails): Promise<Persona> {
  return api<Persona>('/api/persona/details', { method: 'PUT', body: JSON.stringify(payload) })
}

export type RiskAssessmentSubmission = {
  timeHorizonAnswer: number | null
  riskCapacityAnswer: number | null
  riskToleranceAnswer: number | null
  investmentObjectivesAnswer: number | null
  liquidityNeedsAnswer: number | null
}

// The five-question risk assessment on its own — used both by Settings' "Reassess risk profile"
// and the dedicated prompt shown on a later login (see RiskAssessment). Never touches
// demographics/persona/instruments/platforms, which this screen has no way to resupply.
export async function updateRisk(payload: RiskAssessmentSubmission): Promise<Persona> {
  return api<Persona>('/api/persona/risk', { method: 'PUT', body: JSON.stringify(payload) })
}
