import { useState } from 'react'
import type * as React from 'react'
import { api, API_URL } from '../api'
import { money, since, numeric, toggleLabel } from '../util'
import { Switch } from '../ui'
import type { Settings, Country, Dashboard, Holding, Theme } from '../types'

export function SettingsView({ settings, countries, dashboard, holdings, reload, theme, setTheme }: {
  settings: Settings; countries: Country[]; dashboard: Dashboard | null; holdings: Holding[]
  reload: () => Promise<void>; theme: Theme; setTheme: React.Dispatch<React.SetStateAction<Theme>>
}) {
  const [form, setForm] = useState({
    displayName: settings.displayName, phone: settings.phone || '', country: settings.country,
    numberFormat: settings.numberFormat, notifyEmail: settings.notifyEmail, notifySms: settings.notifySms,
    notifyPush: settings.notifyPush, notifyThresholdPercent: String(settings.notifyThresholdPercent),
  })
  const [status, setStatus] = useState('')
  const [confirmText, setConfirmText] = useState('')
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
      await api('/api/settings', { method: 'PUT', body: JSON.stringify({
        country: form.country, displayName: form.displayName, phone: form.phone, numberFormat: form.numberFormat,
        notifyEmail: form.notifyEmail, notifySms: form.notifySms, notifyPush: form.notifyPush,
        notifyThresholdPercent: numeric(form.notifyThresholdPercent),
      }) })
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
  const deleteAccount = async () => {
    if (confirmText !== 'DELETE') return
    await api('/api/account', { method: 'DELETE' })
    await reload()
  }
  const brokersConnected = new Set(holdings.map(h => h.broker).filter((b): b is string => !!b)).size
  const saveBar = <>
    <button className="primary" onClick={() => void save()} disabled={status === 'saving'}>{status === 'saving' ? 'Saving…' : 'Save changes'}</button>
    {status && status !== 'saving' && <span className="settings-status">{status === 'saved' ? 'Saved.' : status}</span>}
  </>

  return <div className="settings-list">
    <SettingsSection {...sectionProps('User profile')} subtitle="Who you are">
      <div className="settings-field"><label>Display name</label><input value={form.displayName} onChange={e => set('displayName', e.target.value)} /></div>
      <div className="settings-field"><label>Email</label><input value={settings.email} disabled title="Managed by your sign-in provider" /></div>
      <div className="settings-field"><label>Contact number</label><input value={form.phone} onChange={e => set('phone', e.target.value)} placeholder="+91 98765 43210" /></div>
      <div className="settings-field">
        <label>Country of residence</label>
        <select value={form.country} onChange={e => set('country', e.target.value)}>{countries.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}</select>
      </div>
      <p className="hint">Your base currency follows your country: <b>{selectedCountry?.currency ?? settings.baseCurrency}</b>. Any page also has a "View in" dropdown for a one-off switch, using static reference rates.</p>
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
        <div className="panel-heading"><h3>Delete account</h3><span>Cannot be undone</span></div>
        <p className="hint">This permanently removes every instrument, holding, transaction, and your profile. Type <b>DELETE</b> to confirm.</p>
        <div className="settings-field"><input value={confirmText} onChange={e => setConfirmText(e.target.value)} placeholder="DELETE" /></div>
        <button className="danger-btn" onClick={() => void deleteAccount()} disabled={confirmText !== 'DELETE'}>Delete everything</button>
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
