import { describe, test, expect, vi } from 'vitest'

vi.mock('./api', () => ({ api: vi.fn(), API_URL: 'http://api.test' }))
import { resolveGuideStep, type GuideEntry, type GuideStep } from './goku-guide'

// Walks every branch of the guided menu, picking each option in turn (and a stand-in value for a
// pick/period/text step), and returns every finished path.
function allPaths(): { trail: GuideEntry[]; end: GuideStep }[] {
  const done: { trail: GuideEntry[]; end: GuideStep }[] = []
  const walk = (trail: GuideEntry[]) => {
    const step = resolveGuideStep(trail)
    if (step.kind === 'choice') step.options.forEach(o => walk([...trail, o]))
    else if (step.kind === 'pick') walk([...trail, { key: `id-${step.source}`, label: `Some ${step.source}` }])
    else if (step.kind === 'period') walk([...trail, { key: '2026-01-01|2026-03-31', label: 'Q1' }])
    else if (step.kind === 'text') walk([...trail, { key: 'text', label: 'details' }])
    else done.push({ trail, end: step })
  }
  walk([])
  return done
}

describe('Goku guided menu', () => {
  test('every path finishes in at most five steps', () => {
    const paths = allPaths()
    expect(paths.length).toBeGreaterThan(20)
    for (const { trail } of paths) expect(trail.length).toBeLessThanOrEqual(5)
  })

  test('every path ends by sending, importing a file, or handing over to free text', () => {
    for (const { end } of allPaths()) expect(['send', 'file', 'freeform']).toContain(end.kind)
  })

  test('every sent message carries a structured intent for Goku', () => {
    for (const { end } of allPaths()) {
      if (end.kind === 'send') expect(end.payload).toMatch(/^\[Intent: [^\]]+\] /)
    }
  })

  test('a filtered transactions summary passes the filter id and date range', () => {
    const step = resolveGuideStep([
      { key: 'transactions', label: 'Transactions summary' }, { key: 'category', label: 'Category' },
      { key: 'c-1', label: 'US Stocks' }, { key: '2026-04-01|2026-06-30', label: 'Q1' },
    ])
    expect(step).toEqual({
      kind: 'send',
      content: 'Summarize my transactions for category "US Stocks" from 2026-04-01 to 2026-06-30.',
      payload: '[Intent: Transactions summary · category_id=c-1 from=2026-04-01 to=2026-06-30] Summarize my transactions for category "US Stocks" from 2026-04-01 to 2026-06-30.',
    })
  })

  test('a delete names the exact record id, and goes to Goku as a request — not an action', () => {
    const step = resolveGuideStep([
      { key: 'change', label: 'Add, edit or delete' }, { key: 'delete', label: 'Delete something' },
      { key: 'holding', label: 'Holding' }, { key: 'h-9', label: 'Reliance' },
    ])
    expect(step).toMatchObject({ kind: 'send', payload: '[Intent: Change data · delete holding · id=h-9] Delete the holding "Reliance".' })
  })

  test('"Something else" hands over to the text box without sending anything', () => {
    expect(resolveGuideStep([{ key: 'other', label: 'Something else' }])).toEqual({ kind: 'freeform' })
  })
})
