import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import type { Settings } from './types'

vi.mock('./persona-onboarding', () => ({
  PersonaOnboarding: ({ onClose, onDismissForever }: { onClose: (c?: string) => void; onDismissForever: () => void }) => <div>
    <p>Profile step</p>
    <button onClick={() => onClose('USD')}>finish profile</button>
    <button onClick={onDismissForever}>never show profile</button>
  </div>,
}))
vi.mock('./risk-assessment', () => ({
  RiskAssessment: ({ onClose, onDone }: { onClose: () => void; onDone: () => void }) => <div>
    <p>Risk step</p>
    <button onClick={onDone}>finish risk</button>
    <button onClick={onClose}>cancel risk</button>
  </div>,
}))
const dismiss = vi.fn()
vi.mock('./layout-api', () => ({ dismissUserOnboarding: () => dismiss() }))

const { GettingStarted, nextPhase, isPending } = await import('./getting-started')

const settings = (over: Partial<Settings> = {}): Settings => ({
  email: 'a@example.com', displayName: 'A', country: 'IN', countryName: 'India', baseCurrency: 'INR', numberFormat: 'INDIAN',
  notifyEmail: true, notifySms: false, notifyPush: false, notifyThresholdPercent: 5, demoMode: true, holdingCount: 0,
  memberSince: '2026-01-01', customLayoutOnboardingDismissed: false, userOnboardingDismissed: false,
  personaOnboardingDismissed: false, riskOnboardingDismissed: false, ...over,
})

describe('getting started', () => {
  afterEach(() => { cleanup(); dismiss.mockReset() })

  test('works out which phase is next', () => {
    expect(nextPhase(settings())).toBe('profile')
    expect(nextPhase(settings(), 'profile')).toBe('risk')
    expect(nextPhase(settings({ riskOnboardingDismissed: true }), 'profile')).toBe('welcome')
    expect(nextPhase(settings({ personaOnboardingDismissed: true, riskOnboardingDismissed: true }))).toBe('welcome')
    expect(nextPhase(settings(), 'welcome')).toBeNull()
    expect(nextPhase(settings({ personaOnboardingDismissed: true, riskOnboardingDismissed: true, userOnboardingDismissed: true }))).toBeNull()
    expect(isPending('risk', settings({ riskOnboardingDismissed: true }))).toBe(false)
  })

  test('runs profile, risk and welcome in one sitting', () => {
    const onReload = vi.fn(); const onSettingsChange = vi.fn(); const onFinished = vi.fn()
    render(<GettingStarted settings={settings()} persona={null} countries={[]} onReload={onReload} onSettingsChange={onSettingsChange} onFinished={onFinished} />)

    fireEvent.click(screen.getByText('never show profile'))
    expect(onSettingsChange).toHaveBeenCalledWith({ personaOnboardingDismissed: true })
    fireEvent.click(screen.getByText('finish profile'))
    expect(onReload).toHaveBeenCalledWith('USD')

    fireEvent.click(screen.getByText('finish risk'))
    expect(onSettingsChange).toHaveBeenCalledWith({ riskOnboardingDismissed: true })
    expect(onReload).toHaveBeenCalledTimes(2)

    expect(screen.getByText("You're set")).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Start using FinSights' }))
    expect(onSettingsChange).toHaveBeenCalledWith({ userOnboardingDismissed: true })
    expect(dismiss).toHaveBeenCalled()
    expect(onFinished).toHaveBeenCalled()
    expect(screen.queryByText("You're set")).not.toBeInTheDocument()
  })

  test('cancelling risk still moves on, and a finished account sees nothing', () => {
    const onFinished = vi.fn()
    render(<GettingStarted settings={settings({ personaOnboardingDismissed: true, userOnboardingDismissed: true })} persona={null}
      countries={[]} onReload={vi.fn()} onSettingsChange={vi.fn()} onFinished={onFinished} />)
    fireEvent.click(screen.getByText('cancel risk'))
    expect(onFinished).toHaveBeenCalled()
    expect(screen.queryByText('Risk step')).not.toBeInTheDocument()
    cleanup()

    const { container } = render(<GettingStarted settings={settings({ personaOnboardingDismissed: true, riskOnboardingDismissed: true, userOnboardingDismissed: true })}
      persona={null} countries={[]} onReload={vi.fn()} onSettingsChange={vi.fn()} onFinished={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
