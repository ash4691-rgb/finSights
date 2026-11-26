import { useState } from 'react'
import { ReturnsPanel } from './ReturnsPanel'

type Tab = 'returns'
const TABS: { key: Tab; label: string }[] = [
  { key: 'returns', label: 'Returns' },
]

// Analysis: slower, deliberate views over the whole portfolio (returns, tax, allocation), as
// opposed to Signals, which is the "what needs attention now" feed.
export function AnalysisView({ displayCurrency, dataVersion }: { displayCurrency: string; dataVersion: number }) {
  const [tab, setTab] = useState<Tab>('returns')
  return <>
    <div className="format-tabs" role="tablist">
      {TABS.map(t => <button key={t.key} type="button" role="tab" aria-selected={t.key === tab}
        className={t.key === tab ? 'active' : ''} onClick={() => setTab(t.key)}>{t.label}</button>)}
    </div>
    {tab === 'returns' && <ReturnsPanel displayCurrency={displayCurrency} dataVersion={dataVersion} />}
  </>
}
