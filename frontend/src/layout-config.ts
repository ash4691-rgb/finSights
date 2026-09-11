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

export const PAGE_LAYOUT: Record<'dashboard' | 'insights' | 'brokers', Record<string, ZoneConfig>> = {
  dashboard: {
    'dashboard/widgets': { kind: 'sections', seed: [
      { title: 'Overview', deletable: false, widgets: [
        { subType: 'counter', title: 'Net worth', query: { measure: 'netWorth' }, deletable: false },
        { subType: 'pie-chart', title: 'Allocation by category', query: { dimension: 'category', measure: 'value' }, deletable: false },
      ] },
      { title: 'Demo section', deletable: true, widgets: [
        { subType: 'counter', title: 'Total liabilities', query: { measure: 'totalLiabilities' }, deletable: true },
        { subType: 'pie-chart', title: 'Allocation by broker', query: { dimension: 'broker', measure: 'value' }, deletable: true },
      ] },
    ] },
  },
  insights: {
    'insights/widgets': { kind: 'sections', seed: [
      { title: 'Overview', deletable: false, widgets: [
        { subType: '2d-graph', title: 'Net worth over time', query: { series: ['netWorth'] }, deletable: false },
        { subType: 'histogram', title: 'Value by category', query: { dimension: 'category', measure: 'value' }, deletable: false },
      ] },
      { title: 'Demo section', deletable: true, widgets: [
        { subType: 'histogram', title: 'Value by tag', query: { dimension: 'tag', measure: 'value' }, deletable: true },
        { subType: 'counter', title: 'Portfolio P/L', query: { measure: 'totalProfitLoss' }, deletable: true },
      ] },
    ] },
  },
  brokers: {
    'brokers/widgets': { kind: 'sections', seed: [
      { title: 'Overview', deletable: false, widgets: [
        { subType: 'histogram', title: 'Value by broker', query: { dimension: 'broker', measure: 'currentValue' }, deletable: false },
        { subType: 'counter', title: 'Total portfolio value', query: { measure: 'currentValue' }, deletable: false },
      ] },
      { title: 'Demo section', deletable: true, widgets: [
        { subType: 'pie-chart', title: 'Invested by broker', query: { dimension: 'broker', measure: 'investedValue' }, deletable: true },
        { subType: 'counter', title: 'Total holdings', query: { measure: 'holdingCount' }, deletable: true },
      ] },
    ] },
    // existing zones (brokers/page, brokers/grid) stay implicit / plain as today
  },
}
