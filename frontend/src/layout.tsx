import { useEffect, useMemo, useRef, useState } from 'react'
import type * as React from 'react'
import type { ReactNode } from 'react'
import { PAGE_LAYOUT, sectionsZoneKeyFor } from './layout-config'
import type { SectionSeed, Widget } from './layout-config'
import { WIDGET_TYPES, WidgetView, AddWidgetModal } from './widgets'
import type { PageDataSource } from './widgets'
import { saveLayout } from './layout-api'
import { Field } from './ui'

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
export type SectionMeta = { title: string; deletable: boolean }
export type ZonePref = {
  order: string[]; spans: Record<string, number>; heights?: Record<string, number>
  widgets?: Record<string, Widget>; sections?: Record<string, SectionMeta>
}
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

function pageSlice(page: string): LayoutState {
  const all = readLayout()
  const slice: LayoutState = {}
  for (const key of Object.keys(all)) if (key.startsWith(`${page}/`)) slice[key] = all[key]
  return slice
}

// Debounced (~600ms) per-page backend sync — fires at most once per page per burst of moves/
// resizes/widget edits, mirroring what's now in that page's zones in localStorage.
const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {}
function schedulePageSave(page: string) {
  clearTimeout(saveTimers[page])
  saveTimers[page] = setTimeout(() => { void saveLayout(page, pageSlice(page)) }, 600)
}

// "Save layout" menu action — skips the debounce so the user gets an immediate save + confirmation.
export function flushPageSave(page: string): Promise<void> {
  clearTimeout(saveTimers[page])
  return saveLayout(page, pageSlice(page))
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

export function LayoutZone({ zoneKey, editing, nonce, defaults = [], render, dataSource, className, forceWidgetSeed,
  sectionTitle, onRenameSection, onDeleteSection }: {
  zoneKey: string; editing: boolean; nonce: number; defaults?: ZoneItem[]
  render?: Record<string, ReactNode>; dataSource?: PageDataSource; className?: string
  forceWidgetSeed?: Omit<Widget, 'id'>[]
  sectionTitle?: string; onRenameSection?: (title: string) => void; onDeleteSection?: () => void
}) {
  const page = zoneKey.split('/')[0] as keyof typeof PAGE_LAYOUT
  const config = PAGE_LAYOUT[page]?.[zoneKey]
  if (config?.kind === 'sections') {
    return <SectionedZone zoneKey={zoneKey} editing={editing} nonce={nonce} dataSource={dataSource} seed={config.seed} className={className} />
  }
  const widgetConfig = config?.kind === 'widget' ? config : undefined
  const isWidgetZone = !!widgetConfig || forceWidgetSeed != null
  const { items, widgets, move, resize, addWidget, removeWidget, updateWidget } =
    useZoneLayout(zoneKey, defaults, nonce, widgetConfig?.seed ?? forceWidgetSeed)
  const zoneRef = useRef<HTMLDivElement>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)
  const [rz, setRz] = useState<DragState | null>(null)
  const [addingWidget, setAddingWidget] = useState(false)
  const [editingWidgetId, setEditingWidgetId] = useState<string | null>(null)
  const [renamingSection, setRenamingSection] = useState(false)
  const [titleDraft, setTitleDraft] = useState('')
  // Guards against a double-commit when Enter triggers commitRename and the resulting unmount
  // of the (still-focused) input then fires blur too.
  const committingRef = useRef(false)
  const startRename = () => { committingRef.current = false; setTitleDraft(sectionTitle ?? ''); setRenamingSection(true) }
  const commitRename = () => {
    if (committingRef.current) return
    committingRef.current = true
    setRenamingSection(false)
    const next = titleDraft.trim()
    if (next && next !== sectionTitle) onRenameSection?.(next)
  }
  const cancelRename = () => { committingRef.current = true; setRenamingSection(false) }

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

  return <>
    {isWidgetZone && <div className="widget-section-head">
      {sectionTitle != null
        ? (renamingSection
            ? <input className="widget-section-title-input" autoFocus value={titleDraft} maxLength={48}
                onChange={e => setTitleDraft(e.target.value)} onBlur={commitRename}
                onKeyDown={e => { if (e.key === 'Enter') commitRename(); else if (e.key === 'Escape') cancelRename() }} />
            : <h4>{sectionTitle}</h4>)
        : <span />}
      {editing && <div className="widget-section-actions">
        <button type="button" className="icon-btn add" title="Add widget" aria-label="Add widget" onClick={() => setAddingWidget(true)}>+</button>
        {sectionTitle != null && onRenameSection &&
          <button type="button" className="icon-btn edit" title="Rename panel" aria-label="Rename panel" onClick={startRename}>✎</button>}
        {onDeleteSection &&
          <button type="button" className="icon-btn delete" title="Delete panel" aria-label="Delete panel" onClick={onDeleteSection}>🗑</button>}
      </div>}
    </div>}
    <div ref={zoneRef} className={`layout-zone${editing ? ' editing' : ''}${rz ? ' resizing' : ''}${className ? ` ${className}` : ''}`}>
      {visibleItems.map(item => {
        const live = rz && rz.key === item.key ? rz : null
        const span = live ? live.span : item.span
        const height = live ? live.height : item.height
        const widget = widgets[item.key]
        // The height/overflow clamp lives on an *inner* wrapper, not `.layout-item` itself — so the
        // item's own box (the resize handles' containing block, hanging slightly outside its edge
        // to stay grabbable) never gets clipped by its own content's overflow. Horizontal overflow
        // is always hidden (a widget can never spill past its section); vertical only scrolls once
        // a manual height is set.
        const contentStyle = height != null
          ? { height: `${height}px`, overflowY: 'auto' as const, overflowX: 'hidden' as const }
          : undefined
        return <div key={item.key}
          className={`layout-item${overKey === item.key ? ' drag-over' : ''}${live ? ' resizing' : ''}`}
          style={{ '--span': span } as React.CSSProperties}
          onDragOver={e => { if (editing && dragKey) { e.preventDefault(); setOverKey(item.key) } }}
          onDragLeave={() => setOverKey(cur => cur === item.key ? null : cur)}
          onDrop={e => { if (editing && dragKey) { e.preventDefault(); move(dragKey, item.key); setDragKey(null); setOverKey(null) } }}>
          <div className="layout-item-content" style={contentStyle}>
            {editing && <div className="layout-item-bar">
              <span className="icon-btn drag" draggable onDragStart={() => setDragKey(item.key)}
                onDragEnd={() => { setDragKey(null); setOverKey(null) }} title="Drag to reorder">⠿</span>
              {isWidgetZone && widget && <div className="widget-actions">
                <button type="button" className="icon-btn edit" title="Edit widget" aria-label="Edit widget" onClick={() => setEditingWidgetId(widget.id)}>✎</button>
                {widget.deletable &&
                  <button type="button" className="icon-btn delete" title="Delete widget" aria-label="Delete widget" onClick={() => removeWidget(widget.id)}>🗑</button>}
              </div>}
            </div>}
            {isWidgetZone
              ? widget && <article className="panel widget-panel">
                  <div className="panel-heading">
                    <h3>{widget.title}</h3>
                  </div>
                  {dataSource && <WidgetView widget={widget} dataSource={dataSource} />}
                </article>
              : render![item.key]}
          </div>
          {editing && (['e', 's', 'se'] as ResizeMode[]).map(mode => (
            <span key={mode} className={`layout-resize layout-resize-${mode}`}
              onPointerDown={e => beginResize(e, { ...item, span, height }, mode)}
              onPointerMove={moveResize} onPointerUp={endResize} onLostPointerCapture={endResize}
              onDoubleClick={() => mode !== 'e' && resize(item.key, { height: null })}
              title={mode === 'e' ? 'Drag to set width' : mode === 's' ? 'Drag to set height · double-click to reset' : 'Drag to resize · double-click to reset height'} />
          ))}
        </div>
      })}
    </div>
    {isWidgetZone && dataSource && (addingWidget || editingWidget) && <AddWidgetModal
      dataSource={dataSource}
      initial={editingWidget}
      onAdd={w => { if (editingWidget) updateWidget(editingWidget.id, w); else addWidget(w); setAddingWidget(false); setEditingWidgetId(null) }}
      onClose={() => { setAddingWidget(false); setEditingWidgetId(null) }} />}
  </>
}

// Deterministic id for the nth seeded section of a zone, or a fresh one for a user-created panel.
const seedSectionId = (zoneKey: string, i: number) => `${zoneKey}:section:${i}`

function seedSectionsPref(zoneKey: string, seed: SectionSeed[]): { order: string[]; sections: Record<string, SectionMeta> } {
  const sections: Record<string, SectionMeta> = {}
  const order = seed.map((s, i) => {
    const id = seedSectionId(zoneKey, i)
    sections[id] = { title: s.title, deletable: s.deletable }
    return id
  })
  return { order, sections }
}

// A page's widget zone as multiple named, independently addable/removable sections — each its
// own widget grid (LayoutZone in forceWidgetSeed mode). The section list itself (order + titles +
// deletable flags) is persisted the same way as a plain ZonePref, at `zoneKey`; each section's
// widgets live in their own nested zone keyed `${sectionId}/widgets`.
function useSectionList(zoneKey: string, seed: SectionSeed[], nonce: number) {
  const seedRef = useRef(seed)
  seedRef.current = seed
  const page = zoneKey.split('/')[0]

  const load = (): { order: string[]; sections: Record<string, SectionMeta> } => {
    const pref = readLayout()[zoneKey]
    if (pref?.sections) return { order: pref.order, sections: pref.sections }
    return seedSectionsPref(zoneKey, seedRef.current)
  }

  const [state, setState] = useState(load)
  useEffect(() => { setState(load()) }, [zoneKey, nonce])

  const persist = (order: string[], sections: Record<string, SectionMeta>) => {
    const all = readLayout()
    all[zoneKey] = { order, spans: {}, sections }
    writeLayout(all)
    schedulePageSave(page)
  }
  const removeSection = (id: string) => setState(({ order, sections }) => {
    const nextOrder = order.filter(k => k !== id)
    const nextSections = { ...sections }
    delete nextSections[id]
    persist(nextOrder, nextSections)
    const all = readLayout()
    delete all[`${id}/widgets`]
    writeLayout(all)
    return { order: nextOrder, sections: nextSections }
  })
  const renameSection = (id: string, title: string) => setState(({ order, sections }) => {
    const nextSections = { ...sections, [id]: { ...sections[id], title } }
    persist(order, nextSections)
    return { order, sections: nextSections }
  })
  return { order: state.order, sections: state.sections, removeSection, renameSection }
}

// Header-level "Create a panel" (page's Layout menu) — appends a fresh, deletable, empty panel
// under the given name. A plain function (not a hook) so App.tsx can call it directly; pair with
// a layoutNonce bump so the mounted SectionedZone re-reads storage, same mechanism Reset already uses.
export function createPanel(page: string, title: string) {
  const zoneKey = sectionsZoneKeyFor(page)
  const config = zoneKey ? PAGE_LAYOUT[page as keyof typeof PAGE_LAYOUT]?.[zoneKey] : undefined
  if (!zoneKey || config?.kind !== 'sections') return
  const all = readLayout()
  const existing = all[zoneKey]
  const current = existing?.sections ? { order: existing.order, sections: existing.sections } : seedSectionsPref(zoneKey, config.seed)
  const id = `${zoneKey}:section:${crypto.randomUUID()}`
  const name = title.trim() || `Panel ${current.order.length + 1}`
  all[zoneKey] = { order: [...current.order, id], spans: {}, sections: { ...current.sections, [id]: { title: name, deletable: true } } }
  writeLayout(all)
  schedulePageSave(page)
}

function SectionedZone({ zoneKey, editing, nonce, dataSource, seed, className }: {
  zoneKey: string; editing: boolean; nonce: number; dataSource?: PageDataSource; seed: SectionSeed[]; className?: string
}) {
  const { order, sections, removeSection, renameSection } = useSectionList(zoneKey, seed, nonce)
  // Seeded sections' starting widgets, keyed by the same deterministic id useSectionList seeds.
  const seedWidgetsById = useMemo(() => new Map(seed.map((s, i) => [seedSectionId(zoneKey, i), s.widgets])), [zoneKey, seed])

  return <div className={`widget-sections${className ? ` ${className}` : ''}`}>
    {order.map(id => {
      const section = sections[id]
      if (!section) return null
      return <div className="widget-section" key={id}>
        <LayoutZone zoneKey={`${id}/widgets`} editing={editing} nonce={nonce} dataSource={dataSource}
          forceWidgetSeed={seedWidgetsById.get(id) ?? []} sectionTitle={section.title}
          onRenameSection={title => renameSection(id, title)}
          onDeleteSection={section.deletable ? () => removeSection(id) : undefined} />
      </div>
    })}
  </div>
}

// Page-header dropdown for edit-layout mode: create a panel, force-save, or reset the page.
export function LayoutMenu({ onCreatePanel, onSave, onReset }: {
  onCreatePanel: (title: string) => void; onSave: () => void | Promise<void>; onReset: () => void
}) {
  const [open, setOpen] = useState(false)
  const [saved, setSaved] = useState(false)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])
  const save = async () => {
    setOpen(false)
    await onSave()
    setSaved(true)
    setTimeout(() => setSaved(false), 1800)
  }
  const startCreate = () => { setOpen(false); setName(''); setNaming(true) }
  const submitCreate = () => {
    const title = name.trim()
    if (!title) return
    onCreatePanel(title)
    setNaming(false)
  }
  return <div className="menu" ref={ref}>
    <button type="button" className="tool-action" onClick={() => setOpen(o => !o)}>{saved ? '✓ Saved' : '☰ Layout'}</button>
    {open && <ul className="menu-dropdown">
      <li><button type="button" onClick={startCreate}>+ Create a panel</button></li>
      <li><button type="button" onClick={() => void save()}>Save layout</button></li>
      <li><button type="button" onClick={() => { setOpen(false); onReset() }}>↺ Reset layout</button></li>
    </ul>}
    {naming && <div className="modal-backdrop"><section className="modal narrow">
      <div className="modal-header">
        <div><p className="eyebrow">NEW PANEL</p><h2>Name this section</h2></div>
        <button className="close" onClick={() => setNaming(false)}>×</button>
      </div>
      <Field label="Section name" wide>
        <input autoFocus value={name} maxLength={48} placeholder="e.g. Retirement accounts"
          onChange={e => setName(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') submitCreate(); else if (e.key === 'Escape') setNaming(false) }} />
      </Field>
      <div className="modal-actions">
        <button type="button" className="outline" onClick={() => setNaming(false)}>Cancel</button>
        <button type="button" className="primary" disabled={!name.trim()} onClick={submitCreate}>Create panel</button>
      </div>
    </section></div>}
  </div>
}
