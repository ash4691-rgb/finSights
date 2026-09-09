import { useEffect, useRef, useState } from 'react'
import type * as React from 'react'
import type { ReactNode } from 'react'
import { PAGE_LAYOUT } from './layout-config'
import type { Widget } from './layout-config'
import { WIDGET_TYPES, WidgetView, AddWidgetModal } from './widgets'
import type { PageDataSource } from './widgets'
import { saveLayout } from './layout-api'

// ---------------------------------------------------------------------------
// Editable layout — per-page "Edit layout" mode. Each page is a set of zones
// (a page zone = its stack of sections; a grid zone = a row of cards inside a
// section, or — per PAGE_LAYOUT — a grid of user-configurable widget panels).
// Within a zone the user can drag items to reorder them, and — via edge /
// corner handles — resize each item horizontally (width snaps to a 12-column
// grid), vertically (free height, content scrolls), or diagonally.
// Preferences write through to localStorage (instant, offline) and, debounced,
// to the backend per page via saveLayout — hydrated back in on load by
// hydrateLayouts(). Nothing crosses from one zone to another.
// ---------------------------------------------------------------------------
export const LAYOUT_KEY = 'finsights-layout-v1'
export const MIN_SPAN = 1, MAX_SPAN = 12, MIN_ITEM_HEIGHT = 96
export const clampSpan = (n: number) => Math.max(MIN_SPAN, Math.min(MAX_SPAN, Math.round(n)))
export type ResizeMode = 'e' | 's' | 'se'
export type ZonePref = { order: string[]; spans: Record<string, number>; heights?: Record<string, number>; widgets?: Record<string, Widget> }
export type LayoutState = Record<string, ZonePref>
export type ZoneItem = { key: string; span: number; height?: number }

export function readLayout(): LayoutState {
  try { const raw = localStorage.getItem(LAYOUT_KEY); return raw ? JSON.parse(raw) as LayoutState : {} }
  catch { return {} }
}
export function writeLayout(next: LayoutState) {
  try { localStorage.setItem(LAYOUT_KEY, JSON.stringify(next)) } catch { /* storage unavailable */ }
}
export function clearPageLayout(page: string) {
  const next = readLayout()
  for (const key of Object.keys(next)) if (key.startsWith(`${page}/`)) delete next[key]
  writeLayout(next)
  void saveLayout(page, {})
}

// Overlay the backend's per-page layout rows (from fetchLayouts()) onto localStorage before
// first paint, so a signed-in user's saved arrangement follows them across devices.
export function hydrateLayouts(byPage: Record<string, unknown>) {
  const all = readLayout()
  for (const [page, config] of Object.entries(byPage)) {
    if (!config || typeof config !== 'object') continue
    for (const key of Object.keys(all)) if (key.startsWith(`${page}/`)) delete all[key]
    Object.assign(all, config as LayoutState)
  }
  writeLayout(all)
}

// Merge a saved preference over the defaults: known keys in their saved order
// first, then any default keys the save didn't know about, appended in default
// order. Keys no longer in `defaults` are dropped from the render list (but
// left in storage, so a transient view — filtered brokers, say — doesn't wipe
// them). Span falls back to the default span; height to unset (auto).
export function mergeZone(defaults: ZoneItem[], pref?: ZonePref): ZoneItem[] {
  const defaultByKey = new Map(defaults.map(d => [d.key, d]))
  const savedOrder = (pref?.order ?? []).filter(k => defaultByKey.has(k))
  const seen = new Set(savedOrder)
  const order = [...savedOrder, ...defaults.map(d => d.key).filter(k => !seen.has(k))]
  return order.map(key => ({
    key,
    span: clampSpan(pref?.spans?.[key] ?? defaultByKey.get(key)!.span),
    height: pref?.heights?.[key],
  }))
}

function prefFrom(items: ZoneItem[], widgets?: Record<string, Widget>): ZonePref {
  const pref: ZonePref = {
    order: items.map(i => i.key),
    spans: Object.fromEntries(items.map(i => [i.key, i.span])),
    heights: Object.fromEntries(items.flatMap(i => i.height != null ? [[i.key, i.height]] : [])),
  }
  if (widgets && Object.keys(widgets).length) pref.widgets = widgets
  return pref
}

// Seeded widget id = `${zoneKey}:seed:${index}` — stable across reloads, so a seed widget is
// reconfigurable (Edit) rather than re-created each time storage is empty.
function seedWidgetPref(zoneKey: string, seed: Omit<Widget, 'id'>[]): ZonePref {
  const widgets: Record<string, Widget> = {}, spans: Record<string, number> = {}, heights: Record<string, number> = {}
  const order = seed.map((w, i) => {
    const id = `${zoneKey}:seed:${i}`
    widgets[id] = { ...w, id }
    spans[id] = WIDGET_TYPES[w.subType].defaultSpan
    heights[id] = WIDGET_TYPES[w.subType].defaultHeight
    return id
  })
  return { order, spans, heights, widgets }
}

// Debounced (~600ms) per-page backend sync — fires at most once per page per burst of moves/
// resizes/widget edits, mirroring what's now in that page's zones in localStorage.
const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {}
function schedulePageSave(page: string) {
  clearTimeout(saveTimers[page])
  saveTimers[page] = setTimeout(() => {
    const all = readLayout()
    const slice: LayoutState = {}
    for (const key of Object.keys(all)) if (key.startsWith(`${page}/`)) slice[key] = all[key]
    void saveLayout(page, slice)
  }, 600)
}

// `widgetSeed` marks a widget zone: item list + persisted `widgets` map are self-managed
// (derived from storage / the seed) rather than from a page-supplied `defaults` list.
export function useZoneLayout(zoneKey: string, defaults: ZoneItem[], nonce: number, widgetSeed?: Omit<Widget, 'id'>[]) {
  const defaultsRef = useRef(defaults)
  defaultsRef.current = defaults
  const seedRef = useRef(widgetSeed)
  seedRef.current = widgetSeed
  const page = zoneKey.split('/')[0]

  const load = (): { items: ZoneItem[]; widgets: Record<string, Widget> } => {
    if (seedRef.current) {
      let pref = readLayout()[zoneKey]
      if (!pref?.widgets || !Object.keys(pref.widgets).length) pref = seedWidgetPref(zoneKey, seedRef.current)
      const widgetDefaults = Object.entries(pref.widgets!).map(([id, w]) => ({ key: id, span: WIDGET_TYPES[w.subType].defaultSpan }))
      return { items: mergeZone(widgetDefaults, pref), widgets: pref.widgets! }
    }
    return { items: mergeZone(defaultsRef.current, readLayout()[zoneKey]), widgets: {} }
  }

  const [state, setState] = useState(load)
  // Re-read from storage when the zone changes or a Reset bumps the nonce.
  useEffect(() => { setState(load()) }, [zoneKey, nonce])
  // Re-merge if the default set itself changes (e.g. brokers loaded in) — keep the user's
  // current order / spans / heights. Only meaningful for non-widget zones.
  const sig = defaults.map(d => `${d.key}:${d.span}`).join('|')
  useEffect(() => {
    if (seedRef.current) return
    setState(current => ({ items: mergeZone(defaultsRef.current, prefFrom(current.items)), widgets: {} }))
  }, [sig])

  const persist = (items: ZoneItem[], widgets: Record<string, Widget>) => {
    const all = readLayout()
    all[zoneKey] = prefFrom(items, widgets)
    writeLayout(all)
    schedulePageSave(page)
  }
  const move = (fromKey: string, toKey: string) => setState(({ items, widgets }) => {
    if (fromKey === toKey) return { items, widgets }
    const from = items.findIndex(i => i.key === fromKey), to = items.findIndex(i => i.key === toKey)
    if (from === -1 || to === -1) return { items, widgets }
    const next = [...items]
    next.splice(to, 0, next.splice(from, 1)[0])
    persist(next, widgets); return { items: next, widgets }
  })
  // patch.height === null clears the manual height (back to auto).
  const resize = (key: string, patch: { span?: number; height?: number | null }) => setState(({ items, widgets }) => {
    const next = items.map(i => i.key !== key ? i : {
      ...i,
      span: patch.span != null ? clampSpan(patch.span) : i.span,
      height: patch.height === null ? undefined : (patch.height != null ? Math.round(patch.height) : i.height),
    })
    persist(next, widgets); return { items: next, widgets }
  })
  const addWidget = (w: Widget) => setState(({ items, widgets }) => {
    const nextWidgets = { ...widgets, [w.id]: w }
    const nextItems = [...items, { key: w.id, span: WIDGET_TYPES[w.subType].defaultSpan, height: WIDGET_TYPES[w.subType].defaultHeight }]
    persist(nextItems, nextWidgets); return { items: nextItems, widgets: nextWidgets }
  })
  const removeWidget = (id: string) => setState(({ items, widgets }) => {
    const nextWidgets = { ...widgets }
    delete nextWidgets[id]
    const nextItems = items.filter(i => i.key !== id)
    persist(nextItems, nextWidgets); return { items: nextItems, widgets: nextWidgets }
  })
  const updateWidget = (id: string, patch: Partial<Widget>) => setState(({ items, widgets }) => {
    const nextWidgets = { ...widgets, [id]: { ...widgets[id], ...patch } }
    persist(items, nextWidgets); return { items, widgets: nextWidgets }
  })
  return { items: state.items, widgets: state.widgets, move, resize, addWidget, removeWidget, updateWidget }
}

export type DragState = {
  key: string; mode: ResizeMode
  startX: number; startY: number; startSpan: number; startHeight: number
  span: number; height: number
}

export function LayoutZone({ zoneKey, editing, nonce, defaults = [], render, dataSource, className }: {
  zoneKey: string; editing: boolean; nonce: number; defaults?: ZoneItem[]
  render?: Record<string, ReactNode>; dataSource?: PageDataSource; className?: string
}) {
  const page = zoneKey.split('/')[0] as keyof typeof PAGE_LAYOUT
  const config = PAGE_LAYOUT[page]?.[zoneKey]
  const widgetConfig = config?.kind === 'widget' ? config : undefined
  const isWidgetZone = !!widgetConfig
  const { items, widgets, move, resize, addWidget, removeWidget, updateWidget } =
    useZoneLayout(zoneKey, defaults, nonce, widgetConfig?.seed)
  const zoneRef = useRef<HTMLDivElement>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)
  const [rz, setRz] = useState<DragState | null>(null)
  const [addingWidget, setAddingWidget] = useState(false)
  const [editingWidgetId, setEditingWidgetId] = useState<string | null>(null)

  const beginResize = (e: React.PointerEvent, item: ZoneItem, mode: ResizeMode) => {
    e.preventDefault(); e.stopPropagation()
    const itemEl = (e.currentTarget as HTMLElement).parentElement as HTMLElement
    const startHeight = itemEl.getBoundingClientRect().height
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId) } catch { /* older browsers */ }
    setRz({ key: item.key, mode, startX: e.clientX, startY: e.clientY, startSpan: item.span, startHeight, span: item.span, height: startHeight })
  }
  const moveResize = (e: React.PointerEvent) => setRz(d => {
    if (!d) return d
    const colW = (zoneRef.current?.getBoundingClientRect().width ?? 1) / 12
    const span = (d.mode === 'e' || d.mode === 'se') ? clampSpan(d.startSpan + (e.clientX - d.startX) / colW) : d.startSpan
    const height = (d.mode === 's' || d.mode === 'se') ? Math.max(MIN_ITEM_HEIGHT, d.startHeight + (e.clientY - d.startY)) : d.height
    return { ...d, span, height }
  })
  const endResize = () => setRz(d => {
    if (d) resize(d.key, { span: d.span, height: d.mode === 'e' ? undefined : d.height })
    return null
  })

  const visibleItems = isWidgetZone ? items : items.filter(i => render?.[i.key] != null)
  const editingWidget = editingWidgetId ? widgets[editingWidgetId] : undefined

  return <div ref={zoneRef} className={`layout-zone${editing ? ' editing' : ''}${rz ? ' resizing' : ''}${className ? ` ${className}` : ''}`}>
    {visibleItems.map(item => {
      const live = rz && rz.key === item.key ? rz : null
      const span = live ? live.span : item.span
      const height = live ? live.height : item.height
      const style = { '--span': span, ...(height != null ? { height: `${height}px`, overflow: 'auto' } : null) } as React.CSSProperties
      const widget = widgets[item.key]
      return <div key={item.key}
        className={`layout-item${overKey === item.key ? ' drag-over' : ''}${live ? ' resizing' : ''}`}
        style={style}
        onDragOver={e => { if (editing && dragKey) { e.preventDefault(); setOverKey(item.key) } }}
        onDragLeave={() => setOverKey(cur => cur === item.key ? null : cur)}
        onDrop={e => { if (editing && dragKey) { e.preventDefault(); move(dragKey, item.key); setDragKey(null); setOverKey(null) } }}>
        {editing && <div className="layout-item-bar">
          <span className="drag-handle" draggable onDragStart={() => setDragKey(item.key)}
            onDragEnd={() => { setDragKey(null); setOverKey(null) }} title="Drag to reorder">⠿</span>
          {isWidgetZone && widget &&
            <button type="button" className="layout-item-edit" onClick={() => setEditingWidgetId(widget.id)}>Edit</button>}
        </div>}
        {isWidgetZone
          ? widget && <article className="panel widget-panel">
              <div className="panel-heading">
                <h3>{widget.title}</h3>
                {editing && widget.deletable &&
                  <button type="button" className="widget-delete" title="Delete widget" aria-label="Delete widget" onClick={() => removeWidget(widget.id)}>×</button>}
              </div>
              {dataSource && <WidgetView widget={widget} dataSource={dataSource} />}
            </article>
          : render![item.key]}
        {editing && (['e', 's', 'se'] as ResizeMode[]).map(mode => (
          <span key={mode} className={`layout-resize layout-resize-${mode}`}
            onPointerDown={e => beginResize(e, { ...item, span, height }, mode)}
            onPointerMove={moveResize} onPointerUp={endResize} onLostPointerCapture={endResize}
            onDoubleClick={() => mode !== 'e' && resize(item.key, { height: null })}
            title={mode === 'e' ? 'Drag to set width' : mode === 's' ? 'Drag to set height · double-click to reset' : 'Drag to resize · double-click to reset height'} />
        ))}
      </div>
    })}
    {isWidgetZone && editing && <div className="layout-item widget-add-item" style={{ '--span': 3 } as React.CSSProperties}>
      <button type="button" className="widget-add-tile" onClick={() => setAddingWidget(true)}>+ Add widget</button>
    </div>}
    {isWidgetZone && dataSource && (addingWidget || editingWidget) && <AddWidgetModal
      dataSource={dataSource}
      initial={editingWidget}
      onAdd={w => { if (editingWidget) updateWidget(editingWidget.id, w); else addWidget(w); setAddingWidget(false); setEditingWidgetId(null) }}
      onClose={() => { setAddingWidget(false); setEditingWidgetId(null) }} />}
  </div>
}
