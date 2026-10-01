// A small, non-blocking corner card offering UserOnboarding's app-concepts tour — replaces
// auto-popping that 6-step modal the instant a user lands in the app. Unlike a modal, this never
// covers the page or steals focus: the user can ignore it and start using FinSights immediately,
// and it only opens the actual tour if they click into it.
export function TourNudge({ onDismiss, onStart, stackedAboveGoku }: { onDismiss: () => void; onStart: () => void; stackedAboveGoku: boolean }) {
  return <div className={`tour-nudge${stackedAboveGoku ? ' tour-nudge-above-goku' : ''}`}>
    <button type="button" className="tour-nudge-close" onClick={onDismiss} aria-label="Dismiss">×</button>
    <p className="tour-nudge-title">New here?</p>
    <p className="hint">A 60-second tour of the five ideas FinSights is built on — Overview, Holdings, Transactions, Categories and Insights.</p>
    <div className="tour-nudge-actions">
      <button type="button" className="outline" onClick={onDismiss}>Maybe later</button>
      <button type="button" className="primary" onClick={onStart}>Take the tour</button>
    </div>
  </div>
}
