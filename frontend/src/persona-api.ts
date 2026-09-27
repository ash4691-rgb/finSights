import { api } from './api'
import type { InstrumentType, InvestingTenure, InvestorExperience, Persona, SalaryRange } from './types'

export type PersonaSubmission = {
  age: number | null
  occupation: string | null
  salaryRange: SalaryRange | null
  investorExperience: InvestorExperience | null
  investingTenure: InvestingTenure | null
  instrumentTypes: InstrumentType[]
  marketDropAnswer: number | null
  timeHorizonAnswer: number | null
  tradeOffAnswer: number | null
  volatilityReactionAnswer: number | null
  primaryGoalAnswer: number | null
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
