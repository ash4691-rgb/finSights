import { api } from './api'

export async function fetchLayouts(): Promise<Record<string, unknown>> {
  try {
    const rows = await api<Record<string, { page: string; config: unknown }>>('/api/layouts')
    return Object.fromEntries(Object.values(rows).map(r => [r.page, r.config]))
  } catch { return {} }
}
export async function saveLayout(page: string, config: unknown): Promise<void> {
  try { await api(`/api/layouts/${page}`, { method: 'PUT', body: JSON.stringify({ config }) }) }
  catch { /* offline — localStorage mirror still holds it */ }
}

// "Don't show this again" for the Edit Layout onboarding tour. Best-effort: if this fails the
// tour just replays next time, which is a fine fallback rather than blocking the user on it.
export async function dismissEditLayoutOnboarding(): Promise<void> {
  try { await api('/api/settings/edit-layout-onboarding/dismiss', { method: 'POST' }) }
  catch { /* replays next time — not worth surfacing an error for */ }
}
