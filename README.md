# 消防隐患字段建模与受控查询平台

面向市消防救援支队的**字段治理（Field Governance）**系统：把区县检查表中“同名不同义、单位不一、状态随复查变化”的自定义隐患字段，
通过**稳定字段 ID + 版本化目录 + 逐值证据映射契约 + 受控查询编译 + 权限与披露抑制 + 离线草案合并 + 历史复现**，
纳入可审计的市级风险统计与授权查询。

技术栈：**React 18 + TypeScript + Vite + IndexedDB + WebWorker**（前端） · **Spring Boot 3.5 + Java 25 + Spring JDBC + PostgreSQL 18**（后端）。

---

## 1. 一键启动（Docker Compose）

```bash
docker compose up --build
# 前端：http://localhost:8088
# 后端 API：http://localhost:8080
# PostgreSQL：localhost:5432（库/用户/密码 ccdev）
```

启动时后端自动执行 `schema.sql` 建表与 `data.sql` 播种（幂等，可重复执行）。

## 2. 本地开发启动

前置：容器内已预装 JDK 25、Maven、Node 22、PostgreSQL 18（127.0.0.1:5432，ccdev/ccdev）。

```bash
# 后端（端口 8080，自动建表播种）
cd backend
mvn spring-boot:run

# 前端（端口 5173，/api 已配置代理到 8080）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev          # 开发
npm run build        # 类型检查 + 生产构建（产物在 dist/）
```

后端单元测试与端到端验证：

```bash
cd backend
mvn test                              # JUnit：披露抑制、厘米→毫米、一致性/Kappa/闸门/分层模型
python3 e2e/run-e2e.py                # 需后端已启动；8 大场景 86 项断言的完整 E2E
```

## 3. 测试账号

| 用户名 | 密码 | 角色/辖区/密级 | 授权目的 |
| --- | --- | --- | --- |
| `city_admin` | `city123` | 市级管理员，全市，RESTRICTED | STAT / CASE_INVEST / AUDIT / GOVERN |
| `jia_analyst` | `jia123` | 甲区分析员，JIA，INTERNAL（含 BING 跨区 STAT 授权，用于验证敏感级拦截） | STAT |
| `yi_analyst` | `yi123` | 乙区分析员，YI，INTERNAL（含 JIA 案件 J-2026-003 逐案授权） | STAT / CASE_INVEST |
| `auditor` | `audit123` | 审计员，全市，RESTRICTED | 仅 AUDIT（发起 STAT 查询会被拒绝） |

## 4. 预置数据与题目场景

- 甲区 `J-2026-001`：裂缝 **12mm**、`jia.risk_level=2`（甲区定义=需限期整改，**非重大**）、检查后仍未整改；
- 乙区 `Y-2026-001`：裂缝 **1.2cm**、新版表单 `yi.risk_level=2`，表单填报说明其含义为**“需观察”**（同名不同义，禁止按旧义计为重大）；
- 甲区 `J-2026-002`：立案为重大隐患，**复查后降级**为限期整改（事件流 `REVIEW_DOWNGRADE`）；
- 丙区 3 个案件的**敏感辖区**（RESTRICTED 字段）；
- 乙区旧版表单 `Y-2026-002`（目录 v1，旧义代码 2=重大）与新版代码 `4`（临时管控，**无市级等价语义，编译期拒绝**）；
- 待批准的辖区扩展字段 `yi.ev_charge_hazard`（电动车充电隐患）与市级立项申请。

## 5. 核心验证方式（已实际执行）

在“受控查询”页点击**载入题目标准查询**（或用 `city_admin` 调 `POST /api/query/compile`）：

> 各区当前未整改重大隐患中裂缝宽度 ≥ 10mm 的案件数（asOf=2026-05-20）

系统行为与标准结果：

1. **先按各自定义映射**：乙区新版代码 2 → `OBSERVE`（不计重大）；甲区代码 2 → `LIMITED`（不计重大）；旧版表单代码 2 经 SUPERSEDED 的 V1 契约 → `MAJOR`；
2. **再换算单位**：乙区 `1.2cm` 经契约 `x*10` → `12mm`，逐值证据可在“表单与证据链”页查看；
3. **应用事件状态**：`J-2026-002` 复查降级后不进重大项；`J-2026-008` 已整改不进未整改项；
4. **权限过滤**：甲区分析员只见 JIA；审计员无 STAT 目的被拒；INTERNAL 密级拿不到 BING 敏感组；
5. **披露抑制（阈值 4）**：JIA=4 发布；YI=3、BING=3 为小组抑制（不返回原始计数）；可发布**总数 9**；互补抑制保证无法用“总数−其他组”反推被抑制格；
6. **不等价拒绝**：乙区代码 4 与待批准扩展字段进入 `compileWarnings`，不强行合并；`POST /api/forms/map-check` 返回 422；
7. **历史复现**：种子查询按快照（目录 v2 + asOf + 契约版本清单）重放，与存档逐组一致；旧表单始终按提交时 v1 语义解释；
8. **离线合并**：基线一致的草案 MERGED；含 `std.smoke_density`（v3 语义与敏感级均变化）的草案转 PENDING_CONFLICT，可退回重填或管理员复核强合；
9. **新字段治理**：批准前查询 `std.ev_charge_hazard` 被拒；批准（可指定追溯生效日）后，旧表单 FI-Y-005 **不改写**，编译期回溯映射为 PRESENT。

E2E 断言结果（最近一次在**全新重建的 PostgreSQL 库**上实际执行）：**86 项全部 PASS，0 FAIL**
（`backend/e2e/run-e2e.py`，8 大场景：标准查询 11、访问策略 4、不等价拒绝 2、历史复现 4、离线合并 6、
扩展治理 7、演进一致性 3、**锂电隔离一致性 49**）。后端 `mvn test`：**11 个 JUnit 用例全部通过**
（原有 5 + 新增 `AgreementStatsTest` 6）；前端 `npm run build`（`tsc -b` 严格模式 + Vite）通过，
`vite dev` 下所有新模块均可正常转译、`/api` 代理与登录拦截正常。

> E2E 场景 1–7 中的“治理批准”按设计不可重复执行；重跑整套 E2E 前请重建库（见下文“复现方式”）。

### 5.1 场景 8：锂电隔离等级测量一致性（本题新增，已实际验证）

进入前端左侧 **“隔离等级一致性”** 页，或用 `city_admin` 调 `/api/reliability/*`。种子数据与标准结果：

- **复检配对账**：`std.li_iso_level` 有 12 个永久场所 ×（初查/复查/盲审照片/量测）= 48 条观察、36 对配对
  （同场所复查 / 盲审照片 / 现场量测各 12 对）；`std.li_photo_grade` 另有 18 对盲审快评。每对都计算有符号等级差 B−A。
- **观察条件编码**：光照 / 距离 / 遮挡 / 角度 / 清晰度 + 综合 `quality=GOOD|ADVERSE`，短码如 `PH-ADV-OCCLUDED`；
  原始取值保留 `STD_L / DISTRICT_LETTER / RISK_SCORE_100 / GAP_M / QUICK_PHOTO_4` 等混杂口径，统计前标准化到 1..4。
- **一致性估计**：标准字段总体二次加权一致率 **0.963**、加权 κ **0.837**、κ 95% 自助区间 **[0.706, 0.918]**、
  平均偏差 **0.00**；三通道 κ 分别约为 0.79 / 0.81 / 0.92。好照片条件完全一致率 **7/9≈0.78**，
  而 3 对恶劣照片（逆光/昏暗远距/重遮挡）**完全一致率 0**。
- **闸门与跨区排名**：阈值（配对≥12、加权一致率≥0.85、κ≥0.60、κ 区间下界≥0.40、|偏差|≤0.5）全部满足 →
  `GET /api/reliability/ranking?fieldId=std.li_iso_level` 返回排名 **BING(3.50) > YI(2.63) > JIA(2.00)**。
- **一致性不足即禁止排名**：`std.li_photo_grade`（照片快评）虽已登记入目录，但 κ≈**0.128**、区间跨 0、加权一致率 0.79，
  闸门 `NOT_ELIGIBLE`；同一 ranking 接口对它返回 **HTTP 409** 且不产出任何名次。
- **分层测量模型** `y=μ+场所+检查员+ε`：标准字段可估计，方差份额约为 **场所 81.8% / 检查员 3.8% / 残差 14.5%**；
  照片快评因无场所/同场所复检，返回 `estimable=false` 与具体补足缺口（体现“泛化好但需足够复检”）。
- **锚样校准器**：12 个固定锚样 = **8 永久设施 + 4 临时施工**，严格分两类统计；检查员韩涛（INS-HAN）
  对全部锚样系统性低判一级，平均偏差 **−0.75** 被标记。覆盖分析显示 6 个新兴场景（户外预制舱、热失控后复勘、
  梯次利用等）未覆盖、**新兴场景覆盖率 0**，并给出“固定锚样解释强但新场景覆盖有限”的说明。
  `POST /anchors/calibration/evaluate {"poolAcrossClasses":true}` 或同时传两类 → **422**
  （临时施工照片不得与永久设施合并为同类锚样）。
- **培训反馈台**：`POST /feedback/generate` 依据锚样偏差与配对分歧生成 INS-HAN / INS-ZHAO 等条目，
  可 `OPEN→ACKED→DONE`；审计员调用生成接口被拒（权限不足）。

**干净复现（推荐）**：

```bash
# 1) 重建数据库（容器自带 PostgreSQL 18，ccdev/ccdev）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h127.0.0.1 -Uccdev -dccdev \
  -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
# 2) 启动后端（自动建表并依次播种 data.sql + reliability-data.sql）
cd backend && mvn spring-boot:run
# 3) 另一个终端：完整 E2E
python3 backend/e2e/run-e2e.py        # 期望 86 PASS / 0 FAIL
```

> 说明：本隔离容器未安装 Docker 守护进程，镜像未在本地构建；已提供多阶段 `Dockerfile` 与 `docker-compose.yml`（YAML 结构已校验），在装有 Docker 的环境执行 `docker compose up --build` 即可。上述单测、前端构建与 37 项 E2E 均在容器预装的 JDK 25 / Node 22 / PostgreSQL 18 上实际执行通过。

## 6. 界面清单

| 页面 | 能力 |
| --- | --- |
| 受控查询 | 标准化表达（仅 std.*）、目的/时点/版本选择、披露结果、逐案编译诊断（证据链/换算/事件重放/剔除原因）、版本快照 |
| 字段目录 | 按版本 + asOf 解释字段；显示值域/单位/粒度/事件时间含义/辖区/敏感级/有效区间/弃用替代；字段版本史 |
| 映射契约 | 契约状态与生效区间、逐值证据、换算公式、equivalent=false 的拒绝项 |
| 表单与证据链 | 不可变实例、提交时目录版本、逐字段映射轨迹、案件事件流、map-check 显式拒绝演示 |
| 隔离等级一致性 | 总览/闸门/跨区排名、加权一致率·κ·自助区间·等级差分布、复检配对账（可新增）、观察条件编码、锚样校准器（永久/临施分类、检查员偏差、覆盖）、分层场所/检查员效应、培训反馈台 |
| 离线草案 | IndexedDB 本地草案、WebWorker 同步、语义/敏感级冲突待决、退回重填/管理员强合 |
| 历史复现 | 查询历史、按快照重放、存档 vs 复现逐组比对 |
| 新字段治理 | 两条路线（市级标准 / 命名空间扩展）申请、批准（可追溯）/驳回、批准前拒绝→批准后回溯闭环 |

## 7. 关键设计约束的落地位置

- 稳定字段 ID 与版本并存：`field_def(field_id, version)`，解释规则见 `CatalogService.defsAsOf`；
- 提交时固定目录版本：`form_instance.catalog_version`，契约选择受 `contract.version ≤ catalog_version` 约束（`MappingEngine`）；
- 同名不自动合并：仅允许经 `mapping_contract + mapping_value` 逐值证据映射，`equivalent=false` 一律拒绝；
- 最小发布组与互补抑制：`SuppressionService`（阈值 `app.suppression.threshold=4`）；
- 四重访问策略（辖区/案件/敏感级/目的）：`AccessPolicyService`；
- 离线冲突检测：前端 `sync.worker.ts` 预检 + 后端 `OfflineService` 独立复核；
- 历史复现：`query_history.version_snapshot` + `HistoryService.replay` 固定版本重编译；
- 回溯不改写旧表单：治理批准只发布新目录版本/契约生效日，表单实例行永不更新。
- 复检配对账与等级差：`iso_pair(obs_a,obs_b,pair_type)` 显式配对，差值得自两侧 `iso_observation.level_value`；
- 加权一致率 / 加权 κ / 系统偏差 / 固定种子自助区间：`reliability.AgreementStats`（纯函数 + JUnit）；
- 场所 vs 检查员效应：`reliability.HierarchicalModel` 中心化反投影加法模型 + 复检充分性门槛，不足即 `estimable=false`；
- 锚样分类铁律：`iso_anchor_sample.anchor_class ∈ {PERMANENT,TEMP_CONSTRUCTION}`，跨类合并在 `ReliabilityService.evaluateAnchorClass` 直接 422；
- 一致性闸门：`ReliabilityGate` 出结论，`GET /api/reliability/ranking` 在不达标时抛 `ReliabilityGateException` → HTTP 409 且不返回名次（字段仍在 `field_def` 可登记）。

## 8. 目录结构

```
.
├── backend/                Spring Boot 工程
│   ├── src/main/java/com/fire/fieldgov/
│   │   ├── catalog/        字段目录与契约读取（版本解释）
│   │   ├── mapping/        映射引擎（证据/换算/生效范围）、事件流重放
│   │   ├── query/          查询编译器、历史复现
│   │   ├── policy/         访问策略、披露抑制
│   │   ├── offline/        离线草案合并与待决
│   │   ├── governance/     扩展字段两条路线治理
│   │   ├── reliability/    锂电隔离一致性：AgreementStats/Kappa/自助区间、HierarchicalModel、ReliabilityGate、ReliabilityService
│   │   ├── form/ web/ security/ db/
│   ├── src/main/resources/ schema.sql / data.sql / reliability-data.sql / application.yml
│   └── e2e/run-e2e.py      端到端验证脚本（8 场景 86 断言）
├── frontend/               React + TS 工程
│   └── src/{pages/reliability/*, pages/ReliabilityPage.tsx, workers,db.ts,api.ts}
└── docker-compose.yml      一键启动（postgres + backend + frontend/nginx）
```
