import { Fragment, useEffect, useRef, useState } from 'react'
import type * as React from 'react'
import { money, since } from './util'
import type { TimelineWeek } from './types'

// Hand-rolled inline-SVG charts for widget panels — zero new npm deps. Matches the visual
// language of NetWorthChart and BreakdownCard (DashboardView).

export function Counter({ value, delta }: { value: number; delta?: number }) {
  return <div className="counter-widget">
    <strong>{money(value)}</strong>
    {delta != null && <span className={delta >= 0 ? 'positive' : 'negative'}>{delta >= 0 ? '▲' : '▼'} {money(Math.abs(delta))}</span>}
  </div>
}

const PIE_COLORS = ['var(--accent)', 'var(--accent-2)', 'var(--positive)', 'var(--warn-text)', 'var(--purple-text)', 'var(--text-3)', 'var(--negative)', 'var(--chip-text)']

function polar(cx: number, cy: number, r: number, angle: number) {
  const rad = (angle - 90) * Math.PI / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}
function donutSlice(cx: number, cy: number, outerR: number, innerR: number, start: number, end: number) {
  const large = end - start > 180 ? 1 : 0
  const o1 = polar(cx, cy, outerR, start), o2 = polar(cx, cy, outerR, end)
  const i1 = polar(cx, cy, innerR, end), i2 = polar(cx, cy, innerR, start)
  return `M ${o1.x} ${o1.y} A ${outerR} ${outerR} 0 ${large} 1 ${o2.x} ${o2.y} L ${i1.x} ${i1.y} A ${innerR} ${innerR} 0 ${large} 0 ${i2.x} ${i2.y} Z`
}

// SVG donut + compact legend. Slices under ~2% are folded into an "Other" wedge.
export function PieChart({ rows }: { rows: { label: string; value: number }[] }) {
  const total = rows.reduce((sum, r) => sum + Math.max(0, r.value), 0)
  if (!total) return <p className="hint">Nothing to chart yet.</p>
  const sorted = [...rows].filter(r => r.value > 0).sort((a, b) => b.value - a.value)
  const kept = sorted.filter(r => r.value / total >= 0.02)
  const restTotal = sorted.filter(r => r.value / total < 0.02).reduce((sum, r) => sum + r.value, 0)
  const slices = restTotal > 0 ? [...kept, { label: 'Other', value: restTotal }] : kept
  const cx = 90, cy = 90, outerR = 80, innerR = 48
  let angle = 0
  const arcs = slices.map((s, i) => {
    const start = angle
    angle += (s.value / total) * 360
    return { ...s, start, end: angle, color: PIE_COLORS[i % PIE_COLORS.length] }
  })
  return <div className="pie-widget">
    <svg viewBox="0 0 180 180" className="pie-svg" role="img" aria-label="Breakdown pie chart">
      {arcs.length === 1
        ? <circle cx={cx} cy={cy} r={(outerR + innerR) / 2} fill="none" stroke={arcs[0].color} strokeWidth={outerR - innerR} />
        : arcs.map(a => <path key={a.label} d={donutSlice(cx, cy, outerR, innerR, a.start, a.end)} fill={a.color} />)}
    </svg>
    <div className="pie-legend">{arcs.map(a => <div className="pie-legend-row" key={a.label}>
      <i style={{ background: a.color }} /><span title={a.label}>{a.label}</span><b>{((a.value / total) * 100).toFixed(0)}%</b>
    </div>)}</div>
  </div>
}

// Horizontal bars, same visual language as BreakdownCard's bar (width relative to `total`).
export function BarChart({ rows, total }: { rows: { label: string; value: number }[]; total: number }) {
  if (!rows.length) return <p className="hint">Nothing to chart yet.</p>
  return <div className="bar-widget">
    {rows.map(r => <div className="bar-row" key={r.label}>
      <span className="bar-label" title={r.label}>{r.label}</span>
      <div className="bar-track"><i style={{ width: `${Math.min(100, total ? (Math.abs(r.value) / total) * 100 : 0)}%` }} /></div>
      <b className={r.value < 0 ? 'negative' : ''}>{money(r.value)}</b>
    </div>)}
  </div>
}

function niceNum(range: number, round: boolean) {
  const exp = Math.floor(Math.log10(range || 1))
  const f = range / 10 ** exp
  const nf = round ? (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) : (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10)
  return nf * 10 ** exp
}
function niceTicks(min: number, max: number, count = 4) {
  if (min === max) { min -= 1; max += 1 }
  const step = niceNum((max - min) / Math.max(1, count - 1), true)
  const niceMin = Math.floor(min / step) * step
  const niceMax = Math.ceil(max / step) * step
  const ticks: number[] = []
  for (let v = niceMin; v <= niceMax + step / 2; v += step) ticks.push(v)
  return ticks
}
const shortDate = (t: string) => new Date(t).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
const SERIES_COLORS = ['var(--accent)', 'var(--accent-2)', 'var(--positive)']
// Slop around the plotted rectangle so a point's dot (radius 4) at the very first/last index, or
// at the value axis's min/max, sits fully inside the hover hit-area instead of half outside it.
const HOVER_PAD = 8

// Interactive line chart — up to 3 metrics, each its own colour — with real axes and a hover
// crosshair/tooltip. The hover hit-area is the plotted rectangle only (an invisible <rect>), so
// the interactive experience never spills into the axis-label margins. Touch-safe (pointer events).
export function TimeseriesChart({ series, caption }: { series: { label: string; points: { t: string; v: number }[] }[]; caption?: string }) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [hover, setHover] = useState<{ index: number; x: number; flip: boolean } | null>(null)
  const usable = series.filter(s => s.points.length >= 2)
  if (!usable.length) return <p className="hint">Not enough data yet.</p>

  const W = 640, H = 220, padL = 56, padR = 12, padT = 14, padB = 28
  const pointCount = usable[0].points.length
  const allValues = usable.flatMap(s => s.points.map(p => p.v))
  const ticks = niceTicks(Math.min(...allValues), Math.max(...allValues))
  const min = ticks[0], max = ticks[ticks.length - 1], span = max - min || 1
  const x = (i: number) => padL + (i / (pointCount - 1)) * (W - padL - padR)
  const y = (v: number) => padT + (1 - (v - min) / span) * (H - padT - padB)
  const lineFor = (s: { points: { t: string; v: number }[] }) =>
    s.points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(1)} ${y(p.v).toFixed(1)}`).join(' ')

  const tickCount = Math.min(6, pointCount)
  const xTickIdx = [...new Set(Array.from({ length: tickCount }, (_, i) => Math.round(i * (pointCount - 1) / (tickCount - 1))))]

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return
    const relX = ((e.clientX - rect.left) / rect.width) * W
    let nearest = 0, best = Infinity
    usable[0].points.forEach((_, i) => { const d = Math.abs(x(i) - relX); if (d < best) { best = d; nearest = i } })
    const px = (x(nearest) / W) * rect.width
    setHover({ index: nearest, x: px, flip: px > rect.width - 130 })
  }

  return <div className="timeseries-widget">
    {caption && <div className="timeseries-caption">{caption}</div>}
    {usable.length > 1 && <div className="timeseries-legend">
      {usable.map((s, i) => <span className="timeseries-legend-item" key={s.label}><i style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }} />{s.label}</span>)}
    </div>}
    <div className="timeseries-plot">
      <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} className="timeseries-svg" role="img" aria-label="Value over time">
        {ticks.map(t => <g key={t}>
          <line x1={padL} x2={W - padR} y1={y(t)} y2={y(t)} className="timeseries-gridline" />
          <text x={padL - 8} y={y(t)} className="timeseries-y-label" textAnchor="end" dominantBaseline="middle">{money(t)}</text>
        </g>)}
        {xTickIdx.map(i => <text key={i} x={x(i)} y={H - 8} className="timeseries-x-label" textAnchor="middle">{shortDate(usable[0].points[i].t)}</text>)}
        {usable.map((s, i) => <path key={s.label} d={lineFor(s)} className="timeseries-line" style={{ stroke: SERIES_COLORS[i % SERIES_COLORS.length] }} vectorEffect="non-scaling-stroke" />)}
        {hover && <line x1={x(hover.index)} x2={x(hover.index)} y1={padT} y2={H - padB} className="timeseries-crosshair" />}
        {hover && usable.map((s, i) => <circle key={s.label} cx={x(hover.index)} cy={y(s.points[hover.index].v)} r={4} className="timeseries-dot" style={{ fill: SERIES_COLORS[i % SERIES_COLORS.length] }} />)}
        {/* Confines the hover experience to the plotted rectangle — never the axis-label gutters —
            but padded a few px past the first/last point so their dots (radius 4) sit fully inside
            the hit area instead of having half their visual circle outside it. */}
        <rect x={padL - HOVER_PAD} y={padT - HOVER_PAD} width={W - padL - padR + HOVER_PAD * 2} height={H - padT - padB + HOVER_PAD * 2} fill="transparent"
          onPointerMove={onMove} onPointerLeave={() => setHover(null)} onPointerCancel={() => setHover(null)} />
      </svg>
      {hover && <div className={`chart-tooltip${hover.flip ? ' flip' : ''}`} style={{ left: hover.x }}>
        <strong>{shortDate(usable[0].points[hover.index].t)}</strong>
        {usable.map((s, i) => <div className="chart-tooltip-row" key={s.label}><i style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }} />{s.label}: {money(s.points[hover.index].v)}</div>)}
      </div>}
    </div>
  </div>
}

// Shared by every hand-rolled inline-SVG line chart (NetWorthChart, PriceHistoryChart): tracks
// the pointer over the plot, snaps to the nearest data point by x, and returns enough to render
// a guide line + dot in the SVG and a fixed-positioned tooltip outside it (so it's never clipped
// by a scrolling ancestor — same technique as InfoTip). `points` must already be projected into
// the chart's own SVG viewBox coordinates.
export function useChartHover(points: { x: number; y: number }[], W: number) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [index, setIndex] = useState<number | null>(null)
  const [tip, setTip] = useState<{ left: number; top: number } | null>(null)

  useEffect(() => { setIndex(null); setTip(null) }, [points.length])

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect || !points.length) return
    const svgX = (e.clientX - rect.left) / rect.width * W
    let nearest = 0, nearestDist = Infinity
    points.forEach((p, i) => { const d = Math.abs(p.x - svgX); if (d < nearestDist) { nearestDist = d; nearest = i } })
    const vw = window.innerWidth || document.documentElement.clientWidth || 1024
    const screenX = rect.left + (points[nearest].x / W) * rect.width
    setIndex(nearest)
    setTip({ left: Math.min(Math.max(screenX, 90), Math.max(vw - 90, 90)), top: rect.top - 10 })
  }
  const onLeave = () => { setIndex(null); setTip(null) }
  return { svgRef, onMove, onLeave, index, tip }
}

// A lightweight inline-SVG line chart of net worth across the snapshot weeks (oldest → newest).
export function NetWorthChart({ weeks }: { weeks: TimelineWeek[] }) {
  const W = 680, H = 150, padX = 10, padTop = 12, padBot = 16
  const values = weeks.map(w => w.netWorth)
  const min = Math.min(...values), max = Math.max(...values)
  const span = max - min || Math.abs(max) || 1
  const x = (i: number) => padX + (i / (weeks.length - 1)) * (W - padX * 2)
  const y = (v: number) => padTop + (1 - (v - min) / span) * (H - padTop - padBot)
  const points = weeks.map((w, i) => ({ x: x(i), y: y(w.netWorth) }))
  const { svgRef, onMove, onLeave, index, tip } = useChartHover(points, W)
  if (weeks.length < 2) return null
  const line = weeks.map((w, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(1)} ${y(w.netWorth).toFixed(1)}`).join(' ')
  const area = `${line} L ${x(weeks.length - 1).toFixed(1)} ${(H - padBot).toFixed(1)} L ${x(0).toFixed(1)} ${(H - padBot).toFixed(1)} Z`
  const growth = weeks[weeks.length - 1].netWorth - weeks[0].netWorth
  const zeroY = min < 0 && max > 0 ? y(0) : null
  const hovered = index != null ? weeks[index] : null
  return <div className="networth-chart">
    <div className="networth-caption">
      <span>Net worth</span>
      <strong className={growth >= 0 ? 'positive' : 'negative'}>{growth >= 0 ? '▲' : '▼'} {money(Math.abs(growth))} over {weeks.length} weeks</strong>
    </div>
    <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="networth-svg" role="img" aria-label="Net worth over time"
        onMouseMove={onMove} onMouseLeave={onLeave}>
      {zeroY != null && <line x1={padX} x2={W - padX} y1={zeroY} y2={zeroY} className="networth-zero" vectorEffect="non-scaling-stroke" />}
      <path d={area} className="networth-area" />
      <path d={line} className="networth-line" vectorEffect="non-scaling-stroke" />
      {hovered && <g>
        <line x1={points[index!].x} x2={points[index!].x} y1={padTop} y2={H - padBot} className="chart-hover-line" vectorEffect="non-scaling-stroke" />
        <circle cx={points[index!].x} cy={points[index!].y} r="4" className="chart-hover-dot" />
      </g>}
    </svg>
    <div className="networth-axis"><span>{since(weeks[0].weekOf)}</span><span>{since(weeks[weeks.length - 1].weekOf)}</span></div>
    {hovered && tip && <div className="chart-tooltip" style={{ left: tip.left, top: tip.top }}>
      <strong>{money(hovered.netWorth)}</strong><span>{since(hovered.weekOf)}</span>
    </div>}
  </div>
}

// Weekly portfolio history. Newest week first; each row expands to the per-category split.
export function TimelineTable({ weeks }: { weeks: TimelineWeek[] }) {
  const [openWeek, setOpenWeek] = useState<string | null>(null)
  const ordered = [...weeks].reverse()
  const delta = (a: number, b?: number) => (b == null ? null : a - b)
  return <div className="table-panel"><table className="timeline-table">
    <thead><tr><th>Week of</th><th>Invested</th><th>Current</th><th>Unrealised P/L</th><th>Net worth</th></tr></thead>
    <tbody>{ordered.map((w, i) => {
      const prev = ordered[i + 1]
      const pl = w.current - w.invested
      const nwMove = delta(w.netWorth, prev?.netWorth)
      const isOpen = openWeek === w.weekOf
      return <Fragment key={w.weekOf}>
        <tr className="clickable-row" onClick={() => setOpenWeek(isOpen ? null : w.weekOf)}>
          <td><strong>{since(w.weekOf)}</strong> <span className="row-caret">{isOpen ? '▴' : '▾'}</span></td>
          <td>{money(w.invested)}</td>
          <td>{money(w.current)}</td>
          <td className={pl >= 0 ? 'positive' : 'negative'}>{pl >= 0 ? '+' : ''}{money(pl)}</td>
          <td>{money(w.netWorth)}{nwMove != null && nwMove !== 0 &&
            <small className={nwMove > 0 ? 'positive' : 'negative'}> {nwMove > 0 ? '▲' : '▼'} {money(Math.abs(nwMove))}</small>}</td>
        </tr>
        {isOpen && <tr className="timeline-detail-row"><td colSpan={5}>
          <table className="timeline-detail"><tbody>
            {w.categories.map(c => {
              const cpl = c.current - c.invested
              const liability = c.kind === 'LIABILITY'
              return <tr key={c.categoryId}>
                <td>{c.categoryName}{liability && <em className="pill-liability">liability</em>}</td>
                <td>{liability ? '—' : money(c.invested)}</td>
                <td>{money(c.current)}</td>
                <td className={liability ? '' : cpl >= 0 ? 'positive' : 'negative'}>
                  {liability ? '' : `${cpl >= 0 ? '+' : ''}${money(cpl)}`}</td>
              </tr>
            })}
          </tbody></table>
        </td></tr>}
      </Fragment>
    })}</tbody>
  </table></div>
}
