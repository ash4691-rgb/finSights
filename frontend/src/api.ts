export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

// Carries the HTTP status alongside the message so callers can tell "you're not signed in
// anymore" (401 — session expired/invalidated) apart from a transient network/server failure
// worth retrying.
export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-Demo-User': 'demo@finsights.local', ...(options.headers ?? {}) },
    ...options,
  })
  if (!response.ok) {
    const message = await response.json().catch(() => ({}))
    throw new ApiError(message.message ?? 'Something went wrong', response.status)
  }
  return response.status === 204 ? undefined as T : response.json()
}
