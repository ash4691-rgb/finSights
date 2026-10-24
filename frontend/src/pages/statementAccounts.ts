import { reconcileStatement } from './statementImportLogic'
import type { StatementExtractionResult, StatementType } from '../types'

// A CAS lists every demat account and fund folio you hold, so one statement can belong to several
// brokers. The model reports the institution each line is held through ("account"); the user maps
// each one to a broker of theirs, and the rows are then reconciled one broker at a time — never
// against holdings of a broker the statement doesn't speak for.

export type BrokerResult = { broker: string; result: StatementExtractionResult }

const norm = (value: string) => value.trim().toLowerCase()

type Account = string | null | undefined
const nameOf = (account: Account) => account?.trim() ?? ''

/** The distinct institutions the statement's lines are held through, in first-seen order. */
export const accountsOf = (statement: StatementExtractionResult): string[] => {
  const seen = new Map<string, string>()
  for (const row of [...statement.holdings, ...statement.transactions]) {
    const name = nameOf(row.account)
    if (name && !seen.has(norm(name))) seen.set(norm(name), name)
  }
  return [...seen.values()]
}

/** True when some line names no institution — those need the single broker field as well. */
export const hasUnaccountedRows = (statement: StatementExtractionResult): boolean =>
  [...statement.holdings, ...statement.transactions].some(row => !nameOf(row.account))

/** One of the user's brokers an institution name clearly refers to ("Zerodha Broking" → "Zerodha"), or ''. */
export const guessBroker = (account: string, brokers: string[]): string => {
  const target = norm(account)
  const exact = brokers.find(b => norm(b) === target)
  if (exact) return exact
  const related = brokers.filter(b => target.includes(norm(b)) || norm(b).includes(target))
  return related.length === 1 ? related[0] : ''
}

/** Every account has a broker, and so does any line that names no account. */
export const accountsMapped = (accounts: string[], brokerByAccount: Record<string, string>, fallbackBroker: string, needsFallback: boolean): boolean =>
  accounts.every(a => (brokerByAccount[norm(a)] ?? '').trim() !== '') && (!needsFallback || fallbackBroker.trim() !== '')

/** The statement cut down to the lines that belong to each broker, in first-seen broker order. */
export const splitByBroker = (statement: StatementExtractionResult, brokerByAccount: Record<string, string>, fallbackBroker: string): { broker: string; statement: StatementExtractionResult }[] => {
  const brokerOf = (account: Account) => nameOf(account) ? (brokerByAccount[norm(nameOf(account))] ?? '').trim() : fallbackBroker.trim()
  const groups = new Map<string, StatementExtractionResult>()
  const group = (broker: string) => {
    if (!groups.has(norm(broker))) groups.set(norm(broker), { ...statement, broker, holdings: [], transactions: [], toRemove: [] })
    return groups.get(norm(broker))!
  }
  for (const row of statement.holdings) { const broker = brokerOf(row.account); if (broker) group(broker).holdings.push(row) }
  for (const row of statement.transactions) { const broker = brokerOf(row.account); if (broker) group(broker).transactions.push(row) }
  return [...groups.values()].map(g => ({ broker: g.broker ?? '', statement: g }))
}

/** Reconciles each broker's share of a multi-account statement against that broker's holdings only. */
export const reconcileByBroker = async (statement: StatementExtractionResult, statementType: StatementType,
    brokerByAccount: Record<string, string>, fallbackBroker: string): Promise<BrokerResult[]> =>
  reconcileParts(statement, statementType, splitByBroker(statement, brokerByAccount, fallbackBroker), fallbackBroker.trim())

const reconcileParts = async (statement: StatementExtractionResult, statementType: StatementType,
    parts: { broker: string; statement: StatementExtractionResult }[], fallbackBroker: string): Promise<BrokerResult[]> => {
  // A statement with no rows still reconciles (it carries only notes), against the one broker given.
  if (parts.length === 0) return [{ broker: fallbackBroker, result: await reconcileStatement(statement, statementType, fallbackBroker) }]
  // The statement's own notes belong to the statement, so only the first broker's tab carries them.
  return Promise.all(parts.map(async ({ broker, statement: part }, index) =>
    ({ broker, result: await reconcileStatement(index === 0 ? part : { ...part, warnings: [] }, statementType, broker) })))
}

export const accountKey = norm
