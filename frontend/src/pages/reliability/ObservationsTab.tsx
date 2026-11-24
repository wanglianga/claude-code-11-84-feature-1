import { useState } from 'react'
import { Chip, LevelDots, Loading, useFetch, type Any } from './ui'

const MODE_LABEL: Record<string, string> = {
  ONSITE: '现场', REVISIT: '复查', PHOTO_BLIND: '盲审照片', MEASURE: '量测'
}

export default function ObservationsTab() {
  const [fieldId, setFieldId] = useState('std.li_iso_level')
  const [mode, setMode] = useState('')
  const qs = new URLSearchParams({ fieldId })
  if (mode) qs.set('mode', mode)
  const { data, loading, error } = useFetch<Any[]>(`/reliability/observations?${qs.toString()}`)
  const meta = useFetch<Any>('/reliability/meta')

  return (
    <div>
      <div className="panel">
        <h3>观察条件编码字典</h3>
        <p className="small muted-text">
          每次评定除记录标准化等级 1..4 外，同时编码检查员、观察方式与照片/量测条件；原始取值体系（L 码 / 字母 / 百分制 / 间距米）原样保留，体现“取值混杂”，统计前先标准化到同一序次。
        </p>
        <div className="rel-grid-3">
          {(meta.data?.conditionDimensions || []).map((d: Any) => (
            <div key={d.key} className="gate-crit" style={{ display: 'block' }}>
              <b>{d.label}</b> <code className="small">{d.key}</code>
              <div style={{ marginTop: 4 }}>{d.codes.map((c: string) => <Chip key={c} tone="info">{c}</Chip>)}</div>
            </div>
          ))}
        </div>
      </div>

      <div className="panel">
        <div className="row" style={{ marginBottom: 12 }}>
          <div>
            <label>字段</label>
            <select value={fieldId} onChange={(e) => setFieldId(e.target.value)}>
              <option value="std.li_iso_level">std.li_iso_level 标准等级</option>
              <option value="std.li_photo_grade">std.li_photo_grade 照片快评</option>
            </select>
          </div>
          <div>
            <label>观察方式</label>
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="">全部方式</option>
              {Object.entries(MODE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="muted-text small" style={{ alignSelf: 'center' }}>{data?.length ?? 0} 条观察</div>
        </div>
        <Loading loading={loading} error={error} />
        <div className="table-wrap">
          <table>
            <thead><tr>
              <th>观察 ID</th><th>场所/辖区</th><th>检查员</th><th>方式</th>
              <th>原始取值</th><th>标准等级</th><th>条件编码</th><th>量测</th><th>日期</th>
            </tr></thead>
            <tbody>
              {(data || []).map((o) => (
                <tr key={o.obs_id}>
                  <td><code className="mono">{o.obs_id}</code></td>
                  <td>{o.site_name || <span className="muted-text">照片库</span>}
                    <div className="small muted-text">{o.district || '—'}</div></td>
                  <td>{o.inspector_name || o.inspector_code}</td>
                  <td><Chip tone={o.mode === 'MEASURE' ? 'ok' : o.mode === 'PHOTO_BLIND' ? 'info' : 'muted'}>
                    {MODE_LABEL[o.mode] || o.mode}</Chip></td>
                  <td><code>{o.raw_value}</code><div className="small muted-text">{o.raw_system}</div></td>
                  <td><b>{o.level_value}</b> <LevelDots level={o.level_value} /></td>
                  <td><ConditionCode o={o} /></td>
                  <td>{o.metric_value != null ? `${o.metric_value} m` : '—'}</td>
                  <td className="small">{String(o.observed_at).slice(0, 10)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function ConditionCode({ o }: { o: Any }) {
  const c = o.conditions_json || {}
  const keys = ['lighting', 'distance', 'occlusion', 'angle', 'clarity']
  const adv = o.photo_condition_code && String(o.photo_condition_code).includes('ADV')
  return (
    <div>
      {o.photo_condition_code
        ? <span className={`cond-code ${adv ? 'adv' : ''}`}>{o.photo_condition_code}</span>
        : <span className="muted-text small">—</span>}
      {o.mode === 'PHOTO_BLIND' && (
        <div className="small" style={{ marginTop: 4, maxWidth: 210 }}>
          {keys.filter((k) => c[k]).map((k) => <Chip key={k} tone={c.quality === 'ADVERSE' ? 'warn' : 'muted'}>{String(c[k])}</Chip>)}
        </div>
      )}
    </div>
  )
}
