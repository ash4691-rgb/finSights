// Per-page widget-zone config for the editable-layout framework. A page's zones are either
// 'plain' (today's static sections/cards, rendered by the page itself) or 'widget' (a grid of
// user-configurable Widget panels seeded from `seed` and then freely added/edited/removed).
export type WidgetSubType = 'pie-chart' | '2d-graph' | 'counter' | 'histogram'
export type WidgetQuery = { dimension?: string; measure?: string; series?: string }
export type Widget = { id: string; subType: WidgetSubType; title: string; query: WidgetQuery; deletable: boolean }
export type ZoneConfig =
  | { kind: 'plain'; items: { key: string; span: number }[] }
  | { kind: 'widget'; seed: Omit<Widget, 'id'>[] }

export const PAGE_LAYOUT: Record<'dashboard' | 'insights' | 'brokers', Record<string, ZoneConfig>> = {
  dashboard: {
    'dashboard/widgets': { kind: 'widget', seed: [
      { subType: 'counter', title: 'Net worth', query: { measure: 'netWorth' }, deletable: false },
      { subType: 'pie-chart', title: 'Allocation by category', query: { dimension: 'category', measure: 'value' }, deletable: false },
    ] },
  },
  insights: {
    'insights/widgets': { kind: 'widget', seed: [
      { subType: '2d-graph', title: 'Net worth over time', query: { series: 'netWorth' }, deletable: false },
      { subType: 'histogram', title: 'Value by category', query: { dimension: 'category', measure: 'value' }, deletable: false },
    ] },
  },
  brokers: {
    'brokers/widgets': { kind: 'widget', seed: [
      { subType: 'histogram', title: 'Value by broker', query: { dimension: 'broker', measure: 'currentValue' }, deletable: false },
      { subType: 'counter', title: 'Total portfolio value', query: { measure: 'currentValue' }, deletable: false },
    ] },
    // existing zones (brokers/page, brokers/grid) stay implicit / plain as today
  },
}
