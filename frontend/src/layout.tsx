import { useEffect, useRef, useState } from 'react'
import type * as React from 'react'
import type { ReactNode } from 'react'

// ---------------------------------------------------------------------------
// Editable layout — per-page "Edit layout" mode. Each page is a set of zones
// (a page zone = its stack of sections; a grid zone = a row of cards inside a
// section). Within a zone the user can drag items to reorder them, and — via
// edge / corner handles — resize each item horizontally (width snaps to a
// 12-column grid), vertically (free height, content scrolls), or diagonally.
// Preferences live in localStorage keyed "page/zone" — never syncs to the
// backend (same as the theme toggle). Nothing crosses from one zone to another.
// ---------------------------------------------------------------------------
export const LAYOUT_KEY = 'finsights-layout-v1'
export const MIN_SPAN = 1, MAX_SPAN = 12, MIN_ITEM_HEIGHT = 96
export const clampSpan = (n: number) => Math.max(MIN_SPAN, Math.min(MAX_SPAN, Math.round(n)))
export type ResizeMode = 'e' | 's' | 'se'
export type ZonePref = { order: string[]; spans: Record<string, number>; heights?: Record<string, number> }
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

export function useZoneLayout(zoneKey: string, defaults: ZoneItem[], nonce: number) {
  const defaultsRef = useRef(defaults)
  defaultsRef.current = defaults
  const [items, setItems] = useState<ZoneItem[]>(() => mergeZone(defaults, readLayout()[zoneKey]))
  // Re-read from storage when the zone changes or a Reset bumps the nonce.
  useEffect(() => { setItems(mergeZone(defaultsRef.current, readLayout()[zoneKey])) }, [zoneKey, nonce])
  // Re-merge if the default set itself changes (e.g. brokers loaded in) — keep
  // the user's current order / spans / heights.
  const sig = defaults.map(d => `${d.key}:${d.span}`).join('|')
  useEffect(() => { setItems(current => mergeZone(defaultsRef.current, {
    order: current.map(i => i.key),
    spans: Object.fromEntries(current.map(i => [i.key, i.span])),
    heights: Object.fromEntries(current.flatMap(i => i.height != null ? [[i.key, i.height]] : [])),
  })) }, [sig])

  const persist = (nextItems: ZoneItem[]) => {
    const all = readLayout()
    all[zoneKey] = {
      order: nextItems.map(i => i.key),
      spans: Object.fromEntries(nextItems.map(i => [i.key, i.span])),
      heights: Object.fromEntries(nextItems.flatMap(i => i.height != null ? [[i.key, i.height]] : [])),
    }
    writeLayout(all)
  }
  const move = (fromKey: string, toKey: string) => setItems(current => {
    if (fromKey === toKey) return current
    const from = current.findIndex(i => i.key === fromKey), to = current.findIndex(i => i.key === toKey)
    if (from === -1 || to === -1) return current
    const next = [...current]
    next.splice(to, 0, next.splice(from, 1)[0])
    persist(next); return next
  })
  // patch.height === null clears the manual height (back to auto).
  const resize = (key: string, patch: { span?: number; height?: number | null }) => setItems(current => {
    const next = current.map(i => i.key !== key ? i : {
      ...i,
      span: patch.span != null ? clampSpan(patch.span) : i.span,
      height: patch.height === null ? undefined : (patch.height != null ? Math.round(patch.height) : i.height),
    })
    persist(next); return next
  })
  return { items, move, resize }
}

export type DragState = {
  key: string; mode: ResizeMode
  startX: number; startY: number; startSpan: number; startHeight: number
  span: number; height: number
}

export function LayoutZone({ zoneKey, editing, nonce, defaults, render, className }: {
  zoneKey: string; editing: boolean; nonce: number; defaults: ZoneItem[]
  render: Record<string, ReactNode>; className?: string
}) {
  const { items, move, resize } = useZoneLayout(zoneKey, defaults, nonce)
  const zoneRef = useRef<HTMLDivElement>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)
  const [rz, setRz] = useState<DragState | null>(null)

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

  return <div ref={zoneRef} className={`layout-zone${editing ? ' editing' : ''}${rz ? ' resizing' : ''}${className ? ` ${className}` : ''}`}>
    {items.filter(i => render[i.key] != null).map(item => {
      const live = rz && rz.key === item.key ? rz : null
      const span = live ? live.span : item.span
      const height = live ? live.height : item.height
      const style = { '--span': span, ...(height != null ? { height: `${height}px`, overflow: 'auto' } : null) } as React.CSSProperties
      return <div key={item.key}
        className={`layout-item${overKey === item.key ? ' drag-over' : ''}${live ? ' resizing' : ''}`}
        style={style}
        onDragOver={e => { if (editing && dragKey) { e.preventDefault(); setOverKey(item.key) } }}
        onDragLeave={() => setOverKey(cur => cur === item.key ? null : cur)}
        onDrop={e => { if (editing && dragKey) { e.preventDefault(); move(dragKey, item.key); setDragKey(null); setOverKey(null) } }}>
        {editing && <div className="layout-item-bar">
          <span className="drag-handle" draggable onDragStart={() => setDragKey(item.key)}
            onDragEnd={() => { setDragKey(null); setOverKey(null) }} title="Drag to reorder">⠿</span>
          <span className="layout-item-size">{span}/12{height != null ? ` · ${Math.round(height)}px` : ''}</span>
        </div>}
        {render[item.key]}
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
}
