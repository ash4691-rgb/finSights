import { describe, test, expect } from 'vitest'
import { blankManualForm } from '../util'
import { defaultsForCategory, deriveManualValues, manualFormFrom, manualPayload, manualTypeLabel } from './manualType'
import type { Category, Holding } from '../types'

const form = (overrides: Partial<ReturnType<typeof blankManualForm>> & { area?: string; investedValue?: string }) =>
  ({ ...blankManualForm(), area: '', investedValue: '', ...overrides })

describe('deriveManualValues', () => {
  test('real estate multiplies area by each unit price and defaults current to purchase', () => {
    expect(deriveManualValues('REAL_ESTATE', form({ area: '12.5', purchaseUnitPrice: '1000.1', currentUnitPrice: '1200' })))
      .toEqual({ invested: 12501.25, current: 15000 })
    expect(deriveManualValues('REAL_ESTATE', form({ area: '10', purchaseUnitPrice: '500' }))).toEqual({ invested: 5000, current: 5000 })
  })

  test('real estate is incomplete without area, purchase price or a positive current price', () => {
    expect(deriveManualValues('REAL_ESTATE', form({ purchaseUnitPrice: '5' }))).toBeNull()
    expect(deriveManualValues('REAL_ESTATE', form({ area: '5' }))).toBeNull()
    expect(deriveManualValues('REAL_ESTATE', form({ area: '5', purchaseUnitPrice: '5', currentUnitPrice: '0' }))).toBeNull()
  })

  test('provident fund invested and current are both the total balance', () => {
    expect(deriveManualValues('PROVIDENT_FUND', form({ investedValue: '200' }))).toEqual({ invested: 200, current: 200 })
  })

  test('provident fund with no balance, and the default type, derive nothing', () => {
    expect(deriveManualValues('PROVIDENT_FUND', form({}))).toBeNull()
    expect(deriveManualValues('DEFAULT', form({ area: '5', purchaseUnitPrice: '5' }))).toBeNull()
  })
})

describe('manualPayload', () => {
  test('real estate sends prices only, leaving a blank current price out', () => {
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000' }))).toEqual({ purchaseUnitPrice: 4000, currentUnitPrice: undefined })
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000', currentUnitPrice: '4500' }))).toMatchObject({ currentUnitPrice: 4500 })
  })

  test('provident fund sends the monthly contribution and the rate as a fraction, leaving unset fields out', () => {
    expect(manualPayload('PROVIDENT_FUND', form({ expectedInterestRate: '8.25' }))).toMatchObject({ expectedInterestRate: 0.0825, monthlyContribution: undefined })
    expect(manualPayload('PROVIDENT_FUND', form({}))).toMatchObject({ expectedInterestRate: undefined })
    expect(manualPayload('PROVIDENT_FUND', form({ monthlyContribution: '3600' }))).toMatchObject({ monthlyContribution: 3600 })
  })

  test('the default type sends no manual block', () => {
    expect(manualPayload('DEFAULT', form({}))).toBeNull()
  })
})

describe('form helpers', () => {
  const base = { id: 'h', manualType: 'PROVIDENT_FUND', manual: { expectedInterestRate: 0.0825, monthlyContribution: 1800 } } as Holding

  test('manualFormFrom restores a saved holding, rate as a percentage', () => {
    expect(manualFormFrom(base)).toMatchObject({ manualType: 'PROVIDENT_FUND', monthlyContribution: '1800', expectedInterestRate: '8.25', purchaseUnitPrice: '' })
  })

  test('manualFormFrom falls back to the default type with empty fields', () => {
    expect(manualFormFrom({ id: 'h' } as Holding)).toEqual(blankManualForm())
  })

  test('a real-estate asset category defaults to manual real estate, anything else to default', () => {
    const cat = (kind: 'ASSET' | 'LIABILITY', realEstate: boolean) => ({ id: 'c', kind, realEstate } as Category)
    expect(defaultsForCategory(cat('ASSET', true))).toEqual({ manualType: 'REAL_ESTATE', valuationMethod: 'MANUAL' })
    expect(defaultsForCategory(cat('LIABILITY', true))).toEqual({ manualType: 'DEFAULT' })
    expect(defaultsForCategory(cat('ASSET', false))).toEqual({ manualType: 'DEFAULT' })
    expect(defaultsForCategory(undefined)).toEqual({ manualType: 'DEFAULT' })
  })

  test('labels', () => {
    expect([manualTypeLabel('DEFAULT'), manualTypeLabel('REAL_ESTATE'), manualTypeLabel('PROVIDENT_FUND')]).toEqual(['Default', 'Real estate', 'Provident fund'])
  })
})
