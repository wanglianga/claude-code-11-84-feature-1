import { Bar, CiText, Loading, num, pct, signed, useFetch, type Any } from './ui'

const TYPE_LABEL: Record<string, string> = {
  SAME_SITE_REVISIT: '同场所复查',
  BLIND_PHOTO: '盲审照片',
  ONSITE_VS_MEASURE: '现场量测'
}

export default function ConsistencyTab({ field }: { field: string }) {
  const { data, loading, error } = useFetch<Any>(`/reliability/consistency?fieldId=${encodeURIComponent(field)}`)
  const o: Any = data?.overall

  return (
    <div>
      <Loading loading={loading} error={error} />
      {o && (
        <>
          <div className="panel">
            <h3>总体一致性估计（n={o.n}，固定种子 2000 次成对自助区间）</h3>
            <div className="rel-grid-3">
              <Metric title="简单一致率" value={pct(o.exactAgreement)} ci={null}
                note="等级完全相同；忽略“差一级”的部分一致性" />
              <Metric title="线性加权一致率" value={num(o.weightedAgreementLinear)} ci={null}
                note="权重 1−|d|/(K−1)" />
              <Metric title="二次加权一致率" value={num(o.weightedAgreementQuadratic)}
                ci={<CiText ci={o.weightedAgreementQuadraticCi95} />} note="权重 1−(|d|/(K−1))²，相邻分歧扣得更少" />
              <Metric title="线性加权 κ" value={num(o.kappaLinear)} ci={null} note="扣除机会一致" />
              <Metric title="二次加权 κ" value={num(o.kappaQuadratic)}
                ci={<CiText ci={o.kappaQuadraticCi95} />} note="跨区排名闸门采用的重复性指标" />
              <Metric title="平均有符号偏差 Δ" value={signed(o.meanSignedDiff)}
                ci={<CiText ci={o.biasCi95} />}
                note={`标准差 ${num(o.sdSignedDiff)}；${o.biasSignificant ? '区间不含 0，存在系统偏差' : '区间含 0，未见显著系统偏差'}`} />
            </div>
            <p className="small muted-text" style={{ marginTop: 8 }}>
              加权一致率对“差一级/差两级”给部分分，数值通常偏高；加权 κ 进一步扣除边际分布造成的机会一致，
              因此更能反映<b>不同检查员与照片条件下能否重复得到同一风险判定</b>。区间由配对自助法得到，重采样不拆散两侧配对。
            </p>
          </div>

          <div className="panel">
            <h3>分通道明细</h3>
            <div className="table-wrap">
              <table>
                <thead><tr>
                  <th>比较通道</th><th>n</th><th>完全一致</th><th>线性加权</th><th>二次加权</th>
                  <th>线性 κ</th><th>二次 κ (95% CI)</th><th>平均偏差 Δ</th><th>方向</th>
                </tr></thead>
                <tbody>
                  {Object.entries(data.byType).map(([t, r]: [string, Any]) => (
                    <tr key={t}>
                      <td><b>{TYPE_LABEL[t] || t}</b></td>
                      <td>{r.n}</td>
                      <td>{r.n ? pct(r.exactAgreement) : '—'}</td>
                      <td>{r.n ? num(r.weightedAgreementLinear) : '—'}</td>
                      <td>{r.n ? num(r.weightedAgreementQuadratic) : '—'}</td>
                      <td>{r.kappaLinear == null ? '—' : num(r.kappaLinear)}</td>
                      <td>{r.kappaQuadratic == null ? '—' : <>{num(r.kappaQuadratic)}<CiText ci={r.kappaQuadraticCi95} /></>}</td>
                      <td>{r.n ? signed(r.meanSignedDiff) : '—'}</td>
                      <td className="small">{dirLabel(r.biasDirection)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="rel-grid-2">
            <div className="panel">
              <h3>照片条件：好条件 vs 恶劣条件（盲审照片）</h3>
              {(['GOOD', 'ADVERSE'] as const).map((q) => {
                const r = data.byPhotoQuality[q]
                return (
                  <div key={q} style={{ marginBottom: 14 }}>
                    <div className="small"><b>{q === 'GOOD' ? '光照均匀/无遮挡' : '逆光/昏暗/远距/重遮挡'}</b>（n={r.n}）</div>
                    {r.n === 0 ? <div className="small muted-text">暂无配对</div> : (
                      <>
                        <Bar ratio={r.exactAgreement} cls={q === 'GOOD' ? 'ok' : 'bad'} label="完全一致率" valueText={pct(r.exactAgreement)} />
                        <Bar ratio={Math.max(0, r.kappaQuadratic ?? 0)} cls={q === 'GOOD' ? 'ok' : 'warn'} label="加权 κ" valueText={num(r.kappaQuadratic)} />
                      </>
                    )}
                  </div>
                )
              })}
              <p className="small muted-text">观察条件编码见“观察与编码”页；恶劣照片条件显著拉低可重复性时，应要求补拍或转现场量测。</p>
            </div>

            <div className="panel">
              <h3>等级差分布（B−A）</h3>
              <DiffHistogram counts={o.signedDiffCounts} kind="signed" />
              <h4 style={{ marginTop: 16 }}>绝对差分布 |d|</h4>
              <DiffHistogram counts={o.absDiffCounts} kind="abs" />
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function Metric({ title, value, ci, note }: { title: string; value: string; ci: React.ReactNode; note: string }) {
  return (
    <div className="gate-crit" style={{ display: 'block' }}>
      <div className="small muted-text">{title}</div>
      <div style={{ fontSize: 24, fontWeight: 700, margin: '2px 0' }}>{value}</div>
      {ci}
      <div className="detail" style={{ marginTop: 4 }}>{note}</div>
    </div>
  )
}

function DiffHistogram({ counts, kind }: { counts: Record<string, number>; kind: 'signed' | 'abs' }) {
  const entries = Object.entries(counts || {})
  const max = Math.max(1, ...entries.map(([, v]) => v))
  return (
    <div>
      {entries.map(([k, v]) => {
        const tone = kind === 'abs'
          ? (k === '0' ? 'ok' : k === '1' ? 'warn' : 'bad')
          : (k === '0' ? 'ok' : Math.abs(Number(k)) >= 2 ? 'bad' : 'warn')
        return (
          <Bar key={k} ratio={v / max} cls={tone}
            label={kind === 'signed' ? `Δ=${Number(k) > 0 ? '+' + k : k}` : `|d|=${k}`}
            valueText={`${v} 对`} />
        )
      })}
    </div>
  )
}

function dirLabel(d: string) {
  return ({
    UPWARD: '偏严/偏高 ↑', DOWNWARD: '偏松/偏低 ↓',
    NO_SIGNIFICANT_BIAS: '无显著偏差', INSUFFICIENT_DATA: '数据不足'
  } as Record<string, string>)[d] || d
}
