import { useState } from 'react'
import { useEscToClose } from './ui'
import { dismissCustomLayoutOnboarding } from './layout-api'

// A short, one-time (replays until dismissed) walkthrough of Edit Layout mode. Every
// edit-layout-capable page (dashboard/insights/brokers) already ships a seeded, deletable
// "Demo section" with two demo widgets (see layout-config.ts) — this tour teaches the user
// what that's for and how the editing tools work, rather than fabricating its own example
// content. Triggered from App.tsx whenever the user enters Edit Layout mode and hasn't
// permanently dismissed it; "Don't show this again" is a per-user setting on the backend,
// so it follows the user across devices.
const STEPS: { title: string; body: string }[] = [
  {
    title: 'Welcome to Edit layout',
    body: "You're now in Edit layout mode. Drag any panel by its ⠿ handle to reorder it, or drag its edge to resize it — every page keeps its own arrangement.",
  },
  {
    title: 'Meet your Demo section',
    body: 'We\'ve added a "Demo section" panel to this page so you have something to experiment with right away. It\'s yours to explore — delete it anytime with the 🗑 in its header once you\'re comfortable.',
  },
  {
    title: 'Try the demo widget',
    body: 'Each card inside a section is a widget. Hover one in edit mode for ✎ (edit what it shows) and 🗑 (remove it), and drag its ⠿ handle or edge to reorder or resize it.',
  },
  {
    title: 'Add your own widgets',
    body: 'Click the + at the top of any section to add a widget of your own — pick a chart type, then the data it should show, with a live preview as you go.',
  },
  {
    title: 'Panels, saving & resetting',
    body: 'The ☰ Layout menu creates a new panel, saves your layout immediately, or resets a page back to its defaults. Click "✓ Done" whenever you\'re finished editing.',
  },
]

export function CustomLayoutOnboarding({ onClose, onDismissForever }: { onClose: () => void; onDismissForever: () => void }) {
  const [step, setStep] = useState(0)
  const [dontShowAgain, setDontShowAgain] = useState(false)
  useEscToClose(onClose)

  const finish = () => {
    if (dontShowAgain) { onDismissForever(); void dismissCustomLayoutOnboarding() }
    onClose()
  }
  const isLast = step === STEPS.length - 1
  const current = STEPS[step]

  return <div className="modal-backdrop"><section className="modal narrow onboarding-tour">
    <div className="modal-header">
      <div><p className="eyebrow">EDIT LAYOUT · STEP {step + 1} OF {STEPS.length}</p><h2>{current.title}</h2></div>
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
