export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

// Carries the HTTP status alongside the message so callers can tell "you're not signed in
// anymore" (401 — session expired/invalidated) apart from a transient network/server failure
// worth retrying.
export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

// Render's free tier spins the backend down after inactivity, and Neon suspends its compute
// alongside it — the first request after either can transiently fail (timeout, connection
// reset, or a 5xx while the app/DB is still waking up) even though a retry moments later would
// succeed. GET is the only method safe to retry blind: a POST/PUT/DELETE whose response was
// lost to exactly this kind of hiccup may have already landed server-side, and retrying it
// could double-submit (e.g. create a duplicate holding) — so only reads get the safety net;
// every write still surfaces its error immediately, same as before.
const RETRYABLE_ATTEMPTS = 3
const RETRY_DELAYS_MS = [400, 900]

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const attempts = method === 'GET' ? RETRYABLE_ATTEMPTS : 1

  for (let attempt = 0; ; attempt++) {
    let response: Response
    try {
      response = await fetch(`${API_URL}${path}`, {
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-Demo-User': 'demo@finsights.local', ...(options.headers ?? {}) },
        ...options,
      })
    } catch (err) {
      // Network-level failure (connection refused/reset) — the request never reached the server.
      if (attempt < attempts - 1) { await sleep(RETRY_DELAYS_MS[attempt]); continue }
      throw err
    }
    // A 5xx while the backend/DB is still waking up is worth one more try; a 4xx is a real
    // error (bad input, not signed in, …) that retrying won't fix.
    if (!response.ok && response.status >= 500 && attempt < attempts - 1) {
      await sleep(RETRY_DELAYS_MS[attempt]); continue
    }
    if (!response.ok) {
      const message = await response.json().catch(() => ({}))
      throw new ApiError(message.message ?? 'Something went wrong', response.status)
    }
    return response.status === 204 ? undefined as T : response.json()
  }
}
