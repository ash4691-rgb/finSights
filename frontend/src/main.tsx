import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { api, API_URL } from './api'
import { Field } from './ui'
import { App } from './App'
import type { User } from './types'

// ---------------------------------------------------------------------------
// Public site — a marketing homepage and a minimal Google sign-in screen sit
// in front of the authenticated app shell. "Entered" is remembered locally so
// a returning visitor skips straight back into the app; "Sign out" clears it.
// ---------------------------------------------------------------------------

export const ENTERED_KEY = 'finsights-entered-app'
export type Phase = 'home' | 'login' | 'app'

export function Root() {
  const [phase, setPhase] = useState<Phase>(() => {
    try { return localStorage.getItem(ENTERED_KEY) === 'true' ? 'app' : 'home' } catch { return 'home' }
  })
  const [loginMode, setLoginMode] = useState<'login' | 'signup'>('signup')

  const enterApp = () => {
    try { localStorage.setItem(ENTERED_KEY, 'true') } catch { /* storage unavailable */ }
    setPhase('app')
  }
  const exitApp = () => {
    try { localStorage.removeItem(ENTERED_KEY) } catch { /* storage unavailable */ }
    setPhase('home')
  }
  const goToLogin = (mode: 'login' | 'signup') => { setLoginMode(mode); setPhase('login') }

  // A real (non-demo) session means the user is already signed in — skip straight into the app,
  // e.g. after returning from the Google redirect.
  useEffect(() => {
    api<User>('/api/auth/me').then(me => { if (!me.demoMode) enterApp() }).catch(() => { /* not signed in */ })
  }, [])

  if (phase === 'home') return <Homepage onGetStarted={() => goToLogin('signup')} onSignIn={() => goToLogin('login')} />
  if (phase === 'login') return <LoginScreen initialMode={loginMode} onBack={() => setPhase('home')} onEnter={enterApp} />
  return <App onSignOut={exitApp} />
}

export const homeFeatures: [string, string, string][] = [
  ['◈', 'Categories & holdings', 'Define your own buckets — Growth Equity, Emergency Fund, whatever makes sense to you — and file every stock, fund, FD, or loan under one you own.'],
  ['⇄', 'FX-aware, base-currency first', 'Every value converts on the fly against your base currency, with the live conversion rate shown — never hidden behind a single blended number.'],
  ['▤', 'Manual-first, by design', 'No live broker sync yet, so every figure is one you entered and can trust. Bulk-import transactions from CSV or XML the moment you\'re ready.'],
]

export function Homepage({ onGetStarted, onSignIn }: { onGetStarted: () => void; onSignIn: () => void }) {
  return <div className="public-page">
    <header className="public-nav">
      <div className="brand"><div className="mark">F</div><span>FinSights</span></div>
      <nav className="public-nav-links">
        <a href="#features">Features</a>
        <a href="#privacy">Privacy</a>
      </nav>
      <button className="outline compact" onClick={onSignIn}>Log in</button>
    </header>

    <section className="public-hero">
      <div className="public-hero-copy">
        <p className="eyebrow">PORTFOLIO INTELLIGENCE</p>
        <h1>Know what your portfolio needs, not just what it's worth.</h1>
        <p className="public-lede">Bring your categories, holdings, and transactions together in one place, and FinSights reads the whole picture — surfacing what's moved, what's drifted, and what actually needs your attention.</p>
        <div className="public-cta-row">
          <button className="primary" onClick={onGetStarted}>Get started</button>
          <a className="outline" href="#features">Explore features</a>
        </div>
        <ul className="public-checklist">
          <li>✓ Intelligence that flags what needs a look</li>
          <li>✓ Your data lives in your own backend</li>
          <li>✓ Built for long-term, hands-on investors</li>
        </ul>
      </div>
      <div className="public-hero-preview" aria-hidden="true">
        <div className="preview-card">
          <p className="preview-label">Current portfolio value · Preview</p>
          <strong className="preview-value">₹33,05,835</strong>
          <span className="positive">↑ ₹1,21,335 total gain/loss</span>
          <div className="preview-rows">
            <div className="preview-row"><span>Growth Equity</span><b>₹1,97,850</b></div>
            <div className="preview-row"><span>Fixed Income</span><b>₹2,34,315</b></div>
            <div className="preview-row"><span>Gold &amp; Commodities</span><b>₹62,000</b></div>
          </div>
        </div>
      </div>
    </section>

    <section className="public-features" id="features">
      {homeFeatures.map(([icon, title, copy]) => <article className="public-feature-card" key={title}>
        <div className="mark">{icon}</div>
        <h3>{title}</h3>
        <p>{copy}</p>
      </article>)}
    </section>

    <section className="public-band">
      <div>
        <p className="eyebrow">WHAT YOUR PORTFOLIO IS ASKING FOR</p>
        <h2>The numbers, read for you.</h2>
        <p className="public-lede">Set movement thresholds — daily to yearly — for holdings you own and symbols you're just watching, and Hot picks surfaces the ones that broke them. The Action centre gathers what needs a decision — EMIs due, interest payouts to confirm, deposits that matured, missing values. Allocation drift, concentration risk, and rebalancing prompts are next.</p>
      </div>
    </section>

    <section className="public-band alt" id="privacy">
      <div>
        <p className="eyebrow">PRIVACY-FIRST</p>
        <h2>Your data, your backend.</h2>
        <p className="public-lede">FinSights doesn't sell data or run ads. Everything you enter is stored in your own backend — create an account with email or Google when you're ready to make it permanent.</p>
      </div>
    </section>

    <section className="public-cta-band">
      <h2>A clearer read on what you own.</h2>
      <button className="primary" onClick={onGetStarted}>Get started</button>
    </section>

    <footer className="public-footer">
      <div className="brand"><div className="mark">F</div><span>FinSights</span></div>
      <p>Portfolio intelligence for long-term investors.</p>
      <small>© {new Date().getFullYear()} FinSights.</small>
    </footer>
  </div>
}

export function LoginScreen({ initialMode, onBack, onEnter }: { initialMode: 'login' | 'signup'; onBack: () => void; onEnter: () => void }) {
  const [mode, setMode] = useState<'login' | 'signup'>(initialMode)
  const [googleEnabled, setGoogleEnabled] = useState(false)
  const [demoEnabled, setDemoEnabled] = useState(false)
  const [form, setForm] = useState({ email: '', password: '', displayName: '' })
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const set = (key: string, value: string) => setForm(current => ({ ...current, [key]: value }))

  useEffect(() => {
    api<{ googleEnabled: boolean; demoEnabled: boolean }>('/api/auth/config')
      .then(config => { setGoogleEnabled(config.googleEnabled); setDemoEnabled(config.demoEnabled) })
      .catch(() => { /* keep both off */ })
  }, [])

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      await api(mode === 'signup' ? '/api/auth/register' : '/api/auth/login', {
        method: 'POST',
        body: JSON.stringify(mode === 'signup'
          ? { email: form.email, displayName: form.displayName || null, password: form.password }
          : { email: form.email, password: form.password }),
      })
      onEnter()
    } catch (err) { setError(err instanceof Error ? err.message : 'Something went wrong') } finally { setBusy(false) }
  }
  const continueWithGoogle = () => { window.location.href = `${API_URL}/oauth2/authorization/google` }

  return <div className="public-page login-page">
    <header className="public-nav"><button className="brand brand-btn" onClick={onBack}><div className="mark">F</div><span>FinSights</span></button></header>
    <div className="login-center">
      <section className="modal narrow login-card">
        <h2>{mode === 'signup' ? 'Create your account' : 'Log in'}</h2>
        <form onSubmit={submit} className="auth-form">
          {mode === 'signup' && <Field label="Name"><input value={form.displayName} onChange={e => set('displayName', e.target.value)} placeholder="Your name" autoComplete="name" /></Field>}
          <Field label="Email" required><input type="email" required value={form.email} onChange={e => set('email', e.target.value)} placeholder="you@example.com" autoComplete="email" /></Field>
          <Field label="Password" required><input type="password" required minLength={mode === 'signup' ? 8 : undefined} value={form.password} onChange={e => set('password', e.target.value)} placeholder={mode === 'signup' ? 'At least 8 characters' : ''} autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} /></Field>
          {error && <p className="form-error">{error}</p>}
          <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'signup' ? 'Create account' : 'Log in'}</button>
        </form>
        <p className="auth-toggle">
          {mode === 'signup' ? 'Already have an account? ' : 'New to FinSights? '}
          <button type="button" onClick={() => { setMode(mode === 'signup' ? 'login' : 'signup'); setError('') }}>
            {mode === 'signup' ? 'Log in' : 'Create an account'}
          </button>
        </p>
        <div className="auth-divider"><span>or</span></div>
        <button type="button" className="outline google-btn" onClick={continueWithGoogle} disabled={!googleEnabled}
          title={googleEnabled ? '' : 'Set APP_AUTH_MODE=google on the backend to enable Google sign-in'}>Continue with Google</button>
        {!googleEnabled && <p className="auth-note">Google sign-in isn't configured on this deployment.</p>}
        {demoEnabled && <button type="button" className="link-back" onClick={onEnter}>Skip — explore the demo without an account</button>}
        <button type="button" className="link-back" onClick={onBack}>← Back to homepage</button>
      </section>
    </div>
  </div>
}

createRoot(document.getElementById('root')!).render(<Root />)
