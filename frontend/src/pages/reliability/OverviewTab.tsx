import { Bar, Chip, CiText, Kpi, Loading, num, pct, signed, useFetch, type Any } from './ui'

const TYPE_LABEL: Record<string, string> = {
  SAME_SITE_REVISIT: '同场所复查',
  BLIND_PHOTO: '盲审照片',
  ONSITE_VS_MEASURE: '现场量测'
}

export default function OverviewTab({ field }: { field: string }) {
  const cons = useFetch<Any>(`/reliability/consistency?fieldId=${encodeURIComponent(field)}`)
  const rank = useFetch<Any>(`/reliability/ranking?fieldId=${encodeURIComponent(field)}`)
  const c = cons.data
  const o: Any = c?.overall
  const gate: Any = c?.gate

  return (
    <div>
      <Loading loading={cons.loading} error={cons.error} />
      {c && o && gate && (
        <>
          <div className={`notice ${gate.rankingEligible ? 'ok' : 'danger'}`}>
            <b>{gate.rankingEligible ? '✅ 一致性闸门通过：可驱动跨区排名' : '⛔ 一致性不足：字段可登记，但不得驱动跨区排名'}</b>
            <div style={{ marginTop: 4 }}>{gate.decision}</div>
          </div>

          <div className="kpi-grid">
            <Kpi label="配对样本" value={o.n} sub={`等级类别 K=${o.categories}`} />
            <Kpi label="简单一致率" value={pct(o.exactAgreement)}
              cls={o.exactAgreement >= 0.6 ? 'good' : 'warn'} sub="等级完全相同的比例" />
            <Kpi label="二次加权一致率" value={num(o.weightedAgreementQuadratic)} cls="good"
              sub={`线性加权 ${num(o.weightedAgreementLinear)}；相邻分歧部分给分`} />
            <Kpi label="机会校正加权 κ" value={num(o.kappaQuadratic)}
              cls={o.kappaQuadratic >= 0.6 ? 'good' : 'bad'}>
              <CiText ci={o.kappaQuadraticCi95} />
            </Kpi>
            <Kpi label="系统性偏差 Δ" value={signed(o.meanSignedDiff)}
              cls={o.biasSignificant ? 'warn' : 'good'}
              sub={`方向：${biasLabel(o.biasDirection)}（B−A，级）`}>
              <CiText ci={o.biasCi95} />
            </Kpi>
          </div>

          <div className="panel">
            <h3>闸门判据（阈值可在 application.yml 调整）</h3>
            {gate.criteria.map((cr: Any) => (
              <div key={cr.key} className={`gate-crit ${cr.pass ? 'pass' : 'fail'}`}>
                <span className="mark">{cr.pass ? '✓' : '✕'}</span>
                <div>
                  <div><b>{cr.label}</b></div>
                  <div className="detail">{cr.detail}</div>
                </div>
              </div>
            ))}
          </div>

          <div className="panel">
            <h3>三类比对通道的等级差</h3>
            <div className="rel-grid-3">
              {Object.entries(c.byType).map(([t, r]: [string, Any]) => (
                <div key={t} className="gate-crit" style={{ display: 'block' }}>
                  <div style={{ marginBottom: 6 }}><b>{TYPE_LABEL[t] || t}</b> <Chip tone="muted">n={r.n}</Chip></div>
                  {r.n === 0 ? <div className="small muted-text">暂无配对</div> : (
                    <>
                      <Bar ratio={r.kappaQuadratic == null ? 0 : Math.max(0, r.kappaQuadratic)}
                        cls={r.kappaQuadratic >= 0.6 ? 'ok' : 'bad'}
                        label="加权 κ" valueText={num(r.kappaQuadratic)} />
                      <div className="small muted-text" style={{ paddingLeft: 4 }}>
                        完全一致 {pct(r.exactAgreement)} · 加权一致 {num(r.weightedAgreementQuadratic)} · 偏差 {signed(r.meanSignedDiff)}
                      </div>
                    </>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <h3>跨区排名（受一致性闸门保护）</h3>
            {rank.data?.rankingEligible ? (
              <>
                <p className="small muted-text">{rank.data.metric}</p>
                {rank.data.ranking.map((r: Any) => (
                  <Bar key={r.district} ratio={(r.meanLevel - 1) / 3}
                    cls={r.rank === 1 ? 'brand' : r.rank === 2 ? 'warn' : 'ok'}
                    label={`#${r.rank} ${r.district}`}
                    valueText={`均值 ${num(r.meanLevel, 2)} · ${r.site_count} 场所 / ${r.observation_count} 评`} />
                ))}
                <div className="table-wrap" style={{ marginTop: 12 }}>
                  <table>
                    <thead><tr><th>名次</th><th>辖区</th><th>平均等级</th><th>场所数</th><th>观测数</th></tr></thead>
                    <tbody>
                      {rank.data.ranking.map((r: Any) => (
                        <tr key={r.district}><td>{r.rank}</td><td><b>{r.district}</b></td>
                          <td>{num(r.meanLevel, 3)}</td><td>{r.site_count}</td><td>{r.observation_count}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : (
              <div className="notice danger">
                <b>排名接口拒绝产出（HTTP 409）。</b>
                <div style={{ margin: '6px 0' }}>{rank.error || '当前字段一致性未达闸门。'}</div>
                <div className="small">字段仍保留在字段目录中、可继续登记与填报；在一致性修复前，系统不返回任何辖区名次，避免把测量噪声误读为区域风险差异。</div>
                <div className="mini-actions">
                  <button className="btn ghost tiny" onClick={() => rank.reload()}>重新请求 /ranking</button>
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

function biasLabel(d: string) {
  return ({
    UPWARD: '比较侧系统性偏严/偏高',
    DOWNWARD: '比较侧系统性偏松/偏低',
    NO_SIGNIFICANT_BIAS: '无显著系统偏差',
    INSUFFICIENT_DATA: '数据不足'
  } as Record<string, string>)[d] || d
}
