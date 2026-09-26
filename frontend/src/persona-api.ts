import { api } from './api'
import type { InstrumentType, SalaryRange } from './types'

export type PersonaSubmission = {
  age: number | null
  occupation: string | null
  salaryRange: SalaryRange | null
  instrumentTypes: InstrumentType[]
  marketDropAnswer: number | null
  timeHorizonAnswer: number | null
  tradeOffAnswer: number | null
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
