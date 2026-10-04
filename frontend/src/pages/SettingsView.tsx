import { useState } from 'react'
import type * as React from 'react'
import { api, API_URL } from '../api'
import { money, since, numeric, toggleLabel } from '../util'
import { Switch } from '../ui'
import { updatePersonaDetails } from '../persona-api'
import { PERSONA_DESCRIPTIONS, PERSONA_ICONS, PERSONA_LABELS, PORTFOLIO_SIZE_OPTIONS, RISK_ALLOCATION, RISK_DESCRIPTIONS, RISK_LABELS, RISK_TAG_CLASS, SALARY_OPTIONS, TENURE_OPTIONS } from '../persona-onboarding'
import type { InvestingTenure, Settings, Country, Dashboard, Holding, Theme, Persona, PortfolioSize, SalaryRange } from '../types'

export function SettingsView({ settings, countries, dashboard, holdings, reload, theme, setTheme, persona, onStartOnboarding, onReassessRisk, onAccountDeleted }: {
  settings: Settings; countries: Country[]; dashboard: Dashboard | null; holdings: Holding[]
  reload: () => Promise<void>; theme: Theme; setTheme: React.Dispatch<React.SetStateAction<Theme>>
  persona: Persona | null; onStartOnboarding: () => void; onReassessRisk: () => void; onAccountDeleted: () => void
}) {
  const [form, setForm] = useState({
    displayName: settings.displayName, phone: settings.phone || '', country: settings.country,
    numberFormat: settings.numberFormat, notifyEmail: settings.notifyEmail, notifySms: settings.notifySms,
    notifyPush: settings.notifyPush, notifyThresholdPercent: String(settings.notifyThresholdPercent),
    // Investor details — only meaningful once a persona row exists (see the `persona` prop);
    // blank otherwise, since there's nothing to edit yet.
    age: persona?.age ? String(persona.age) : '', occupation: persona?.occupation ?? '',
    salaryRange: persona?.salaryRange ?? '' as SalaryRange | '',
    portfolioSize: persona?.portfolioSize ?? '' as PortfolioSize | '',
    investingTenure: persona?.investingTenure ?? '' as InvestingTenure | '',
  })
  const [status, setStatus] = useState('')
  const [confirmText, setConfirmText] = useState('')
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  // Accordion — at most one section open at a time.
  const [openSection, setOpenSection] = useState('User profile')
  const sectionProps = (title: string) => ({
    title, open: openSection === title,
    onToggle: () => setOpenSection(current => current === title ? '' : title),
  })
  const selectedCountry = countries.find(c => c.code === form.country)
  const set = <K extends keyof typeof form>(key: K, value: typeof form[K]) => setForm(current => ({ ...current, [key]: value }))
  const save = async () => {
    setStatus('saving')
    try {
      const calls: Promise<unknown>[] = [api('/api/settings', { method: 'PUT', body: JSON.stringify({
        country: form.country, displayName: form.displayName, phone: form.phone, numberFormat: form.numberFormat,
        notifyEmail: form.notifyEmail, notifySms: form.notifySms, notifyPush: form.notifyPush,
        notifyThresholdPercent: numeric(form.notifyThresholdPercent),
      }) })]
      // Persona/risk profile itself is read-only here — only the demographic fields save, and
      // only once a persona exists to attach them to (see updatePersonaDetails).
      if (persona) {
        calls.push(updatePersonaDetails({
          age: form.age ? Number(form.age) : null, occupation: form.occupation || null,
          salaryRange: form.salaryRange || null, portfolioSize: form.portfolioSize || null,
          investingTenure: form.investingTenure || null,
        }))
      }
      await Promise.all(calls)
      await reload(); setStatus('saved')
    } catch (e) { setStatus(e instanceof Error ? e.message : 'Could not save') }
  }
  const exportJson = async () => {
    const response = await fetch(`${API_URL}/api/account/export`, { credentials: 'include', headers: { 'X-Demo-User': 'demo@finsights.local' } })
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = 'finsights-export.json'; link.click()
    URL.revokeObjectURL(url)
  }
  // One phrase-gated action, keyed by the exact confirm text — the button itself relabels to
  // match whichever phrase is currently typed, rather than offering three separate buttons that
  // are mostly just sitting there disabled. DELETE ACCOUNT skips reload(): the account (and its
  // session) is gone at that point, and reload() would otherwise immediately re-authenticate as
  // the shared demo identity (every request carries an X-Demo-User fallback header — see api.ts)
  // instead of actually landing back on the sign-in screen, making deletion look like it silently
  // "didn't work." onAccountDeleted runs the same client-side sign-out App.tsx uses elsewhere,
  // with no further API call in between.
  const DELETE_ACTIONS: Record<string, { label: string; run: () => Promise<void> }> = {
    'DELETE HOLDINGS': {
      label: 'Delete holdings & transactions',
      run: async () => { await api('/api/account/holdings', { method: 'DELETE' }); await reload() },
    },
    'DELETE TRANSACTIONS': {
      label: 'Delete transactions',
      run: async () => { await api('/api/account/transactions', { method: 'DELETE' }); await reload() },
    },
    'DELETE ACCOUNT': {
      label: 'Delete everything',
      run: async () => { await api('/api/account', { method: 'DELETE' }); onAccountDeleted() },
    },
  }
  const deleteAction = DELETE_ACTIONS[confirmText]
  const runDelete = async () => {
    if (!deleteAction) return
    setDeleteBusy(true); setDeleteError(''); setConfirmText('')
    try { await deleteAction.run() }
    catch (e) { setDeleteError(e instanceof Error ? e.message : 'Could not complete that — try again.') }
    finally { setDeleteBusy(false) }
  }
  const brokersConnected = new Set(holdings.map(h => h.broker).filter((b): b is string => !!b)).size
  const saveBar = <>
    <button className="primary" onClick={() => void save()} disabled={status === 'saving'}>{status === 'saving' ? 'Saving…' : 'Save changes'}</button>
    {status && status !== 'saving' && <span className="settings-status">{status === 'saved' ? 'Saved.' : status}</span>}
  </>

  return <div className="settings-list">
    <SettingsSection {...sectionProps('User profile')} subtitle="Who you are">
      <h4 className="settings-subheading">Contact &amp; account</h4>
      <div className="settings-fields-grid">
        <div className="settings-field"><label>Display name</label><input value={form.displayName} onChange={e => set('displayName', e.target.value)} /></div>
        <div className="settings-field"><label>Email</label><input value={settings.email} disabled title="Managed by your sign-in provider" /></div>
        <div className="settings-field"><label>Contact number</label><input value={form.phone} onChange={e => set('phone', e.target.value)} placeholder="+91 98765 43210" /></div>
        <div className="settings-field">
          <label>Country of residence</label>
          <select value={form.country} onChange={e => set('country', e.target.value)}>{countries.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}</select>
        </div>
      </div>
      <p className="hint">Your base currency follows your country: <b>{selectedCountry?.currency ?? settings.baseCurrency}</b>. Any page also has a "View in" dropdown for a one-off switch, using live market rates (refreshed at most every 20 minutes).</p>

      <div className="settings-subsection">
        <h4 className="settings-subheading">Investor details</h4>
        {persona ? <>
          <div className="profile-tags-row">
            {persona.investorPersona && <span className="profile-tag-wrap" tabIndex={0}>
              <span className="profile-tag">{PERSONA_ICONS[persona.investorPersona]} {PERSONA_LABELS[persona.investorPersona]}</span>
              <span className="profile-tag-tooltip" role="tooltip">{PERSONA_DESCRIPTIONS[persona.investorPersona]}</span>
            </span>}
            <span className="profile-tag-wrap" tabIndex={0}>
              <span className={`profile-tag ${RISK_TAG_CLASS[persona.riskProfile]}`}>{RISK_LABELS[persona.riskProfile]}</span>
              <span className="profile-tag-tooltip" role="tooltip">{RISK_DESCRIPTIONS[persona.riskProfile]} Core focus: {RISK_ALLOCATION[persona.riskProfile].coreFocus}.</span>
            </span>
          </div>
          <p className="hint">Ideal portfolio mix: Equity {RISK_ALLOCATION[persona.riskProfile].equity} · Debt &amp; cash {RISK_ALLOCATION[persona.riskProfile].debtCash}, based on your persona &amp; risk profile.</p>
          <div className="settings-fields-grid">
            <div className="settings-field"><label>Age</label><input type="number" min={0} max={120} value={form.age} onChange={e => set('age', e.target.value)} /></div>
            <div className="settings-field"><label>Occupation</label><input value={form.occupation} onChange={e => set('occupation', e.target.value)} placeholder="e.g. Software engineer" /></div>
            <div className="settings-field">
              <label>Annual salary range</label>
              <select value={form.salaryRange} onChange={e => set('salaryRange', e.target.value as SalaryRange)}>
                <option value="">Select…</option>
                {SALARY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
            <div className="settings-field">
              <label>Investing since</label>
              <select value={form.investingTenure} onChange={e => set('investingTenure', e.target.value as InvestingTenure)}>
                <option value="">Select…</option>
                {TENURE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
            <div className="settings-field">
              <label>Portfolio size</label>
              <select value={form.portfolioSize} onChange={e => set('portfolioSize', e.target.value as PortfolioSize)}>
                <option value="">Select…</option>
                {PORTFOLIO_SIZE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
          </div>
          <button type="button" className="outline" onClick={onReassessRisk}>Reassess Risk profile</button>
        </> : <p className="hint">You haven't completed the persona questionnaire yet. <button type="button" className="link-inline" onClick={onStartOnboarding}>Start now</button></p>}
      </div>
      {saveBar}
    </SettingsSection>

    <SettingsSection {...sectionProps('User preferences')} subtitle="How the app looks & behaves">
      <div className="settings-field">
        <label>Appearance</label>
        <Switch checked={theme === 'light'} onChange={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} text={theme === 'dark' ? 'Dark' : 'Light'} icon={theme === 'dark' ? '🌙' : '☀'} />
      </div>
      <div className="settings-field">
        <label>Number system</label>
        <select value={form.numberFormat} onChange={e => set('numberFormat', e.target.value as Settings['numberFormat'])}>
          <option value="INDIAN">Indian (Lakh, Crore — e.g. 12,34,567)</option>
          <option value="INTERNATIONAL">International (Million, Billion — e.g. 1,234,567)</option>
        </select>
      </div>
      <div className="settings-field">
        <label>Two-factor authentication</label>
        <Switch checked={false} disabled text="Off — needs Google Sign-In" icon="🔒" title="2FA becomes available once Google Sign-In replaces demo mode" />
      </div>
      {saveBar}
    </SettingsSection>

    <SettingsSection {...sectionProps('Notification preferences')} subtitle="Channels & limits">
      <p className="hint">Alerts aren't sent yet — these preferences are saved now so they take effect as soon as alerting ships.</p>
      <div className="check-row settings-checks">
        <label><input type="checkbox" checked={form.notifyEmail} onChange={e => set('notifyEmail', e.target.checked)} /> Email</label>
        <label><input type="checkbox" checked={form.notifySms} onChange={e => set('notifySms', e.target.checked)} /> SMS</label>
        <label><input type="checkbox" checked={form.notifyPush} onChange={e => set('notifyPush', e.target.checked)} /> Push</label>
      </div>
      <div className="settings-field">
        <label>Notify on moves greater than</label>
        <div className="inline-field"><input type="number" min="0" step="0.5" value={form.notifyThresholdPercent} onChange={e => set('notifyThresholdPercent', e.target.value)} /><span>%</span></div>
      </div>
      {saveBar}
    </SettingsSection>

    <SettingsSection {...sectionProps('Account management')} subtitle={`Member since ${since(settings.memberSince)}`}>
      <div className="pulse-row"><span>Net worth</span><strong>{dashboard ? money(dashboard.netWorth) : '—'}</strong></div>
      <div className="pulse-row"><span>Holdings tracked</span><strong>{settings.holdingCount}</strong></div>
      <div className="pulse-row"><span>Brokers connected</span><strong>{brokersConnected}</strong></div>
      <div className="pulse-row"><span>Authentication</span><strong>{settings.demoMode ? 'Demo mode' : 'Google'}</strong></div>
      <button className="outline" onClick={() => void exportJson()}>Export all data (JSON)</button>
      <div className="danger-zone-inline">
        <div className="panel-heading"><h3>Delete data</h3><span>Cannot be undone</span></div>
        <div className="danger-zone-row">
          <input value={confirmText} onChange={e => setConfirmText(e.target.value)} placeholder="Type a phrase below" disabled={deleteBusy} />
          <button className="danger-btn" onClick={() => void runDelete()} disabled={!deleteAction || deleteBusy}>
            {deleteBusy ? 'Deleting…' : deleteAction?.label ?? 'Delete'}
          </button>
        </div>
        {deleteBusy && <p className="hint">Deleting… this may take a moment — please don't close this page.</p>}
        {deleteError && <p className="form-error">{deleteError}</p>}
        <div className="danger-zone-info">
          <p><b>DELETE HOLDINGS</b> — removes every holding and its transactions. Categories and your profile are kept.</p>
          <p><b>DELETE TRANSACTIONS</b> — removes transaction history only. Holdings are retained, reset to zero value.</p>
          <p><b>DELETE ACCOUNT</b> — permanently removes every instrument, holding, transaction, and your profile.</p>
        </div>
      </div>
    </SettingsSection>
  </div>
}

// One collapsible card on the Settings page. The page is an accordion — the
// parent owns which single section is open and passes `open` / `onToggle`.
export function SettingsSection({ title, subtitle, open, onToggle, children }: {
  title: string; subtitle: string; open: boolean; onToggle: () => void; children: React.ReactNode
}) {
  return <article className={`panel settings-section${open ? ' open' : ''}`}>
    <button type="button" className="settings-section-head" onClick={onToggle} aria-expanded={open}>
      <span className="settings-section-title"><h3>{title}</h3><span>{subtitle}</span></span>
      <span className="settings-section-toggle">{toggleLabel(open)}</span>
    </button>
    {open && <div className="settings-section-body">{children}</div>}
  </article>
}
