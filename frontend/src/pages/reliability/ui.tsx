import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { api } from '../../api'

export type Any = any

export function pct(x: Any, digits = 1): string {
  if (x === null || x === undefined || Number.isNaN(Number(x))) return '—'
  return (Number(x) * 100).toFixed(digits) + '%'
}
export function num(x: Any, digits = 3): string {
  if (x === null || x === undefined || Number.isNaN(Number(x))) return 'NA'
  return Number(x).toFixed(digits)
}
export function signed(x: Any, digits = 2): string {
  if (x === null || x === undefined || Number.isNaN(Number(x))) return 'NA'
  const v = Number(x)
  return (v > 0 ? '+' : '') + v.toFixed(digits)
}

export function CiText({ ci }: { ci: Any }) {
  if (!ci) return <span className="ci-line">95% 区间不可估计</span>
  return <span className="ci-line">95% CI [{num(ci.lower, 3)}, {num(ci.upper, 3)}]</span>
}

export function Kpi({ label, value, cls, sub, children }: {
  label: string; value: ReactNode; cls?: string; sub?: ReactNode; children?: ReactNode
}) {
  return (
    <div className="kpi rel-kpi">
      <div className={`num ${cls || ''}`}>{value}</div>
      <div className="lbl">{label}</div>
      {sub && <div className="sub">{sub}</div>}
      {children}
    </div>
  )
}

/** 水平条；ratio∈[0,1] */
export function Bar({ ratio, cls, label, valueText }: {
  ratio: number; cls?: string; label?: ReactNode; valueText?: ReactNode
}) {
  const w = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0)) * 100
  return (
    <div className="hbar-row">
      <div className="lab" title={typeof label === 'string' ? label : undefined}>{label}</div>
      <div className="hbar-track"><div className={`hbar-fill ${cls || ''}`} style={{ width: `${w}%` }} /></div>
      <div className="hbar-val">{valueText ?? `${w.toFixed(0)}%`}</div>
    </div>
  )
}

export function LevelDots({ level }: { level: number }) {
  return (
    <span className="lvl-dots" title={`等级 ${level}`}>
      {[1, 2, 3, 4].map((i) => <i key={i} className={i <= level ? `on l${level}` : ''} />)}
    </span>
  )
}

export function DiffChip({ d }: { d: number }) {
  const cls = d === 0 ? 'zero' : Math.abs(d) >= 2 ? 'big' : d > 0 ? 'pos' : 'neg'
  return <span className={`arrow-diff ${cls}`}>{d === 0 ? '0 一致' : (d > 0 ? `+${d}` : d)}</span>
}

export function Chip({ tone, children }: { tone?: 'ok' | 'warn' | 'danger' | 'info' | 'brand' | 'muted'; children: ReactNode }) {
  return <span className={`chip ${tone || 'muted'}`}>{children}</span>
}

export function useFetch<T = Any>(path: string | null) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [tick, setTick] = useState(0)
  const reload = useCallback(() => setTick((t) => t + 1), [])
  useEffect(() => {
    if (!path) return
    let alive = true
    setLoading(true); setError(null)
    api<T>(path)
      .then((d) => { if (alive) { setData(d); setLoading(false) } })
      .catch((e) => { if (alive) { setError(e instanceof Error ? e.message : String(e)); setLoading(false) } })
    return () => { alive = false }
  }, [path, tick])
  return { data, error, loading, reload }
}

export function Loading({ loading, error }: { loading?: boolean; error?: string | null }) {
  if (error) return <div className="notice danger">加载失败：{error}</div>
  if (loading) return <div className="muted-text small">加载中…</div>
  return null
}

export function ActionLog({ log }: { log: string[] }) {
  if (!log.length) return null
  return (
    <div style={{ marginTop: 10 }}>
      {log.map((l, i) => <div key={i} className="notice info small" style={{ marginBottom: 6 }}>{l}</div>)}
    </div>
  )
}
