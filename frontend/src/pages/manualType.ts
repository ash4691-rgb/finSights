import { numeric } from '../util'
import type { ManualForm } from '../util'
import type { Category, Holding, ManualDetails, ManualType, ValuationMethod } from '../types'

export const manualTypes: ManualType[] = ['DEFAULT', 'REAL_ESTATE', 'EPFO']
export const manualTypeLabel = (t: ManualType) => t === 'REAL_ESTATE' ? 'Real estate' : t === 'EPFO' ? 'EPFO' : 'Default'
export const monthNames = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

type DerivationInput = ManualForm & { area: string; investedValue: string }

const text = (n?: number) => n != null ? String(n) : ''
const pad2 = (n: number) => String(n).padStart(2, '0')

// The nudge is stored as a month + day that repeats yearly; the form edits it as one date, shown in the current year.
const creditDateFrom = (month?: number, day?: number) =>
  month != null && day != null ? `${new Date().getFullYear()}-${pad2(month)}-${pad2(day)}` : ''

// Edit form values for a holding that already carries a manual type.
export const manualFormFrom = (holding: Holding): ManualForm => {
  const m: ManualDetails = holding.manual ?? {}
  return {
    manualType: holding.manualType ?? 'DEFAULT',
    purchaseUnitPrice: text(m.purchaseUnitPrice), currentUnitPrice: text(m.currentUnitPrice),
    employeeContribution: text(m.employeeContribution), employerContribution: text(m.employerContribution),
    interestAccumulated: text(m.interestAccumulated),
    expectedInterestRate: m.expectedInterestRate != null ? String(+(m.expectedInterestRate * 100).toFixed(4)) : '',
    interestCreditDate: creditDateFrom(m.interestCreditMonth, m.interestCreditDay),
  }
}

// A new holding in a real-estate category starts as a manual real-estate one; anything else is a plain holding.
export const defaultsForCategory = (category?: Category): { manualType: ManualType; valuationMethod?: ValuationMethod } =>
  category?.realEstate && category.kind === 'ASSET' ? { manualType: 'REAL_ESTATE', valuationMethod: 'MANUAL' } : { manualType: 'DEFAULT' }

const round2 = (n: number) => Math.round(n * 100) / 100

// Mirrors the server's calculation so the form can show the values it will save; null until the inputs are complete.
export function deriveManualValues(type: ManualType, f: DerivationInput): { invested: number; current: number } | null {
  if (type === 'REAL_ESTATE') return deriveRealEstate(f)
  if (type === 'EPFO') return deriveEpfo(f)
  return null
}

function deriveRealEstate(f: DerivationInput) {
  const area = numeric(f.area)
  const purchase = numeric(f.purchaseUnitPrice)
  if (area <= 0 || purchase <= 0) return null
  const current = f.currentUnitPrice === '' ? purchase : numeric(f.currentUnitPrice)
  if (current <= 0) return null
  return { invested: round2(area * purchase), current: round2(area * current) }
}

export const hasContributionSplit = (f: ManualForm) => f.employeeContribution !== '' || f.employerContribution !== ''

// The balance EPFO reports already includes interest: current = employee + employer (or one total), invested = current - interest.
function deriveEpfo(f: DerivationInput) {
  const current = hasContributionSplit(f) ? numeric(f.employeeContribution) + numeric(f.employerContribution) : numeric(f.investedValue)
  const interest = numeric(f.interestAccumulated)
  if (current <= 0 || interest > current) return null
  return { invested: round2(current - interest), current: round2(current) }
}

// The request's `manual` block; only the fields that belong to the chosen type are sent.
export function manualPayload(type: ManualType, f: ManualForm): ManualDetails | null {
  if (type === 'REAL_ESTATE') {
    return { purchaseUnitPrice: numeric(f.purchaseUnitPrice), currentUnitPrice: f.currentUnitPrice === '' ? undefined : numeric(f.currentUnitPrice) }
  }
  if (type === 'EPFO') return epfoPayload(f)
  return null
}

const optional = (value: string) => value === '' ? undefined : numeric(value)

function epfoPayload(f: ManualForm): ManualDetails {
  const [, month, day] = f.interestCreditDate.split('-')
  const hasDate = f.interestCreditDate !== ''
  return {
    employeeContribution: optional(f.employeeContribution), employerContribution: optional(f.employerContribution),
    interestAccumulated: optional(f.interestAccumulated),
    expectedInterestRate: f.expectedInterestRate === '' ? undefined : numeric(f.expectedInterestRate) / 100,
    interestCreditMonth: hasDate ? Number(month) : undefined,
    interestCreditDay: hasDate ? Number(day) : undefined,
  }
}
