import { Chip, Loading, num, useFetch, type Any } from './ui'

export default function HierarchicalTab({ field }: { field: string }) {
  const { data: h, loading, error } = useFetch<Any>(`/reliability/hierarchical?fieldId=${encodeURIComponent(field)}`)

  return (
    <div>
      <div className="panel">
        <h3>分层测量模型：分离场所效应与检查员效应</h3>
        <p className="small muted-text" style={{ maxWidth: 920 }}>
          模型 <code>等级 = 总均值 μ + 场所效应 + 检查员效应 + 残差</code>，用交叉加法模型的中心化反投影拟合，
          再做方差分量分解。场所效应代表场所真实隔离条件差异（泛化到新场所的信号）；检查员效应是可经校准/培训收敛的宽严倾向；
          残差包含场所×检查员交互、照片条件与随机噪声。该模型泛化好，但<b>需要足够的同场所复检与交叉评分</b>。
        </p>
        <Loading loading={loading} error={error} />

        {h && (
          <>
            <div className="rel-grid-3" style={{ marginBottom: 14 }}>
              <div className="kpi"><div className="num">{h.n}</div><div className="lbl">有效观测</div></div>
              <div className="kpi"><div className="num">{h.siteCount}</div><div className="lbl">场所数</div></div>
              <div className="kpi"><div className="num">{h.inspectorCount}</div><div className="lbl">评分主体数（含量测基准）</div></div>
              <div className="kpi"><div className="num">{h.revisitPairCount}</div><div className="lbl">同场所复检配对</div></div>
              <div className="kpi"><div className="num">{num(h.grandMean, 2)}</div><div className="lbl">总平均等级 μ</div></div>
            </div>

            <div className="panel" style={{ background: '#f7f9fb' }}>
              <h4 style={{ marginTop: 0 }}>估计充分性检查</h4>
              {h.checks.map((ck: Any) => (
                <div key={ck.key} className={`gate-crit ${ck.pass ? 'pass' : 'fail'}`} style={{ background: '#fff' }}>
                  <span className="mark">{ck.pass ? '✓' : '✕'}</span>
                  <div><b>{ck.label}</b><div className="detail">要求 ≥ {ck.required}，实际 {ck.actual}</div></div>
                </div>
              ))}
            </div>

            {!h.estimable ? (
              <div className="notice warn">
                <b>样本不足，分层模型不输出效应分解（避免给出不稳定结论）。</b>
                <ul style={{ margin: '6px 0 0 18px' }}>{h.missingRequirements.map((m: string, i: number) => <li key={i}>{m}</li>)}</ul>
                <div style={{ marginTop: 6 }}>{h.interpretation}</div>
                <div className="small" style={{ marginTop: 6 }}>可改用“锚样校准器”的固定锚样评分快速识别检查员偏差——解释强，但对新场景覆盖有限。</div>
              </div>
            ) : (
              <>
                <div className="panel">
                  <h4 style={{ marginTop: 0 }}>方差分量占比</h4>
                  <div className="stack-bar">
                    <div style={{ width: `${h.shareSite * 100}%`, background: '#2b6cb0' }}>场所 {(h.shareSite * 100).toFixed(0)}%</div>
                    <div style={{ width: `${h.shareInspector * 100}%`, background: '#b7791f' }}>检查员 {(h.shareInspector * 100).toFixed(0)}%</div>
                    <div style={{ width: `${h.shareResidual * 100}%`, background: '#94a3b8' }}>残差 {(h.shareResidual * 100).toFixed(0)}%</div>
                  </div>
                  <div className="small muted-text">
                    方差：场所 {num(h.varianceSite)} · 检查员 {num(h.varianceInspector)} · 残差 {num(h.varianceResidual)}
                  </div>
                  <div className={`notice ${h.shareSite >= 0.6 ? 'ok' : 'warn'}`} style={{ marginTop: 10, marginBottom: 0 }}>{h.interpretation}</div>
                </div>

                <div className="rel-grid-2">
                  <EffectList title="场所效应（相对总均值，高=条件更严峻）" effects={h.siteEffects} tone="site" />
                  <EffectList title="检查员效应（负=偏松，排在最前）" effects={h.inspectorEffects} tone="insp" />
                </div>
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}

function EffectList({ title, effects, tone }: { title: string; effects: Any[]; tone: 'site' | 'insp' }) {
  const maxAbs = Math.max(0.25, ...effects.map((e) => Math.abs(e.effect)))
  return (
    <div className="panel">
      <h4 style={{ marginTop: 0 }}>{title}</h4>
      {effects.map((e) => {
        const width = (Math.abs(e.effect) / maxAbs) * 50
        const neg = e.effect < 0
        const style: React.CSSProperties = neg ? { right: '50%', width: `${width}%` } : { left: '50%', width: `${width}%` }
        return (
          <div key={e.code} style={{ display: 'grid', gridTemplateColumns: '120px 1fr 52px', gap: 8, alignItems: 'center', margin: '7px 0' }}>
            <div className="small" title={e.code}>
              {tone === 'insp' ? <Chip tone={neg ? 'warn' : 'info'}>{e.code}</Chip> : <span className="mono">{e.code}</span>}
              <span className="muted-text"> n={e.n}</span>
            </div>
            <div className="diverge"><div className="zero" />
              {Math.abs(e.effect) > 0.004 && <div className={`ef ${neg ? 'neg' : 'pos'}`} style={style} />}
            </div>
            <span className="mono small" style={{ textAlign: 'right' }}>{e.effect > 0 ? '+' : ''}{num(e.effect, 2)}</span>
          </div>
        )
      })}
    </div>
  )
}
