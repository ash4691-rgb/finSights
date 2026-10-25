import type { ReactNode } from 'react'
import { Field } from '../ui'
import { areaUnits, areaUnitLabel, money, percent } from '../util'
import type { ManualForm } from '../util'
import { hasContributionSplit } from './manualType'
import type { AreaUnit, Holding, ManualType } from '../types'

type FormFields = ManualForm & { area: string; areaUnit: AreaUnit; investedValue: string; currency: string }
type FieldsProps = {
  type: ManualType; form: FormFields; set: (key: string, value: string) => void
  derived: { invested: number; current: number } | null; disabled?: boolean
}

const number = (value: string, onChange: (v: string) => void, props: { required?: boolean; placeholder?: string; disabled?: boolean; min?: string; max?: string; step?: string } = {}) =>
  <input type="number" min={props.min ?? '0'} max={props.max} step={props.step ?? 'any'} required={props.required} disabled={props.disabled} placeholder={props.placeholder} value={value} onChange={e => onChange(e.target.value)} />

function CalculatedValues({ form, derived, disabled }: { form: FormFields; derived: FieldsProps['derived']; disabled?: boolean }) {
  const show = (value?: number) => disabled || value == null ? '' : String(value)
  return <div className="field-pair">
    <Field label="Invested value (calculated)"><input disabled value={show(derived?.invested)} placeholder="Fill in the fields above" aria-label="Invested value (calculated)" /></Field>
    <Field label="Current value (calculated)"><input disabled value={show(derived?.current)} placeholder={form.currency} aria-label="Current value (calculated)" /></Field>
  </div>
}

function RealEstateFields({ form, set, derived, disabled }: Omit<FieldsProps, 'type'>) {
  return <>
    <div className="field-pair">
      <Field label="Area" required>{number(form.area, v => set('area', v), { required: true, placeholder: 'e.g. 1200' })}</Field>
      <Field label="Unit" required><select required value={form.areaUnit} onChange={e => set('areaUnit', e.target.value)}>{areaUnits.map(u => <option key={u} value={u}>{areaUnitLabel(u)}</option>)}</select></Field>
    </div>
    <div className="field-pair">
      <Field label="Purchase unit price" required>{number(form.purchaseUnitPrice, v => set('purchaseUnitPrice', v), { required: true, placeholder: 'Price per unit of area', disabled })}</Field>
      <Field label="Current unit price">{number(form.currentUnitPrice, v => set('currentUnitPrice', v), { placeholder: 'Same as purchase price', disabled })}</Field>
    </div>
    <CalculatedValues form={form} derived={derived} disabled={disabled} />
  </>
}

function EpfoFields({ form, set, derived, disabled }: Omit<FieldsProps, 'type'>) {
  const split = hasContributionSplit(form)
  return <>
    <Field label="Total balance" required={!split} wide>
      {number(split ? String(derived?.current ?? '') : form.investedValue, v => set('investedValue', v), { required: !split, disabled: split || disabled, placeholder: split ? 'Employee + employer' : 'Balance shown in your EPFO passbook' })}
      <p className="hint">{split ? 'Employee + employer contributions below.' : 'EPFO reports employee + employer share including interest.'}</p>
    </Field>
    <div className="field-pair">
      <Field label="Employee contribution">{number(form.employeeContribution, v => set('employeeContribution', v), { disabled })}</Field>
      <Field label="Employer contribution">{number(form.employerContribution, v => set('employerContribution', v), { disabled })}</Field>
    </div>
    <div className="field-pair">
      <Field label="Monthly employee contribution">{number(form.monthlyEmployeeContribution, v => set('monthlyEmployeeContribution', v), { disabled, placeholder: 'Per month' })}</Field>
      <Field label="Monthly employer contribution">{number(form.monthlyEmployerContribution, v => set('monthlyEmployerContribution', v), { disabled, placeholder: 'Per month' })}</Field>
    </div>
    <p className="hint">With a monthly amount set, a task on the 1st of each month asks you to log last month's contribution.</p>
    <div className="field-pair">
      <Field label="Interest accumulated">{number(form.interestAccumulated, v => set('interestAccumulated', v), { placeholder: '0', disabled })}</Field>
      <Field label="Expected interest rate (% p.a.)">{number(form.expectedInterestRate, v => set('expectedInterestRate', v), { max: '100', placeholder: 'e.g. 8.25' })}</Field>
    </div>
    <CalculatedValues form={form} derived={derived} disabled={disabled} />
  </>
}

// The inputs behind a typed manual holding, shown under the Manual type dropdown.
export function ManualTypeFields({ type, ...rest }: FieldsProps) {
  if (type === 'REAL_ESTATE') return <RealEstateFields {...rest} />
  if (type === 'EPFO') return <EpfoFields {...rest} />
  return null
}

const fact = (title: string, value: ReactNode) => <span key={title}>{title}<b>{value}</b></span>

function realEstateFacts(holding: Holding) {
  const m = holding.manual ?? {}
  return [
    holding.area != null && holding.areaUnit ? fact('Area', `${holding.area} ${areaUnitLabel(holding.areaUnit)}`) : null,
    m.purchaseUnitPrice != null ? fact('Purchase unit price', money(m.purchaseUnitPrice, holding.currency)) : null,
    m.currentUnitPrice != null ? fact('Current unit price', money(m.currentUnitPrice, holding.currency)) : null,
  ]
}

function epfoFacts(holding: Holding) {
  const m = holding.manual ?? {}
  return [
    m.employeeContribution != null ? fact('Employee contribution', money(m.employeeContribution, holding.currency)) : null,
    m.employerContribution != null ? fact('Employer contribution', money(m.employerContribution, holding.currency)) : null,
    m.monthlyEmployeeContribution != null ? fact('Monthly employee contribution', money(m.monthlyEmployeeContribution, holding.currency)) : null,
    m.monthlyEmployerContribution != null ? fact('Monthly employer contribution', money(m.monthlyEmployerContribution, holding.currency)) : null,
    m.interestAccumulated != null ? fact('Interest accumulated', money(m.interestAccumulated, holding.currency)) : null,
    m.expectedInterestRate != null ? fact('Expected rate', `${percent(m.expectedInterestRate * 100)} p.a.`) : null,
  ]
}

// Extra facts for the holding drawer; null for a plain holding.
export function ManualFacts({ holding }: { holding: Holding }) {
  if (holding.manualType === 'REAL_ESTATE') return <>{realEstateFacts(holding)}</>
  if (holding.manualType === 'EPFO') return <>{epfoFacts(holding)}</>
  return null
}

