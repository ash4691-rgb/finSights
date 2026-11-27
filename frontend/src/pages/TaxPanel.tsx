import { useEffect, useState } from 'react'
import { api } from '../api'
import { money, since, downloadCsv } from '../util'
import { InfoTip } from '../ui'
import type { TaxClass, TaxReport, TaxTerm } from '../types'

export const TAX_CLASS_LABELS: Record<TaxClass, string> = {
  LISTED_EQUITY: 'Listed equity / equity MF',
  OTHER_CAPITAL: 'Other (gold, foreign, property)',
  DEBT_SLAB: 'Debt / fixed income (slab)',
  VIRTUAL_DIGITAL_ASSET: 'Crypto (30% flat)',
  EXEMPT: 'Exempt (EPF, PPF)',
}
const TERM_LABELS: Record<TaxTerm, string> = { SHORT: 'Short-term', LONG: 'Long-term', FLAT: 'Flat 30%', SLAB: 'Slab rate', EXEMPT: 'Exempt' }
const INR = 'INR'
const inr = (value: number) => money(value, INR)
const tone = (value: number) => {
  if (value > 0) return 'positive'
  if (value < 0) return 'negative'
  return ''
}

export function realisedCsvRows(report: TaxReport): (string | number)[][] {
  return report.realised.map(r => [r.name, TAX_CLASS_LABELS[r.taxClass], TERM_LABELS[r.term], r.bought, r.sold, r.units, r.cost, r.proceeds, r.gain])
}

export function TaxPanel({ dataVersion }: { dataVersion: number }) {
  const [year, setYear] = useState('')
  const [report, setReport] = useState<TaxReport | null>(null)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState<string | null>(null)
  const query = year ? `?fy=${encodeURIComponent(year)}` : ''

  useEffect(() => {
    setError('')
    api<TaxReport>(`/api/tax${query}`).then(setReport)
      .catch(e => { setReport(null); setError(e instanceof Error ? e.message : 'Could not load the tax view') })
  }, [query, dataVersion])

  const changeClass = async (holdingId: string, value: string) => {
    setSaving(holdingId); setError('')
    const taxClass = value === '' ? null : value
    try {
      setReport(await api<TaxReport>(`/api/tax/holdings/${holdingId}/class${query}`, { method: 'PUT', body: JSON.stringify({ taxClass }) }))
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save that') }
    finally { setSaving(null) }
  }

  if (!report) return error ? <p className="form-error">{error}</p> : <p className="hint">Working out your gains…</p>
  const s = report.summary
  const exportCsv = () => downloadCsv(`capital-gains-${report.financialYear}.csv`,
    ['Holding', 'Treatment', 'Term', 'Bought', 'Sold', 'Units', 'Cost (INR)', 'Proceeds (INR)', 'Gain (INR)'], realisedCsvRows(report))

  return <>
    {error && <p className="form-error">{error}</p>}
    <div className="panel-heading tax-toolbar">
      <label>Financial year{' '}
        <select value={report.financialYear} onChange={e => setYear(e.target.value)} aria-label="Financial year">
          {report.availableYears.map(y => <option key={y} value={y}>FY {y}</option>)}
        </select>
      </label>
      <button className="outline compact" onClick={exportCsv} disabled={!report.realised.length}>↓ Export gains (CSV)</button>
    </div>

    <section className="metric-grid tax-metrics">
      <article className="metric-card"><p>Estimated tax on gains <InfoTip text="Equity STCG/LTCG, long-term gains on other assets, and crypto at their flat rates. Excludes cess, surcharge and anything at your slab." /></p><strong>{inr(s.estimatedTax)}</strong><small>FY {report.financialYear}</small></article>
      <article className="metric-card"><p>Equity long-term</p><strong className={tone(s.equityLongTerm)}>{inr(s.equityLongTerm)}</strong><small>{inr(s.equityLongTermExemptionLeft)} of {inr(s.equityLongTermExemption)} exemption left</small></article>
      <article className="metric-card"><p>Equity short-term</p><strong className={tone(s.equityShortTerm)}>{inr(s.equityShortTerm)}</strong><small>Tax {inr(s.equityShortTermTax)}</small></article>
      <article className="metric-card"><p>At your slab rate</p><strong>{inr(s.debtSlabGains + s.otherShortTerm + s.interestIncome)}</strong><small>Debt gains, other short-term, interest</small></article>
    </section>

    <section className="panel">
      <div className="panel-heading"><h3>Gains by bucket</h3></div>
      <div className="table-panel"><table>
        <thead><tr><th>Bucket</th><th>Net gain</th><th>Estimated tax</th></tr></thead>
        <tbody>
          <tr><td>Listed equity, short-term</td><td className={tone(s.equityShortTerm)}>{inr(s.equityShortTerm)}</td><td>{inr(s.equityShortTermTax)}</td></tr>
          <tr><td>Listed equity, long-term</td><td className={tone(s.equityLongTerm)}>{inr(s.equityLongTerm)}</td><td>{inr(s.equityLongTermTax)}</td></tr>
          <tr><td>Other assets, long-term</td><td className={tone(s.otherLongTerm)}>{inr(s.otherLongTerm)}</td><td>{inr(s.otherLongTermTax)}</td></tr>
          <tr><td>Other assets, short-term</td><td className={tone(s.otherShortTerm)}>{inr(s.otherShortTerm)}</td><td>Slab rate</td></tr>
          <tr><td>Debt / fixed income</td><td className={tone(s.debtSlabGains)}>{inr(s.debtSlabGains)}</td><td>Slab rate</td></tr>
          <tr><td>Crypto</td><td className={tone(s.virtualDigitalAssetGains)}>{inr(s.virtualDigitalAssetGains)}</td><td>{inr(s.virtualDigitalAssetTax)}</td></tr>
          <tr><td>Interest income</td><td>{inr(s.interestIncome)}</td><td>Slab rate</td></tr>
        </tbody>
      </table></div>
    </section>

    <section className="panel">
      <div className="panel-heading"><h3>Realised this year</h3><span>{report.realised.length}</span></div>
      {report.realised.length === 0 ? <p className="hint">No sales in FY {report.financialYear}.</p>
        : <div className="table-panel"><table>
          <thead><tr><th>Holding</th><th>Term</th><th>Bought</th><th>Sold</th><th>Cost</th><th>Proceeds</th><th>Gain</th></tr></thead>
          <tbody>{report.realised.map((r, i) => <tr key={`${r.holdingId}-${r.sold}-${i}`}>
            <td>{r.name}</td><td>{TERM_LABELS[r.term]}</td><td>{since(r.bought)}</td><td>{since(r.sold)}</td>
            <td>{inr(r.cost)}</td><td>{inr(r.proceeds)}</td><td className={tone(r.gain)}>{inr(r.gain)}</td>
          </tr>)}</tbody>
        </table></div>}
    </section>

    <section className="panel">
      <div className="panel-heading"><h3>Unrealised today <InfoTip text="What you'd book if you sold today, split by how long each lot has been held. Useful for timing a sale or harvesting the yearly equity LTCG exemption." /></h3></div>
      {report.unrealised.length === 0 ? <p className="hint">Nothing open with a cost basis.</p>
        : <div className="table-panel"><table>
          <thead><tr><th>Holding</th><th>Short-term</th><th>Long-term</th><th>Next lot turns long-term</th></tr></thead>
          <tbody>{report.unrealised.map(u => <tr key={u.holdingId}>
            <td>{u.name}</td><td className={tone(u.shortTermGain)}>{inr(u.shortTermGain)}</td>
            <td className={tone(u.longTermGain)}>{inr(u.longTermGain)}</td><td>{u.longTermFrom ? since(u.longTermFrom) : '—'}</td>
          </tr>)}</tbody>
        </table></div>}
    </section>

    {report.interest.length > 0 && <section className="panel">
      <div className="panel-heading"><h3>Interest income</h3></div>
      <div className="table-panel"><table>
        <thead><tr><th>Holding</th><th>Received</th><th>Accrued</th></tr></thead>
        <tbody>{report.interest.map(i => <tr key={i.holdingId}><td>{i.name}</td><td>{inr(i.received)}</td><td>{inr(i.accrued)}</td></tr>)}</tbody>
      </table></div>
    </section>}

    <section className="panel">
      <div className="panel-heading"><h3>How each holding is taxed</h3></div>
      <p className="hint">FinSights guesses from the category, name and ticker. Change any that are wrong.</p>
      <div className="table-panel"><table>
        <thead><tr><th>Holding</th><th>Category</th><th>Treatment</th></tr></thead>
        <tbody>{report.holdings.map(h => <tr key={h.holdingId}>
          <td>{h.name}</td><td>{h.categoryName}</td>
          <td><select aria-label={`Tax treatment for ${h.name}`} disabled={saving === h.holdingId}
            value={h.inferred ? '' : h.taxClass} onChange={e => void changeClass(h.holdingId, e.target.value)}>
            <option value="">Auto: {TAX_CLASS_LABELS[h.taxClass]}</option>
            {(Object.keys(TAX_CLASS_LABELS) as TaxClass[]).map(c => <option key={c} value={c}>{TAX_CLASS_LABELS[c]}</option>)}
          </select></td>
        </tr>)}</tbody>
      </table></div>
    </section>

    <ul className="hint tax-notes">{report.notes.map(n => <li key={n}>{n}</li>)}</ul>
  </>
}
