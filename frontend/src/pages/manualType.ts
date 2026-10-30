import { numeric } from '../util'
import type { ManualForm } from '../util'
import type { Category, Holding, ManualDetails, ManualType, ValuationMethod } from '../types'

export const manualTypes: ManualType[] = ['DEFAULT', 'REAL_ESTATE', 'PROVIDENT_FUND']
const manualTypeLabels: Record<ManualType, string> = { DEFAULT: 'Default', REAL_ESTATE: 'Real estate', PROVIDENT_FUND: 'Provident fund' }
export const manualTypeLabel = (t: ManualType) => manualTypeLabels[t]

type DerivationInput = ManualForm & { area: string; investedValue: string }

const text = (n?: number) => n != null ? String(n) : ''
// Edit form values for a holding that already carries a manual type.
export const manualFormFrom = (holding: Holding): ManualForm => {
  const m: ManualDetails = holding.manual ?? {}
  return {
    manualType: holding.manualType ?? 'DEFAULT',
    purchaseUnitPrice: text(m.purchaseUnitPrice), currentUnitPrice: text(m.currentUnitPrice),
    monthlyEmployeeContribution: text(m.monthlyEmployeeContribution), monthlyEmployerContribution: text(m.monthlyEmployerContribution),
    expectedInterestRate: m.expectedInterestRate != null ? String(+(m.expectedInterestRate * 100).toFixed(4)) : '',
  }
}

// A new holding in a real-estate category starts as a manual real-estate one; anything else is a plain holding.
export const defaultsForCategory = (category?: Category): { manualType: ManualType; valuationMethod?: ValuationMethod } =>
  category?.realEstate && category.kind === 'ASSET' ? { manualType: 'REAL_ESTATE', valuationMethod: 'MANUAL' } : { manualType: 'DEFAULT' }

const round2 = (n: number) => Math.round(n * 100) / 100

// Mirrors the server's calculation so the form can show the values it will save; null until the inputs are complete.
export function deriveManualValues(type: ManualType, f: DerivationInput): { invested: number; current: number } | null {
  if (type === 'REAL_ESTATE') return deriveRealEstate(f)
  if (type === 'PROVIDENT_FUND') return deriveProvidentFund(f)
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

// A provident fund is tracked by its total balance, so invested and current value are both that balance.
function deriveProvidentFund(f: DerivationInput) {
  const balance = numeric(f.investedValue)
  if (balance <= 0) return null
  return { invested: round2(balance), current: round2(balance) }
}

// The request's `manual` block; only the fields that belong to the chosen type are sent.
export function manualPayload(type: ManualType, f: ManualForm): ManualDetails | null {
  if (type === 'REAL_ESTATE') {
    return { purchaseUnitPrice: numeric(f.purchaseUnitPrice), currentUnitPrice: f.currentUnitPrice === '' ? undefined : numeric(f.currentUnitPrice) }
  }
  if (type === 'PROVIDENT_FUND') return providentFundPayload(f)
  return null
}

const optional = (value: string) => value === '' ? undefined : numeric(value)

function providentFundPayload(f: ManualForm): ManualDetails {
  return {
    expectedInterestRate: f.expectedInterestRate === '' ? undefined : numeric(f.expectedInterestRate) / 100,
    monthlyEmployeeContribution: optional(f.monthlyEmployeeContribution), monthlyEmployerContribution: optional(f.monthlyEmployerContribution),
  }
}
