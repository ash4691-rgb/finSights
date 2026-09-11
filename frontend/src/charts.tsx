import { useRef, useState } from 'react'
import type * as React from 'react'
import { money } from './util'

// Hand-rolled inline-SVG charts for widget panels — zero new npm deps. Matches the visual
// language of NetWorthChart (InsightsView) and BreakdownCard (DashboardView).

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
        {/* Confines the hover experience to the plotted rectangle — never the axis-label gutters. */}
        <rect x={padL} y={padT} width={W - padL - padR} height={H - padT - padB} fill="transparent"
          onPointerMove={onMove} onPointerLeave={() => setHover(null)} onPointerCancel={() => setHover(null)} />
      </svg>
      {hover && <div className={`chart-tooltip${hover.flip ? ' flip' : ''}`} style={{ left: hover.x }}>
        <strong>{shortDate(usable[0].points[hover.index].t)}</strong>
        {usable.map((s, i) => <div className="chart-tooltip-row" key={s.label}><i style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }} />{s.label}: {money(s.points[hover.index].v)}</div>)}
      </div>}
    </div>
  </div>
}
