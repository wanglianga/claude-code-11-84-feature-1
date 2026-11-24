import { useState } from 'react'
import { api } from '../../api'
import { ActionLog, Bar, Chip, LevelDots, Loading, num, pct, signed, useFetch, type Any } from './ui'

export default function AnchorsTab() {
  const cal = useFetch<Any>('/reliability/anchors/calibration')
  const anchors = useFetch<Any[]>('/reliability/anchors')
  const meta = useFetch<Any>('/reliability/meta')
  const [log, setLog] = useState<string[]>([])
  const [score, setScore] = useState({ anchorId: 'AP-01', inspectorCode: 'INS-ZHANG', givenLevel: 1 })
  const push = (s: string) => setLog((p) => [new Date().toLocaleTimeString() + '  ' + s, ...p])

  async function evaluate(body: Any, label: string) {
    try {
      const r = await api<Any>('/reliability/anchors/calibration/evaluate', { method: 'POST', body: JSON.stringify(body) })
      const x = r.result
      push(`${label}：n=${x.n}，完全一致 ${pct(x.exactAgreement)}，加权 κ=${num(x.kappaQuadratic)}，偏差 ${signed(x.meanSignedDiff)}`)
    } catch (e) {
      push(`${label} 被拒绝（预期）：${e instanceof Error ? e.message : String(e)}`)
    }
  }
  async function submitScore() {
    try {
      await api('/reliability/anchors/scores', { method: 'POST', body: JSON.stringify(score) })
      push(`已提交评分 ${score.anchorId} / ${score.inspectorCode} = ${score.givenLevel}（幂等 upsert）`)
      cal.reload()
    } catch (e) { push('评分失败：' + (e instanceof Error ? e.message : String(e))) }
  }

  const c = cal.data
  return (
    <div>
      <div className="notice danger">
        <b>锚样分类铁律：临时施工照片 ≠ 永久设施锚样。</b>
        <div style={{ marginTop: 4 }}>{c?.rule?.message}</div>
      </div>

      <div className="panel">
        <h3>锚样校准操作</h3>
        <div className="row">
          <button className="btn ghost" onClick={() => evaluate({ anchorClass: 'PERMANENT' }, '评估永久设施锚样')}>评估永久设施锚样</button>
          <button className="btn ghost" onClick={() => evaluate({ anchorClass: 'TEMP_CONSTRUCTION' }, '评估临时施工锚样')}>评估临时施工锚样</button>
          <button className="btn" onClick={() => evaluate({ poolAcrossClasses: true }, '尝试合并两类')}>尝试把两类合并评分（应 422）</button>
        </div>
        <ActionLog log={log} />
        <div className="row" style={{ marginTop: 12 }}>
          <div><label>锚样</label>
            <select value={score.anchorId} onChange={(e) => setScore({ ...score, anchorId: e.target.value })}>
              {(anchors.data || []).map((a) => <option key={a.anchor_id} value={a.anchor_id}>{a.anchor_id} · {a.anchor_class === 'PERMANENT' ? '永久' : '临施'} · 基准 {a.reference_level}</option>)}
            </select></div>
          <div><label>检查员</label>
            <select value={score.inspectorCode} onChange={(e) => setScore({ ...score, inspectorCode: e.target.value })}>
              {(meta.data?.inspectors || []).filter((i: Any) => i.inspector_code !== 'INS-MEAS').map((i: Any) =>
                <option key={i.inspector_code} value={i.inspector_code}>{i.display_name}（{i.inspector_code}）</option>)}
            </select></div>
          <div><label>评分等级</label>
            <select value={score.givenLevel} onChange={(e) => setScore({ ...score, givenLevel: Number(e.target.value) })}>
              {[1, 2, 3, 4].map((v) => <option key={v} value={v}>{v}</option>)}
            </select></div>
          <button className="btn ghost" onClick={submitScore}>提交/覆盖评分</button>
        </div>
      </div>

      <Loading loading={cal.loading} error={cal.error} />
      {c && (
        <>
          <div className="rel-grid-2">
            {(['PERMANENT', 'TEMP_CONSTRUCTION'] as const).map((cls) => {
              const r = c.perClass[cls]
              return (
                <div key={cls} className="panel">
                  <h3>{cls === 'PERMANENT' ? '永久设施锚样校准' : '临时施工锚样校准'} <Chip tone={cls === 'PERMANENT' ? 'brand' : 'warn'}>{cls}</Chip></h3>
                  <div className="rel-grid-3">
                    <div><div className="small muted-text">锚样评分 n</div><div className="kappa-gauge" style={{ fontSize: 22, fontWeight: 700 }}>{r.n}</div></div>
                    <div><div className="small muted-text">完全一致</div><div style={{ fontSize: 22, fontWeight: 700 }}>{pct(r.exactAgreement)}</div></div>
                    <div><div className="small muted-text">加权 κ</div><div style={{ fontSize: 22, fontWeight: 700 }} className={r.kappaQuadratic >= 0.6 ? 'good' : 'bad'}>{num(r.kappaQuadratic)}</div></div>
                  </div>
                  <Bar ratio={Math.max(0, r.weightedAgreementQuadratic)} cls="ok" label="二次加权一致率" valueText={num(r.weightedAgreementQuadratic)} />
                  <div className="small muted-text">平均偏差 {signed(r.meanSignedDiff)} · {dirLabel(r.biasDirection)}</div>
                </div>
              )
            })}
          </div>

          <div className="panel">
            <h3>检查员锚样偏差（负=偏松，正=偏严）</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>检查员</th><th>辖区</th><th>n</th><th>完全一致</th><th>加权 κ</th><th style={{ minWidth: 220 }}>平均偏差（−1…+1）</th><th>分类偏差 永久/临施</th><th>标记</th></tr></thead>
                <tbody>
                  {c.perInspector.map((p: Any) => (
                    <tr key={p.inspector_code}>
                      <td><b>{p.display_name}</b><div className="small muted-text">{p.inspector_code}</div></td>
                      <td>{p.district}</td><td>{p.n}</td>
                      <td>{pct(p.exactAgreement)}</td>
                      <td>{num(p.kappaQuadratic)}</td>
                      <td><BiasDiverge v={p.meanSignedBias} /></td>
                      <td className="small mono">{signed(p.classBias.PERMANENT)} / {signed(p.classBias.TEMP_CONSTRUCTION)}</td>
                      <td>{p.flagged ? <Chip tone="danger">建议校准/培训</Chip> : <Chip tone="ok">稳定</Chip>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel">
            <h3>固定锚样的场景覆盖：解释强，但新场景覆盖有限</h3>
            <div className="rel-grid-3" style={{ marginBottom: 10 }}>
              <div className="kpi"><div className="num">{c.coverage.coveredCount}/{c.coverage.universeCount}</div><div className="lbl">场景覆盖 {pct(c.coverage.coverageRate, 0)}</div></div>
              <div className="kpi"><div className="num bad" style={{ color: 'var(--danger)' }}>{c.coverage.novelCovered}/{c.coverage.novelCount}</div><div className="lbl">新兴场景覆盖（0%）</div></div>
            </div>
            <div>
              {c.coverage.scenarios.map((s: Any) => (
                <Chip key={s.code} tone={s.covered ? 'ok' : s.novel ? 'danger' : 'muted'}>
                  {s.covered ? '✓ ' : s.novel ? '新·未覆盖 ' : '✕ '}{s.name}
                </Chip>
              ))}
            </div>
            <p className="notice warn" style={{ marginTop: 12, marginBottom: 0 }}>{c.coverage.limitation}</p>
          </div>
        </>
      )}

      <div className="panel">
        <h3>锚样清单（权威基准等级）</h3>
        <Loading loading={anchors.loading} error={anchors.error} />
        <div className="table-wrap">
          <table>
            <thead><tr><th>锚样</th><th>类别</th><th>场景</th><th>基准等级</th><th>条件码</th><th>来源</th></tr></thead>
            <tbody>
              {(anchors.data || []).map((a) => (
                <tr key={a.anchor_id}>
                  <td><code>{a.anchor_id}</code></td>
                  <td><Chip tone={a.anchor_class === 'PERMANENT' ? 'brand' : 'warn'}>
                    {a.anchor_class === 'PERMANENT' ? '永久设施' : '临时施工'}</Chip></td>
                  <td>{a.scenario_name}<div className="small muted-text">{a.scenario_code}</div></td>
                  <td><b>{a.reference_level}</b> <LevelDots level={a.reference_level} /></td>
                  <td><span className={`cond-code ${String(a.condition_code).includes('ADV') || String(a.condition_code).includes('DUST') ? 'adv' : ''}`}>{a.condition_code}</span></td>
                  <td className="small" style={{ maxWidth: 280 }}>{a.source_desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function BiasDiverge({ v }: { v: number }) {
  const width = Math.min(50, Math.abs(v) * 50)
  const neg = v < 0
  const style: React.CSSProperties = neg
    ? { right: '50%', width: `${width}%` }
    : { left: '50%', width: `${width}%` }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div className="diverge" style={{ flex: 1 }}>
        <div className="zero" />
        {Math.abs(v) > 0.001 && <div className={`ef ${neg ? 'neg' : 'pos'}`} style={style} />}
      </div>
      <span className="mono small" style={{ width: 44, textAlign: 'right' }}>{signed(v)}</span>
    </div>
  )
}

function dirLabel(d: string) {
  return ({ UPWARD: '偏严', DOWNWARD: '偏松', NO_SIGNIFICANT_BIAS: '无显著偏差', INSUFFICIENT_DATA: '数据不足' } as Record<string, string>)[d] || d
}
