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

  test('EPFO current is the employee + employer balance (or one total) and invested is current minus interest', () => {
    expect(deriveManualValues('EPFO', form({ employeeContribution: '100', employerContribution: '50', interestAccumulated: '10' })))
      .toEqual({ invested: 140, current: 150 })
    expect(deriveManualValues('EPFO', form({ employerContribution: '70' }))).toEqual({ invested: 70, current: 70 })
    expect(deriveManualValues('EPFO', form({ investedValue: '200', interestAccumulated: '20' }))).toEqual({ invested: 180, current: 200 })
  })

  test('EPFO with no balance, or interest above the balance, and the default type, derive nothing', () => {
    expect(deriveManualValues('EPFO', form({}))).toBeNull()
    expect(deriveManualValues('EPFO', form({ investedValue: '5', interestAccumulated: '6' }))).toBeNull()
    expect(deriveManualValues('DEFAULT', form({ area: '5', purchaseUnitPrice: '5' }))).toBeNull()
  })
})

describe('manualPayload', () => {
  test('real estate sends prices only, leaving a blank current price out', () => {
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000' }))).toEqual({ purchaseUnitPrice: 4000, currentUnitPrice: undefined })
    expect(manualPayload('REAL_ESTATE', form({ purchaseUnitPrice: '4000', currentUnitPrice: '4500' }))).toMatchObject({ currentUnitPrice: 4500 })
  })

  test('EPFO converts the rate to a fraction and leaves unset fields out', () => {
    expect(manualPayload('EPFO', form({ expectedInterestRate: '8.25', interestAccumulated: '10' })))
      .toMatchObject({ expectedInterestRate: 0.0825, interestAccumulated: 10, employeeContribution: undefined })
    expect(manualPayload('EPFO', form({}))).toMatchObject({ expectedInterestRate: undefined, monthlyEmployeeContribution: undefined })
    expect(manualPayload('EPFO', form({ monthlyEmployeeContribution: '1800', monthlyEmployerContribution: '1800' })))
      .toMatchObject({ monthlyEmployeeContribution: 1800, monthlyEmployerContribution: 1800 })
  })

  test('the default type sends no manual block', () => {
    expect(manualPayload('DEFAULT', form({}))).toBeNull()
  })
})

describe('form helpers', () => {
  const base = { id: 'h', manualType: 'EPFO', manual: { employeeContribution: 5, expectedInterestRate: 0.0825, monthlyEmployerContribution: 1800 } } as Holding

  test('manualFormFrom restores a saved holding, rate as a percentage', () => {
    expect(manualFormFrom(base)).toMatchObject({ manualType: 'EPFO', employeeContribution: '5', monthlyEmployerContribution: '1800', monthlyEmployeeContribution: '', expectedInterestRate: '8.25', purchaseUnitPrice: '' })
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
    expect([manualTypeLabel('DEFAULT'), manualTypeLabel('REAL_ESTATE'), manualTypeLabel('EPFO')]).toEqual(['Default', 'Real estate', 'EPFO'])
  })
})
