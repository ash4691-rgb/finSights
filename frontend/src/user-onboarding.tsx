import { useState } from 'react'
import { useEscToClose } from './ui'
import { dismissUserOnboarding } from './layout-api'

// A short, one-time (replays until dismissed) walkthrough of the app's core concepts, shown the
// first time a user enters the app — separate from CustomLayoutOnboarding, which teaches Edit
// Layout mode specifically and only triggers from there. "Don't show this again" is a per-user
// setting on the backend, so it follows the user across devices, same as CustomLayoutOnboarding.
const STEPS: { title: string; body: string }[] = [
  {
    title: 'Welcome to FinSights',
    body: 'A quick tour of the five ideas the whole app is built on — Overview, Holdings, Transactions, Categories and Insights. Skip anytime; you can always find your way around as you go.',
  },
  {
    title: 'Overview',
    body: "Your dashboard: net worth, total assets and liabilities, and how your portfolio's profit or loss is trending — the one page that summarises everything else.",
  },
  {
    title: 'Holdings',
    body: 'Each individual asset or liability you track — a stock, a fund, a fixed deposit, a loan. This is where you add, edit and review what you actually own or owe.',
  },
  {
    title: 'Transactions',
    body: 'The buys, sells, deposits and repayments that build up a holding\'s history over time — quantities, cost basis, realised gains and interest all come from here.',
  },
  {
    title: 'Categories',
    body: 'Group your holdings however makes sense to you — by asset class, goal, or account — to see allocation, liquidity and performance rolled up at that level.',
  },
  {
    title: 'Insights',
    body: 'Trends, notable movers and an action centre for things worth a decision (a maturing fixed deposit, an EMI due soon) — the page for "what should I look at today?"',
  },
]

export function UserOnboarding({ onClose, onDismissForever }: { onClose: () => void; onDismissForever: () => void }) {
  const [step, setStep] = useState(0)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  useEscToClose(onClose)

  const finish = () => {
    if (dontShowAgain) { onDismissForever(); void dismissUserOnboarding() }
    onClose()
  }
  const isLast = step === STEPS.length - 1
  const current = STEPS[step]

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">GETTING STARTED · STEP {step + 1} OF {STEPS.length}</p><h2>{current.title}</h2></div>
      <button className="close" onClick={finish}>×</button>
    </div>
    <p>{current.body}</p>
    <div className="onboarding-dots">
      {STEPS.map((_, i) => <span key={i} className={`onboarding-dot${i === step ? ' active' : ''}`} />)}
    </div>
    <div className="modal-actions">
      <label className="onboarding-dismiss push-start">
        <input type="checkbox" checked={dontShowAgain} onChange={e => setDontShowAgain(e.target.checked)} />
        Don't show this again
      </label>
      <button type="button" className="outline" onClick={finish}>Skip</button>
      <button type="button" className="primary" onClick={() => isLast ? finish() : setStep(s => s + 1)}>{isLast ? 'Finish' : 'Next'}</button>
    </div>
  </section></div>
}
