export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-Demo-User': 'demo@finsights.local', ...(options.headers ?? {}) },
    ...options,
  })
  if (!response.ok) {
    const message = await response.json().catch(() => ({}))
    throw new Error(message.message ?? 'Something went wrong')
  }
  return response.status === 204 ? undefined as T : response.json()
}
