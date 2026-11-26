import { useEffect, useState } from 'react'
import { api } from '../api'
import { money, since } from '../util'
import { InfoTip } from '../ui'
import type { ReturnFigure, ReturnsReport } from '../types'

export function xirrText(figure: ReturnFigure): string {
  if (figure.xirrPercent == null) return '—'
  const sign = figure.xirrPercent > 0 ? '+' : ''
  return `${sign}${figure.xirrPercent.toFixed(2)}%`
}

function toneOf(value: number | null | undefined): string {
  if (value == null || value === 0) return ''
  return value > 0 ? 'positive' : 'negative'
}

function FigureCells({ figure, currency }: { figure: ReturnFigure; currency: string }) {
  return <>
    <td className={toneOf(figure.xirrPercent)} title={figure.note ?? undefined}>{xirrText(figure)}</td>
    <td>{money(figure.netInvested, currency)}</td>
    <td>{money(figure.currentValue, currency)}</td>
    <td className={toneOf(figure.gain)}>{money(figure.gain, currency)}</td>
    <td>{figure.since ? since(figure.since) : '—'}</td>
  </>
}

const HEADERS = ['XIRR (p.a.)', 'Net invested', 'Current value', 'Gain', 'Since']

export function ReturnsPanel({ displayCurrency, dataVersion }: { displayCurrency: string; dataVersion: number }) {
  const [report, setReport] = useState<ReturnsReport | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    setError('')
    api<ReturnsReport>(`/api/returns?currency=${displayCurrency}`)
      .then(setReport)
      .catch(e => { setReport(null); setError(e instanceof Error ? e.message : 'Could not load returns') })
  }, [displayCurrency, dataVersion])

  if (error) return <p className="form-error">{error}</p>
  if (!report) return <p className="hint">Calculating returns…</p>
  const { portfolio, currency } = report

  return <>
    <section className="hero">
      <div>
        <p>Portfolio XIRR <InfoTip text="Money-weighted annual return: accounts for when each rupee went in or came out, unlike simple P/L. Built from your transactions plus today's value." /></p>
        <h2 className={toneOf(portfolio.xirrPercent)}>{xirrText(portfolio)}</h2>
        <span>{money(portfolio.gain, currency)} gain on {money(portfolio.netInvested, currency)} net invested</span>
        {portfolio.note && <p className="hint">{portfolio.note}</p>}
      </div>
    </section>

    <section className="panel">
      <div className="panel-heading"><h3>By category</h3><span>{report.categories.length}</span></div>
      {report.categories.length === 0 ? <p className="hint">No asset holdings yet.</p>
        : <div className="table-panel"><table>
          <thead><tr><th>Category</th>{HEADERS.map(h => <th key={h}>{h}</th>)}</tr></thead>
          <tbody>{report.categories.map(c => <tr key={c.categoryId}><td>{c.categoryName}</td><FigureCells figure={c.figure} currency={currency} /></tr>)}</tbody>
        </table></div>}
    </section>

    <section className="panel">
      <div className="panel-heading"><h3>By holding</h3><span>{report.holdings.length}</span></div>
      {report.holdings.length > 0 && <div className="table-panel"><table>
        <thead><tr><th>Holding</th><th>Category</th>{HEADERS.map(h => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{report.holdings.map(h => <tr key={h.holdingId}><td>{h.name}</td><td>{h.categoryName}</td><FigureCells figure={h.figure} currency={currency} /></tr>)}</tbody>
      </table></div>}
      <p className="hint">Hover a “—” to see why a return isn't shown. Holdings under 30 days old aren't annualised.</p>
    </section>
  </>
}
