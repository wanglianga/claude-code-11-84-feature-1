import { useState } from 'react'
import { api, getUser } from '../../api'
import { Chip, Loading, useFetch, type Any } from './ui'

export default function FeedbackTab() {
  const { data, loading, error, reload } = useFetch<Any[]>('/reliability/feedback')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const user = getUser()
  const canWrite = user?.role === 'CITY_ADMIN' || user?.role === 'DISTRICT_ANALYST'

  async function generate() {
    setBusy(true); setMsg(null)
    try {
      const list = await api<Any[]>('/reliability/feedback/generate', { method: 'POST' })
      setMsg(`已按锚样偏差与配对分歧生成/刷新 ${list.length} 条反馈建议（已认领/完成状态保留）。`)
      reload()
    } catch (e) { setMsg('生成失败：' + (e instanceof Error ? e.message : String(e))) }
    finally { setBusy(false) }
  }
  async function setStatus(id: string, status: string) {
    try {
      await api(`/reliability/feedback/${id}/status`, { method: 'POST', body: JSON.stringify({ status }) })
      reload()
    } catch (e) { setMsg('状态更新失败：' + (e instanceof Error ? e.message : String(e))) }
  }

  const open = (data || []).filter((f) => f.status === 'OPEN')
  const acked = (data || []).filter((f) => f.status === 'ACKED')
  const done = (data || []).filter((f) => f.status === 'DONE')

  return (
    <div>
      <div className="panel">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <div>
            <h3 style={{ margin: 0 }}>培训反馈台</h3>
            <p className="small muted-text" style={{ margin: '6px 0 0' }}>
              汇总每名检查员的锚样偏差与复检/盲审分歧，生成个性化带教建议；闭环 OPEN → ACKED → DONE。
            </p>
          </div>
          <button className="btn" disabled={busy || !canWrite} onClick={generate}>
            {busy ? '分析中…' : '根据最新一致性结果生成/刷新建议'}
          </button>
        </div>
        {!canWrite && <div className="notice warn small" style={{ marginTop: 10 }}>当前角色为审计员（AUDITOR），仅有查看权限；生成与状态流转需分析员/管理员。</div>}
        {msg && <div className="notice info small" style={{ marginTop: 10 }}>{msg}</div>}
      </div>

      <Loading loading={loading} error={error} />

      <Board title={`待处理 OPEN（${open.length}）`} items={open} canWrite={!!canWrite} onStatus={setStatus} />
      <Board title={`已认领 ACKED（${acked.length}）`} items={acked} canWrite={!!canWrite} onStatus={setStatus} />
      <Board title={`已完成 DONE（${done.length}）`} items={done} canWrite={!!canWrite} onStatus={setStatus} />
    </div>
  )
}

function Board({ title, items, canWrite, onStatus }: {
  title: string; items: Any[]; canWrite: boolean; onStatus: (id: string, s: string) => void
}) {
  return (
    <div className="panel">
      <h3>{title}</h3>
      {items.length === 0 && <div className="small muted-text">暂无条目</div>}
      {items.map((f) => (
        <div key={f.feedback_id} className={`fb-card ${f.severity}`}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
            <div>
              <b>{f.inspector_name || f.inspector_code}</b>
              <span className="small muted-text">（{f.inspector_code} · {f.district}）</span>{' '}
              <Chip tone={f.severity === 'HIGH' ? 'danger' : f.severity === 'MEDIUM' ? 'warn' : 'info'}>{f.severity}</Chip>
              <Chip tone="muted">{f.status}</Chip>
            </div>
            <code className="small muted-text">{f.feedback_id}</code>
          </div>
          <div style={{ margin: '6px 0' }}><b>{f.topic}</b></div>
          <div className="basis">
            量化依据：锚样平均偏差 {String(f.basis_json?.anchorMeanBias ?? '—')}
            {f.basis_json?.pairDisagreements != null && ` · 配对分歧 ${f.basis_json.pairDisagreements} 次`}
            {f.basis_json?.severeDisagreements != null && ` · 跨两级严重分歧 ${f.basis_json.severeDisagreements} 次`}
          </div>
          <div className="small">培训建议：{f.suggestion}</div>
          {canWrite && (
            <div className="mini-actions">
              {f.status === 'OPEN' && <button className="btn tiny" onClick={() => onStatus(f.feedback_id, 'ACKED')}>认领并安排培训</button>}
              {f.status === 'ACKED' && <button className="btn tiny" onClick={() => onStatus(f.feedback_id, 'DONE')}>完成培训与复测</button>}
              {f.status !== 'OPEN' && <button className="btn ghost tiny" onClick={() => onStatus(f.feedback_id, 'OPEN')}>重新打开</button>}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
