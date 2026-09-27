// Per-page widget-zone config for the editable-layout framework. A page's zones are either
// 'plain' (today's static sections/cards, rendered by the page itself), 'widget' (a single grid
// of user-configurable Widget panels), or 'sections' (multiple independently addable/removable
// named groups, each its own widget grid) seeded from `seed` and then freely edited.
export type WidgetSubType = 'pie-chart' | '2d-graph' | 'counter' | 'histogram'
export type WidgetQuery = { dimension?: string; measure?: string; series?: string[] }
export type Widget = { id: string; subType: WidgetSubType; title: string; query: WidgetQuery; deletable: boolean }
export type SectionSeed = { title: string; deletable: boolean; widgets: Omit<Widget, 'id'>[] }
export type ZoneConfig =
  | { kind: 'plain'; items: { key: string; span: number }[] }
  | { kind: 'widget'; seed: Omit<Widget, 'id'>[] }
  | { kind: 'sections'; seed: SectionSeed[] }

export const PAGE_LAYOUT: Record<'dashboard' | 'insights', Record<string, ZoneConfig>> = {
  // No seeded sections on either page — onboarding new users into the widget system with sample
  // content is Platform's to build, not seed data baked into these pages.
  dashboard: {
    'dashboard/widgets': { kind: 'sections', seed: [] },
  },
  insights: {
    'insights/widgets': { kind: 'sections', seed: [] },
  },
}

// Each page currently has at most one 'sections' zone — the one the page-level "Layout" menu's
// "Create a panel" / "Save layout" / "Reset layout" actions operate on.
export function sectionsZoneKeyFor(page: string): string | undefined {
  const zones = PAGE_LAYOUT[page as keyof typeof PAGE_LAYOUT]
  return zones && Object.entries(zones).find(([, cfg]) => cfg.kind === 'sections')?.[0]
}

// Content for the Edit Layout onboarding tour's own demo section, used only on a page whose
// widgets zone ships no seed of its own (today, just Insights — see PAGE_LAYOUT above). The
// same attribute keys as Insights' old seeded "Demo section", before it moved here.
export const ONBOARDING_DEMO_WIDGETS: Omit<Widget, 'id'>[] = [
  { subType: 'histogram', title: 'Value by tag', query: { dimension: 'tag', measure: 'value' }, deletable: true },
  { subType: 'counter', title: 'Portfolio P/L', query: { measure: 'totalProfitLoss' }, deletable: true },
]
