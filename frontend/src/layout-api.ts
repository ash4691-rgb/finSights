import { api } from './api'

// Left to reject on failure (not swallowed): App.tsx only calls hydrateLayouts() when this
// promise fulfils, so a transient network error leaves localStorage untouched instead of being
// mistaken for "this user has no saved layouts" and wiping a perfectly good local cache.
export async function fetchLayouts(): Promise<Record<string, unknown>> {
  const rows = await api<Record<string, { page: string; config: unknown }>>('/api/layouts')
  return Object.fromEntries(Object.values(rows).map(r => [r.page, r.config]))
}
export async function saveLayout(page: string, config: unknown): Promise<void> {
  try { await api(`/api/layouts/${page}`, { method: 'PUT', body: JSON.stringify({ config }) }) }
  catch { /* offline — localStorage mirror still holds it */ }
}

// "Don't show this again" for the CustomLayoutOnboarding tour. Best-effort: if this fails the
// tour just replays next time, which is a fine fallback rather than blocking the user on it.
export async function dismissCustomLayoutOnboarding(): Promise<void> {
  try { await api('/api/settings/custom-layout-onboarding/dismiss', { method: 'POST' }) }
  catch { /* replays next time — not worth surfacing an error for */ }
}

// Same best-effort "don't show this again" for the UserOnboarding (app-concepts) tour.
export async function dismissUserOnboarding(): Promise<void> {
  try { await api('/api/settings/user-onboarding/dismiss', { method: 'POST' }) }
  catch { /* replays next time — not worth surfacing an error for */ }
}
