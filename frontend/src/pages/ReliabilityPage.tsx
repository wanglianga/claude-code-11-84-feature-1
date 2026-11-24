import { useState } from 'react'
import OverviewTab from './reliability/OverviewTab'
import ConsistencyTab from './reliability/ConsistencyTab'
import PairsTab from './reliability/PairsTab'
import ObservationsTab from './reliability/ObservationsTab'
import AnchorsTab from './reliability/AnchorsTab'
import HierarchicalTab from './reliability/HierarchicalTab'
import FeedbackTab from './reliability/FeedbackTab'

const TABS = [
  { key: 'overview', label: '总览 · 闸门 · 跨区排名' },
  { key: 'consistency', label: '等级一致性估计' },
  { key: 'pairs', label: '复检配对账' },
  { key: 'observations', label: '观察条件编码' },
  { key: 'anchors', label: '锚样校准器' },
  { key: 'hierarchical', label: '分层测量模型' },
  { key: 'feedback', label: '培训反馈台' }
] as const

export default function ReliabilityPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]['key']>('overview')
  const [field, setField] = useState('std.li_iso_level')
  const fieldScoped = tab === 'overview' || tab === 'consistency' || tab === 'hierarchical'

  return (
    <div>
      <h2 className="page-title">锂电隔离等级 · 测量一致性工作台</h2>
      <p className="page-desc">
        隔离等级取值混杂，更关键的是判断<b>不同检查员、不同照片条件下能否重复得到同一风险判定</b>。
        这里建立复检配对账与观察条件编码，比较<b>同场所复查 / 盲审照片 / 现场量测</b>的等级差，
        计算加权一致率、系统性偏差与区间；用<b>固定锚样</b>快速校准（解释强但新场景覆盖有限），
        或用<b>分层模型</b>分离场所与检查员效应（泛化好但需足够复检）。
        临时施工照片不得与永久设施作为同类锚样；<b>一致性不足的字段可登记，但不得驱动跨区排名</b>。
      </p>

      <div className="tabs">
        {TABS.map((t) => (
          <button key={t.key} className={tab === t.key ? 'active' : ''} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>

      {fieldScoped && (
        <div className="field-switch">
          <button className={field === 'std.li_iso_level' ? 'active' : ''} onClick={() => setField('std.li_iso_level')}>
            <b>std.li_iso_level · 标准四级</b>
            <span>36 对三通道复检，κ≈0.84，闸门通过，可排名</span>
          </button>
          <button className={field === 'std.li_photo_grade' ? 'active' : ''} onClick={() => setField('std.li_photo_grade')}>
            <b>std.li_photo_grade · 照片快评</b>
            <span>18 对盲审，κ≈0.13，一致性不足，登记但禁止跨区排名</span>
          </button>
        </div>
      )}

      {tab === 'overview' && <OverviewTab field={field} />}
      {tab === 'consistency' && <ConsistencyTab field={field} />}
      {tab === 'pairs' && <PairsTab />}
      {tab === 'observations' && <ObservationsTab />}
      {tab === 'anchors' && <AnchorsTab />}
      {tab === 'hierarchical' && <HierarchicalTab field={field} />}
      {tab === 'feedback' && <FeedbackTab />}
    </div>
  )
}
