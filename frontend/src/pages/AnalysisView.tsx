import { useState } from 'react'
import { ReturnsPanel } from './ReturnsPanel'
import { TaxPanel } from './TaxPanel'
import { AllocationPanel } from './AllocationPanel'
import { GoalsPanel } from './GoalsPanel'

type Tab = 'returns' | 'tax' | 'planning'
const TABS: { key: Tab; label: string }[] = [
  { key: 'returns', label: 'Returns' },
  { key: 'tax', label: 'Tax (India)' },
  { key: 'planning', label: 'Allocation & goals' },
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
    {tab === 'tax' && <TaxPanel dataVersion={dataVersion} />}
    {tab === 'planning' && <>
      <AllocationPanel displayCurrency={displayCurrency} dataVersion={dataVersion} />
      <GoalsPanel dataVersion={dataVersion} />
    </>}
  </>
}
