import { useEffect, useRef, useState } from 'react'
import { Field } from './ui'
import { Counter, PieChart, BarChart, TimeseriesChart } from './charts'
import type { Widget, WidgetSubType, WidgetQuery } from './layout-config'

export type AttrKind = 'dimension' | 'measure' | 'series'
export type Attr = { key: string; label: string; kind: AttrKind }
export type WidgetData =
  | { kind: 'breakdown'; rows: { label: string; value: number }[] }
  | { kind: 'series'; series: { label: string; points: { t: string; v: number }[] }[] }
  | { kind: 'scalar'; value: number; delta?: number }
export interface PageDataSource { attributes: Attr[]; resolve(w: Widget): WidgetData }

export const MAX_SERIES = 3
// query.series was a single string before multi-metric support — coerce old saved widgets too.
export function seriesKeysOf(query: WidgetQuery): string[] {
  const v = query.series as unknown
  if (Array.isArray(v)) return v as string[]
  if (typeof v === 'string' && v) return [v]
  return []
}

export const WIDGET_TYPES: Record<WidgetSubType, { label: string; icon: string; needs: AttrKind[]; defaultSpan: number; defaultHeight: number }> = {
  counter: { label: 'Counter', icon: '#', needs: ['measure'], defaultSpan: 3, defaultHeight: 140 },
  'pie-chart': { label: 'Pie chart', icon: '◔', needs: ['dimension', 'measure'], defaultSpan: 4, defaultHeight: 300 },
  histogram: { label: 'Bar chart', icon: '▤', needs: ['dimension', 'measure'], defaultSpan: 6, defaultHeight: 300 },
  '2d-graph': { label: 'Line graph', icon: '∿', needs: ['series'], defaultSpan: 12, defaultHeight: 300 },
}

export function WidgetView({ widget, dataSource }: { widget: Widget; dataSource: PageDataSource }) {
  const def = WIDGET_TYPES[widget.subType]
  const missingKind = def.needs.find(kind => kind === 'series' ? seriesKeysOf(widget.query).length === 0 : !widget.query[kind])
  if (missingKind) return <p className="hint widget-placeholder">Pick a {missingKind}</p>
  const data = dataSource.resolve(widget)
  if (data.kind === 'scalar') return <Counter value={data.value} delta={data.delta} />
  if (data.kind === 'series') return <TimeseriesChart series={data.series} />
  return widget.subType === 'pie-chart'
    ? <PieChart rows={data.rows} />
    : <BarChart rows={data.rows} total={data.rows.reduce((sum, r) => sum + Math.abs(r.value), 0)} />
}

// Type-ahead over a static `attributes` list, filtered to one AttrKind. Copies the markup +
// open/close behaviour of SymbolSearchInput (ui.tsx), reusing its .tag-input / .tag-menu CSS.
export function AttrPicker({ kind, attributes, value, onChange }: {
  kind: AttrKind; attributes: Attr[]; value?: string; onChange: (key: string) => void
}) {
  const options = attributes.filter(a => a.kind === kind)
  const selected = options.find(a => a.key === value)
  const [query, setQuery] = useState(selected?.label ?? '')
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => { setQuery(selected?.label ?? '') }, [value])
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const q = query.trim().toLowerCase()
  const matches = options.filter(a => !q || a.label.toLowerCase().includes(q))
  const pick = (a: Attr) => { setQuery(a.label); onChange(a.key); setOpen(false) }

  return <div className="tag-input" ref={boxRef}>
    <div className="tag-input-field">
      <input value={query} placeholder={`Search ${kind}s…`}
        onFocus={() => setOpen(true)}
        onChange={e => { setQuery(e.target.value); setOpen(true) }} />
      {open && matches.length > 0 && <ul className="tag-menu">
        {matches.map(a => <li key={a.key}><button type="button" onMouseDown={e => e.preventDefault()} onClick={() => pick(a)}>{a.label}</button></li>)}
      </ul>}
    </div>
  </div>
}

// Checkbox multi-select for a 2d-graph's metrics — up to `max`, each rendered as its own line.
export function SeriesPicker({ attributes, value, onChange, max = MAX_SERIES }: {
  attributes: Attr[]; value: string[]; onChange: (keys: string[]) => void; max?: number
}) {
  const options = attributes.filter(a => a.kind === 'series')
  const toggle = (key: string) => {
    if (value.includes(key)) onChange(value.filter(k => k !== key))
    else if (value.length < max) onChange([...value, key])
  }
  return <div className="series-picker">
    {options.map(a => {
      const checked = value.includes(a.key)
      return <label key={a.key} className={`series-picker-option${checked ? ' checked' : ''}`}>
        <input type="checkbox" checked={checked} disabled={!checked && value.length >= max} onChange={() => toggle(a.key)} />
        {a.label}
      </label>
    })}
    <p className="hint">Up to {max} metrics.</p>
  </div>
}

// Two-step "Add widget" flow (Datadog style): pick a type, then title + one AttrPicker per
// required attribute + a live preview. Reused for editing by passing `initial`.
export function AddWidgetModal({ dataSource, initial, onAdd, onClose }: {
  dataSource: PageDataSource; initial?: Widget; onAdd: (widget: Widget) => void; onClose: () => void
}) {
  const [subType, setSubType] = useState<WidgetSubType | null>(initial?.subType ?? null)
  const [title, setTitle] = useState(initial?.title ?? '')
  const [query, setQuery] = useState<WidgetQuery>(initial?.query ?? {})

  const submit = () => {
    if (!subType) return
    onAdd({ id: initial?.id ?? crypto.randomUUID(), subType, title: title.trim() || WIDGET_TYPES[subType].label, query, deletable: initial?.deletable ?? true })
  }

  return <div className="modal-backdrop"><section className="modal">
    <div className="modal-header">
      <div><p className="eyebrow">{initial ? 'EDIT WIDGET' : 'ADD WIDGET'}</p><h2>{subType ? (title || WIDGET_TYPES[subType].label) : 'Choose a widget type'}</h2></div>
      <button className="close" onClick={onClose}>×</button>
    </div>
    {!subType
      ? <div className="widget-type-grid">
          {(Object.keys(WIDGET_TYPES) as WidgetSubType[]).map(key => <button type="button" key={key} className="widget-type-card" onClick={() => setSubType(key)}>
            <span className="widget-type-icon">{WIDGET_TYPES[key].icon}</span><span>{WIDGET_TYPES[key].label}</span>
          </button>)}
        </div>
      : <>
          <div className="form-grid">
            <Field label="Title" wide><input value={title} maxLength={64} placeholder={WIDGET_TYPES[subType].label} onChange={e => setTitle(e.target.value)} /></Field>
            {WIDGET_TYPES[subType].needs.map(kind => <Field label={kind[0].toUpperCase() + kind.slice(1)} wide key={kind}>
              {kind === 'series'
                ? <SeriesPicker attributes={dataSource.attributes} value={seriesKeysOf(query)} onChange={keys => setQuery(current => ({ ...current, series: keys }))} />
                : <AttrPicker kind={kind} attributes={dataSource.attributes} value={query[kind] as string | undefined} onChange={key => setQuery(current => ({ ...current, [kind]: key }))} />}
            </Field>)}
          </div>
          <div className="widget-preview">
            <WidgetView widget={{ id: 'preview', subType, title: title || WIDGET_TYPES[subType].label, query, deletable: true }} dataSource={dataSource} />
          </div>
          <div className="modal-actions">
            <button type="button" className="outline" onClick={() => setSubType(null)}>← Back</button>
            <button type="button" className="primary" onClick={submit}>{initial ? 'Save changes' : 'Add widget'}</button>
          </div>
        </>}
  </section></div>
}
