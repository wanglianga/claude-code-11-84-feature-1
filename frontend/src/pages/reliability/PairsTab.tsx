import { useState } from 'react'
import { api } from '../../api'
import { ActionLog, Chip, DiffChip, LevelDots, Loading, useFetch, type Any } from './ui'

const TYPE_LABEL: Record<string, string> = {
  SAME_SITE_REVISIT: '同场所复查', BLIND_PHOTO: '盲审照片', ONSITE_VS_MEASURE: '现场量测'
}

export default function PairsTab() {
  const [fieldId, setFieldId] = useState('std.li_iso_level')
  const [pairType, setPairType] = useState('')
  const qs = new URLSearchParams()
  if (fieldId) qs.set('fieldId', fieldId)
  if (pairType) qs.set('pairType', pairType)
  const { data, loading, error, reload } = useFetch<Any[]>(`/reliability/pairs?${qs.toString()}`)

  const [form, setForm] = useState({ pairId: '', pairType: 'SAME_SITE_REVISIT', obsA: 'O-S-Y01-ON', obsB: 'O-S-Y01-RV' })
  const [log, setLog] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  function push(s: string) { setLog((p) => [new Date().toLocaleTimeString() + '  ' + s, ...p]) }

  async function submit() {
    setBusy(true)
    try {
      const r = await api<Any>('/reliability/pairs', { method: 'POST', body: JSON.stringify(form) })
      push(`已登记 ${r.pairId}：${r.levelA} → ${r.levelB}，等级差 ${r.signedDiff >= 0 ? '+' : ''}${r.signedDiff}`)
      reload()
    } catch (e) {
      push('登记失败：' + (e instanceof Error ? e.message : String(e)))
    } finally { setBusy(false) }
  }

  return (
    <div>
      <div className="panel">
        <h3>复检配对账</h3>
        <div className="row" style={{ marginBottom: 12 }}>
          <div>
            <label>字段</label>
            <select value={fieldId} onChange={(e) => setFieldId(e.target.value)}>
              <option value="">全部字段</option>
              <option value="std.li_iso_level">std.li_iso_level 标准等级</option>
              <option value="std.li_photo_grade">std.li_photo_grade 照片快评</option>
            </select>
          </div>
          <div>
            <label>配对类型</label>
            <select value={pairType} onChange={(e) => setPairType(e.target.value)}>
              <option value="">全部类型</option>
              {Object.entries(TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="muted-text small" style={{ alignSelf: 'center' }}>共 {data?.length ?? 0} 对</div>
        </div>
        <Loading loading={loading} error={error} />
        <div className="table-wrap">
          <table>
            <thead><tr>
              <th>配对</th><th>类型</th><th>场所/辖区</th><th>A 侧</th><th></th><th>B 侧</th>
              <th>等级差</th><th>照片条件</th>
            </tr></thead>
            <tbody>
              {(data || []).map((p) => (
                <tr key={p.pair_id}>
                  <td><code>{p.pair_id}</code><div className="small muted-text">{p.field_id}</div></td>
                  <td><Chip tone="brand">{TYPE_LABEL[p.pair_type] || p.pair_type}</Chip></td>
                  <td>{p.site_name || '照片库（无固定场所）'}<div className="small muted-text">{p.district || '—'}</div></td>
                  <Side level={p.level_a} name={p.inspector_a_name} code={p.inspector_a} mode={p.mode_a} />
                  <td className="muted-text">→</td>
                  <Side level={p.level_b} name={p.inspector_b_name} code={p.inspector_b} mode={p.mode_b} />
                  <td><DiffChip d={p.signed_diff} />{p.exact_match && <div className="small" style={{ color: 'var(--ok)' }}>重复同判</div>}</td>
                  <td>{p.condition_code_b
                    ? <span className={`cond-code ${String(p.condition_code_b).includes('ADV') ? 'adv' : ''}`}>{p.condition_code_b}</span>
                    : <span className="muted-text small">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel">
        <h3>登记新配对（写操作需分析员/管理员）</h3>
        <div className="row">
          <div><label>配对 ID（可空，自动生成）</label>
            <input value={form.pairId} placeholder="P-..." onChange={(e) => setForm({ ...form, pairId: e.target.value })} /></div>
          <div><label>类型</label>
            <select value={form.pairType} onChange={(e) => setForm({ ...form, pairType: e.target.value })}>
              {Object.entries(TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select></div>
          <div><label>A 观察 ID</label>
            <input value={form.obsA} onChange={(e) => setForm({ ...form, obsA: e.target.value })} /></div>
          <div><label>B 观察 ID</label>
            <input value={form.obsB} onChange={(e) => setForm({ ...form, obsB: e.target.value })} /></div>
          <button className="btn" disabled={busy} onClick={submit}>{busy ? '提交中…' : '加入配对账'}</button>
        </div>
        <div className="small muted-text" style={{ marginTop: 8 }}>
          示例：O-S-J02-ON → O-S-J02-RV（复查）；O-S-B01-ON → O-S-B01-PH（盲审照片）；O-S-Y04-ON → O-S-Y04-MS（量测）。重复固定 ID 提交为幂等。
        </div>
        <ActionLog log={log} />
      </div>
    </div>
  )
}

function Side({ level, name, code, mode }: { level: number; name?: string; code?: string; mode: string }) {
  return (
    <td>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <b>{level}</b><LevelDots level={level} />
      </div>
      <div className="small">{name || code} <Chip tone="muted">{mode}</Chip></div>
    </td>
  )
}
