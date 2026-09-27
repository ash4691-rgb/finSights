import { useEffect, useState } from 'react'

// A brief, non-interactive transition shown right after PersonaOnboarding finishes (or is
// skipped) and before UserOnboarding's app-concepts tour starts — gives the persona's starter
// categories a moment to register as "happening" rather than the tour appearing to interrupt
// the questionnaire mid-flow. Purely cosmetic: nothing is fetched here, it just paces the handoff.
const MESSAGES = ['Setting up your profile…', 'Preparing your dashboard…']
const MESSAGE_INTERVAL_MS = 900
const TOTAL_DURATION_MS = 1800

export function OnboardingSetupScreen({ onDone }: { onDone: () => void }) {
  const [messageIndex, setMessageIndex] = useState(0)

  useEffect(() => {
    const messageTimer = setInterval(() => setMessageIndex(i => Math.min(i + 1, MESSAGES.length - 1)), MESSAGE_INTERVAL_MS)
    const doneTimer = setTimeout(onDone, TOTAL_DURATION_MS)
    return () => { clearInterval(messageTimer); clearTimeout(doneTimer) }
  }, [])

  return <div className="loading-screen">
    <div className="mark">F</div>
    <p>{MESSAGES[messageIndex]}</p>
  </div>
}
