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
