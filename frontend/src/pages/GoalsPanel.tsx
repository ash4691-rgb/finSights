import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { money, since } from '../util'
import { Field } from '../ui'
import type { Category, Goal } from '../types'

type GoalForm = { name: string; targetAmount: string; targetDate: string; monthlyContribution: string; expectedReturnPercent: string; categoryIds: string[] }
const EMPTY: GoalForm = { name: '', targetAmount: '', targetDate: '', monthlyContribution: '', expectedReturnPercent: '10', categoryIds: [] }

const formOf = (goal: Goal): GoalForm => ({
  name: goal.name, targetAmount: String(goal.targetAmount), targetDate: goal.targetDate,
  monthlyContribution: String(goal.monthlyContribution), expectedReturnPercent: String(goal.expectedReturnPercent), categoryIds: goal.categoryIds,
})

export function requestOf(form: GoalForm) {
  const optional = (value: string) => value === '' ? null : Number(value)
  return {
    name: form.name, targetAmount: Number(form.targetAmount), targetDate: form.targetDate,
    monthlyContribution: optional(form.monthlyContribution), expectedReturnPercent: optional(form.expectedReturnPercent),
    categoryIds: form.categoryIds,
  }
}

function GoalCard({ goal, categoryNames, onEdit, onDelete }: { goal: Goal; categoryNames: string; onEdit: () => void; onDelete: () => void }) {
  const status = goal.onTrack ? 'On track' : `Needs ${money(goal.requiredMonthly, goal.currency)}/month`
  return <article className="panel goal-card">
    <div className="panel-heading"><h3>{goal.name}</h3><span className={goal.onTrack ? 'positive' : 'negative'}>{status}</span></div>
    <div className="pulse-row"><span>Target</span><strong>{money(goal.targetAmount, goal.currency)} by {since(goal.targetDate)}</strong></div>
    <div className="pulse-row"><span>Today</span><strong>{money(goal.currentValue, goal.currency)} ({goal.progressPercent.toFixed(0)}%)</strong></div>
    <div className="bar"><i style={{ width: `${Math.min(100, goal.progressPercent)}%` }} /></div>
    <div className="pulse-row"><span>Projected</span><strong>{money(goal.projectedValue, goal.currency)}</strong></div>
    <p className="hint">{categoryNames} · {money(goal.monthlyContribution, goal.currency)}/month at {goal.expectedReturnPercent}% a year · {goal.monthsLeft} months left</p>
    <div className="goal-actions actions"><button className="primary-link" onClick={onEdit}>Edit</button><button className="danger-link" onClick={onDelete}>Delete</button></div>
  </article>
}

export function GoalsPanel({ dataVersion }: { dataVersion: number }) {
  const [goals, setGoals] = useState<Goal[] | null>(null)
  const [categories, setCategories] = useState<Category[]>([])
  const [editing, setEditing] = useState<Goal | 'new' | null>(null)
  const [form, setForm] = useState<GoalForm>(EMPTY)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const load = () => api<Goal[]>('/api/goals').then(setGoals).catch(e => { setGoals([]); setError(e instanceof Error ? e.message : 'Could not load goals') })
  useEffect(() => { void load() }, [dataVersion])
  useEffect(() => { api<Category[]>('/api/categories').then(c => setCategories(c.filter(x => x.kind === 'ASSET'))).catch(() => setCategories([])) }, [dataVersion])

  const open = (goal: Goal | 'new') => { setEditing(goal); setForm(goal === 'new' ? EMPTY : formOf(goal)); setError('') }
  const set = <K extends keyof GoalForm>(key: K, value: GoalForm[K]) => setForm(f => ({ ...f, [key]: value }))
  const toggleCategory = (id: string) => set('categoryIds', form.categoryIds.includes(id) ? form.categoryIds.filter(c => c !== id) : [...form.categoryIds, id])
  const namesOf = (ids: string[]) => ids.length === 0 ? 'All assets' : ids.map(id => categories.find(c => c.id === id)?.name ?? 'Deleted category').join(', ')

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const isNew = editing === 'new'
    const url = isNew ? '/api/goals' : `/api/goals/${(editing as Goal).id}`
    try {
      await api(url, { method: isNew ? 'POST' : 'PUT', body: JSON.stringify(requestOf(form)) })
      setEditing(null); await load()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save the goal') }
    finally { setSaving(false) }
  }
  const remove = async (goal: Goal) => {
    if (!confirm(`Delete the goal "${goal.name}"?`)) return
    try { await api(`/api/goals/${goal.id}`, { method: 'DELETE' }); await load() }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not delete the goal') }
  }

  return <section className="panel">
    <div className="panel-heading"><h3>Goals</h3><button className="outline compact" onClick={() => open('new')}>+ Add goal</button></div>
    {error && <p className="form-error">{error}</p>}
    {editing && <form className="goal-form" onSubmit={submit}>
      <div className="form-grid">
        <Field label="Name" required><input required maxLength={128} value={form.name} onChange={e => set('name', e.target.value)} placeholder="e.g. House down payment" /></Field>
        <Field label="Target amount" required><input required type="number" min="1" value={form.targetAmount} onChange={e => set('targetAmount', e.target.value)} /></Field>
        <Field label="Target date" required><input required type="date" value={form.targetDate} onChange={e => set('targetDate', e.target.value)} /></Field>
        <Field label="Monthly saving"><input type="number" min="0" value={form.monthlyContribution} onChange={e => set('monthlyContribution', e.target.value)} placeholder="0" /></Field>
        <Field label="Expected return (% a year)"><input type="number" min="-50" max="50" step="0.5" value={form.expectedReturnPercent} onChange={e => set('expectedReturnPercent', e.target.value)} /></Field>
        <Field label="Counts these categories" wide><div className="check-row">
          {categories.map(c => <label key={c.id}><input type="checkbox" checked={form.categoryIds.includes(c.id)} onChange={() => toggleCategory(c.id)} /> {c.name}</label>)}
          {categories.length === 0 && <span className="hint">No asset categories yet.</span>}
        </div><small className="hint">None ticked = all assets.</small></Field>
      </div>
      <div className="modal-actions"><button type="button" className="outline" onClick={() => setEditing(null)}>Cancel</button>
        <button className="primary" disabled={saving}>{saving ? 'Saving…' : editing === 'new' ? 'Add goal' : 'Save goal'}</button></div>
    </form>}
    {goals === null ? <p className="hint">Loading goals…</p>
      : goals.length === 0 && !editing ? <p className="hint">No goals yet. Add one to see whether you're on track and what monthly saving gets you there.</p>
      : <div className="goal-grid">{goals.map(g => <GoalCard key={g.id} goal={g} categoryNames={namesOf(g.categoryIds)}
          onEdit={() => open(g)} onDelete={() => void remove(g)} />)}</div>}
  </section>
}
