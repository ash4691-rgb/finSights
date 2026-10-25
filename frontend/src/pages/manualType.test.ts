import { describe, test, expect } from 'vitest'
import { blankManualForm } from '../util'
import { creditDateIncomplete, defaultsForCategory, deriveManualValues, manualFormFrom, manualPayload, manualTypeLabel } from './manualType'
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

  test('EPFO uses the split when given, otherwise the total, and adds interest', () => {
    expect(deriveManualValues('EPFO', form({ employeeContribution: '100', employerContribution: '50', interestAccumulated: '10' })))
      .toEqual({ invested: 150, current: 160 })
    expect(deriveManualValues('EPFO', form({ employerContribution: '70' }))).toEqual({ invested: 70, current: 70 })
    expect(deriveManualValues('EPFO', form({ investedValue: '200', interestAccumulated: '20' }))).toEqual({ invested: 200, current: 220 })
  })

  test('EPFO with no contributions, and the default type, derive nothing', () => {
    expect(deriveManualValues('EPFO', form({}))).toBeNull()
    expect(deriveManualValues('DEFAULT', form({ area: '5', purchaseUnitPrice: '5' }))).toBeNull()
  })
})

describe('manualPayload', () => {
  test('real estate sends prices only, leaving a blank current price out', () => {
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000' }))).toEqual({ purchaseUnitPrice: 4000, currentUnitPrice: undefined })
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000', currentUnitPrice: '4500' }))).toMatchObject({ currentUnitPrice: 4500 })
  })

  test('EPFO converts the rate to a fraction and sends the credit date only as a pair', () => {
    expect(manualPayload('EPFO', form({ expectedInterestRate: '8.25', interestCreditMonth: '3', interestCreditDay: '31' })))
      .toMatchObject({ expectedInterestRate: 0.0825, interestCreditMonth: 3, interestCreditDay: 31 })
    expect(manualPayload('EPFO', form({ interestCreditMonth: '3' }))).toMatchObject({ interestCreditMonth: undefined, interestCreditDay: undefined })
  })

  test('the default type sends no manual block', () => {
    expect(manualPayload('DEFAULT', form({}))).toBeNull()
  })
})

describe('form helpers', () => {
  const base = { id: 'h', manualType: 'EPFO', manual: { employeeContribution: 5, expectedInterestRate: 0.0825, interestCreditMonth: 3, interestCreditDay: 31 } } as Holding

  test('manualFormFrom restores a saved holding, rate as a percentage', () => {
    expect(manualFormFrom(base)).toMatchObject({ manualType: 'EPFO', employeeContribution: '5', expectedInterestRate: '8.25', interestCreditMonth: '3', interestCreditDay: '31', purchaseUnitPrice: '' })
  })

  test('manualFormFrom falls back to the default type with empty fields', () => {
    expect(manualFormFrom({ id: 'h' } as Holding)).toEqual(blankManualForm())
  })

  test('only a half-filled credit date counts as incomplete', () => {
    expect(creditDateIncomplete(form({ interestCreditMonth: '3' }))).toBe(true)
    expect(creditDateIncomplete(form({ interestCreditDay: '3' }))).toBe(true)
    expect(creditDateIncomplete(form({ interestCreditMonth: '3', interestCreditDay: '3' }))).toBe(false)
    expect(creditDateIncomplete(form({}))).toBe(false)
  })

  test('a real-estate asset category defaults to manual real estate, anything else to default', () => {
    const cat = (kind: 'ASSET' | 'LIABILITY', realEstate: boolean) => ({ id: 'c', kind, realEstate } as Category)
    expect(defaultsForCategory(cat('ASSET', true))).toEqual({ manualType: 'REAL_ESTATE', valuationMethod: 'MANUAL' })
    expect(defaultsForCategory(cat('LIABILITY', true))).toEqual({ manualType: 'DEFAULT' })
    expect(defaultsForCategory(cat('ASSET', false))).toEqual({ manualType: 'DEFAULT' })
    expect(defaultsForCategory(undefined)).toEqual({ manualType: 'DEFAULT' })
  })

  test('labels', () => {
    expect([manualTypeLabel('DEFAULT'), manualTypeLabel('REAL_ESTATE'), manualTypeLabel('EPFO')]).toEqual(['Default', 'Real estate', 'EPFO'])
  })
})
