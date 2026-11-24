-- 消防隐患字段治理：PostgreSQL schema（幂等，可重复执行）
-- 命名约定：std.* 市级标准字段；<辖区>.* 辖区命名空间扩展字段

CREATE TABLE IF NOT EXISTS app_user (
    username        VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    password        VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL,            -- CITY_ADMIN / DISTRICT_ANALYST / AUDITOR
    home_district   VARCHAR(16),                      -- NULL 表示全市
    clearance       VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL', -- INTERNAL / RESTRICTED
    allowed_purposes TEXT[]      NOT NULL DEFAULT '{}',       -- STAT / CASE_INVEST / AUDIT / GOVERN
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_case_grant (
    username   VARCHAR(64) NOT NULL REFERENCES app_user(username),
    district   VARCHAR(16) NOT NULL,
    case_id    VARCHAR(64) NOT NULL DEFAULT '*',    -- '*' 表示辖区内全部案件
    purpose    VARCHAR(32) NOT NULL,
    PRIMARY KEY (username, district, case_id, purpose)
);

-- 字段目录版本（每次发布一行；当前版本取最大 version）
CREATE TABLE IF NOT EXISTS field_catalog_version (
    version       INT PRIMARY KEY,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          TEXT
);

-- 字段定义：同一 field_id 随目录版本多行；查询按“≤ 请求版本的最新一行”解释
CREATE TABLE IF NOT EXISTS field_def (
    field_id          VARCHAR(64) NOT NULL,
    version           INT NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    definition        TEXT NOT NULL,
    value_domain      JSONB NOT NULL,               -- {"type":"enum|numeric","values":[]} / {"min":,"max":}
    unit              VARCHAR(32),
    granularity       VARCHAR(64),                  -- 案件级 / 单次测量 / 案件-复查级
    event_time_meaning VARCHAR(128),                -- 该字段取值对应的业务事件时间含义
    applicable_districts TEXT[] NOT NULL DEFAULT '{}', -- 空数组表示全市适用
    sensitivity       VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / DEPRECATED / DRAFT
    namespace         VARCHAR(16) NOT NULL DEFAULT 'CITY',   -- CITY / JIA / YI / BING
    superseded_by     VARCHAR(64),                  -- 弃用替代字段
    PRIMARY KEY (field_id, version)
);

-- 映射契约：源代码字段 -> 标准字段，带生效区间与审查状态
CREATE TABLE IF NOT EXISTS mapping_contract (
    contract_id      VARCHAR(64) PRIMARY KEY,
    source_field_id  VARCHAR(64) NOT NULL,
    target_field_id  VARCHAR(64) NOT NULL,
    source_district  VARCHAR(16) NOT NULL,
    version          INT NOT NULL,
    status           VARCHAR(16) NOT NULL,          -- APPROVED / DRAFT / SUPERSEDED / REJECTED
    effective_from   DATE NOT NULL,
    effective_to     DATE,
    evidence_note    TEXT NOT NULL,
    reviewed_by      VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 逐值映射：每个源代码取值一行，禁止同名自动合并
CREATE TABLE IF NOT EXISTS mapping_value (
    contract_id   VARCHAR(64) NOT NULL REFERENCES mapping_contract(contract_id),
    source_value  VARCHAR(64) NOT NULL,
    target_value  VARCHAR(64),
    transform     VARCHAR(128),                     -- 数值换算公式，如 x*10、x*1
    evidence      TEXT NOT NULL,                    -- 该取值映射的证据出处
    equivalent    BOOLEAN NOT NULL DEFAULT TRUE,    -- FALSE = 无法证明语义等价，编译时拒绝
    PRIMARY KEY (contract_id, source_value)
);

-- 表单模板：提交时固定目录版本
CREATE TABLE IF NOT EXISTS form_template (
    template_id     VARCHAR(64) PRIMARY KEY,
    district        VARCHAR(16) NOT NULL,
    catalog_version INT NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS form_template_field (
    template_id VARCHAR(64) NOT NULL REFERENCES form_template(template_id),
    field_id    VARCHAR(64) NOT NULL,
    ordinal     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (template_id, field_id)
);

-- 表单实例（不可变；提交后内容哈希固定，回溯映射不改写本行）
CREATE TABLE IF NOT EXISTS form_instance (
    instance_id     VARCHAR(64) PRIMARY KEY,
    case_id         VARCHAR(64) NOT NULL,
    template_id     VARCHAR(64) NOT NULL,
    district        VARCHAR(16) NOT NULL,
    catalog_version INT NOT NULL,                   -- 提交时固定的目录版本
    inspect_date    DATE NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitter       VARCHAR(64) NOT NULL,
    content_hash    VARCHAR(128) NOT NULL,
    origin          VARCHAR(16) NOT NULL DEFAULT 'ONLINE' -- ONLINE / OFFLINE_MERGE
);
CREATE INDEX IF NOT EXISTS idx_form_case ON form_instance(case_id);

CREATE TABLE IF NOT EXISTS form_value (
    instance_id VARCHAR(64) NOT NULL REFERENCES form_instance(instance_id),
    field_id    VARCHAR(64) NOT NULL,
    value_text  VARCHAR(256) NOT NULL,
    PRIMARY KEY (instance_id, field_id)
);

-- 案件事件流：隐患等级/整改状态按事件时间重放
CREATE TABLE IF NOT EXISTS case_event (
    event_id    BIGSERIAL PRIMARY KEY,
    case_id     VARCHAR(64) NOT NULL,
    district    VARCHAR(16) NOT NULL,
    event_type  VARCHAR(32) NOT NULL,               -- INSPECTION / REVIEW_DOWNGRADE / REVIEW_RESCIND / RECTIFY
    event_time  TIMESTAMPTZ NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_event_case_time ON case_event(case_id, event_time);

-- 离线草案合并结果
CREATE TABLE IF NOT EXISTS offline_draft (
    draft_id              VARCHAR(64) PRIMARY KEY,
    username              VARCHAR(64) NOT NULL,
    client_id             VARCHAR(128) NOT NULL,
    base_catalog_version  INT NOT NULL,
    payload               JSONB NOT NULL,
    status                VARCHAR(24) NOT NULL,     -- MERGED / PENDING_CONFLICT / REBASED
    conflicts             JSONB NOT NULL DEFAULT '[]',
    merged_instance_id    VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (username, client_id)
);

-- 受控查询历史（含版本快照，供历史复现）
CREATE TABLE IF NOT EXISTS query_history (
    query_id         BIGSERIAL PRIMARY KEY,
    username         VARCHAR(64) NOT NULL,
    purpose          VARCHAR(32) NOT NULL,
    query_json       JSONB NOT NULL,
    result_json      JSONB NOT NULL,
    version_snapshot JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64) NOT NULL,
    action     VARCHAR(64) NOT NULL,
    detail     JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 辖区扩展字段治理请求（两条路线：市级标准 / 命名空间扩展）
CREATE TABLE IF NOT EXISTS extension_request (
    request_id        VARCHAR(64) PRIMARY KEY,
    route             VARCHAR(24) NOT NULL,         -- CITY_STANDARD / NAMESPACE_EXT
    district          VARCHAR(16),
    proposed_field_id VARCHAR(64) NOT NULL,
    field_def_json    JSONB NOT NULL,
    contract_json     JSONB,
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT / APPROVED / REJECTED
    evidence          TEXT,
    catalog_version_introduced INT,
    reviewed_by       VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at       TIMESTAMPTZ
);

-- ======================================================================
-- 锂电隔离等级 · 测量一致性 / 复检配对 / 锚样校准 / 培训反馈
-- 等级统一标准化为 1..4 序次（越高越严重）；原始取值另存 raw_* 以体现“取值混杂”。
-- ======================================================================

-- 检查员（测量主体；含仪器基准账号与一名系统性偏松检查员用于校准/培训演示）
CREATE TABLE IF NOT EXISTS iso_inspector (
    inspector_code VARCHAR(32) PRIMARY KEY,
    display_name   VARCHAR(64) NOT NULL,
    district       VARCHAR(16) NOT NULL,
    title          VARCHAR(32),
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

-- 场所：同场所复查的对账主体；subject_class 区分永久设施与临时施工场景
CREATE TABLE IF NOT EXISTS iso_site (
    site_code     VARCHAR(32) PRIMARY KEY,
    district      VARCHAR(16) NOT NULL,
    site_name     VARCHAR(128) NOT NULL,
    subject_class VARCHAR(20) NOT NULL,   -- PERMANENT 永久设施 / TEMP_CONSTRUCTION 临时施工
    latent_level  INT,                    -- 内部基准等级（播种/排名口径，不向检查员暴露）
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 观察/评定记录：一条 = 某检查员或量测方式在某观察条件下给出的一次等级
CREATE TABLE IF NOT EXISTS iso_observation (
    obs_id               VARCHAR(40) PRIMARY KEY,
    field_id             VARCHAR(64) NOT NULL,
    site_code            VARCHAR(32) REFERENCES iso_site(site_code),
    inspector_code       VARCHAR(32) REFERENCES iso_inspector(inspector_code),
    mode                 VARCHAR(20) NOT NULL,  -- ONSITE / REVISIT / PHOTO_BLIND / MEASURE
    level_value          INT NOT NULL,         -- 标准化序次 1..4（越高越严重）
    raw_value            VARCHAR(64),          -- 混杂的原始取值
    raw_system           VARCHAR(48),          -- 原始编码体系
    subject_class        VARCHAR(20) NOT NULL DEFAULT 'PERMANENT',
    photo_condition_code VARCHAR(48),          -- 观察条件编码（结构化 conditions_json 的短码）
    conditions_json      JSONB NOT NULL DEFAULT '{}', -- 光照/距离/遮挡/角度/清晰度等
    metric_value         NUMERIC(10,2),        -- 现场量测原始数值（如实测隔离间距 m）
    observed_at          DATE NOT NULL,
    note                 TEXT
);
CREATE INDEX IF NOT EXISTS idx_iso_obs_field ON iso_observation(field_id);
CREATE INDEX IF NOT EXISTS idx_iso_obs_site ON iso_observation(site_code);
CREATE INDEX IF NOT EXISTS idx_iso_obs_inspector ON iso_observation(inspector_code);

-- 复检/比对配对账：显式把两条观察配成一对，pair_type 对应三类等级差比较
CREATE TABLE IF NOT EXISTS iso_pair (
    pair_id     VARCHAR(48) PRIMARY KEY,
    field_id    VARCHAR(64) NOT NULL,
    pair_type   VARCHAR(24) NOT NULL,  -- SAME_SITE_REVISIT / BLIND_PHOTO / ONSITE_VS_MEASURE
    site_code   VARCHAR(32),
    obs_a       VARCHAR(40) NOT NULL REFERENCES iso_observation(obs_id),
    obs_b       VARCHAR(40) NOT NULL REFERENCES iso_observation(obs_id),
    orientation TEXT,                  -- 有符号偏差 B-A 的业务方向说明
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_iso_pair_field_type ON iso_pair(field_id, pair_type);

-- 固定锚样：带权威参考等级；anchor_class 强制区分永久设施 vs 临时施工，禁止并为同类
CREATE TABLE IF NOT EXISTS iso_anchor_sample (
    anchor_id       VARCHAR(40) PRIMARY KEY,
    anchor_class    VARCHAR(20) NOT NULL,  -- PERMANENT / TEMP_CONSTRUCTION（两类不得合并锚定）
    scenario_code   VARCHAR(48) NOT NULL,
    scenario_name   VARCHAR(128) NOT NULL,
    reference_level INT NOT NULL,
    condition_code  VARCHAR(48),
    coverage_tag    VARCHAR(64),
    source_desc     TEXT NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

-- 锚样评分：检查员对固定锚样给出的等级
CREATE TABLE IF NOT EXISTS iso_anchor_score (
    anchor_id      VARCHAR(40) NOT NULL REFERENCES iso_anchor_sample(anchor_id),
    inspector_code VARCHAR(32) NOT NULL REFERENCES iso_inspector(inspector_code),
    given_level    INT NOT NULL,
    scored_at      DATE NOT NULL,
    PRIMARY KEY (anchor_id, inspector_code)
);

-- 培训反馈台：可由一致性/校准结果生成并在 OPEN -> ACKED -> DONE 间流转
CREATE TABLE IF NOT EXISTS iso_feedback (
    feedback_id    VARCHAR(48) PRIMARY KEY,
    inspector_code VARCHAR(32) NOT NULL REFERENCES iso_inspector(inspector_code),
    district       VARCHAR(16) NOT NULL,
    topic          VARCHAR(160) NOT NULL,
    severity       VARCHAR(12) NOT NULL,   -- HIGH / MEDIUM / LOW
    basis_json     JSONB NOT NULL DEFAULT '{}',
    suggestion     TEXT NOT NULL,
    status         VARCHAR(12) NOT NULL DEFAULT 'OPEN', -- OPEN / ACKED / DONE
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
