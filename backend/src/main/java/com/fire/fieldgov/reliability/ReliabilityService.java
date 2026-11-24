package com.fire.fieldgov.reliability;

import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.web.GovernanceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锂电隔离等级测量一致性领域服务：
 * 复检配对账、观察条件编码、一致性/偏差/区间、闸门与跨区排名、
 * 分层模型、锚样校准（永久 vs 临时施工分类）、培训反馈台。
 */
@Service
public class ReliabilityService {

    public static final String FIELD_ISO = "std.li_iso_level";
    public static final String FIELD_PHOTO = "std.li_photo_grade";

    private final JdbcTemplate jdbc;
    private final ReliabilityGate.Thresholds gateThresholds;
    private final HierarchicalModel.MinRequirements hierarchicalReq;

    public ReliabilityService(
            JdbcTemplate jdbc,
            @Value("${app.reliability.gate.min-pairs:12}") int minPairs,
            @Value("${app.reliability.gate.min-weighted-agreement:0.85}") double minWa,
            @Value("${app.reliability.gate.min-kappa:0.60}") double minKappa,
            @Value("${app.reliability.gate.min-kappa-ci-lower:0.40}") double minCiLower,
            @Value("${app.reliability.gate.max-abs-bias:0.50}") double maxAbsBias,
            @Value("${app.reliability.hierarchical.min-observations:16}") int minObs,
            @Value("${app.reliability.hierarchical.min-sites:5}") int minSites,
            @Value("${app.reliability.hierarchical.min-inspectors:3}") int minInsp,
            @Value("${app.reliability.hierarchical.min-revisit-pairs:6}") int minRevisit,
            @Value("${app.reliability.hierarchical.min-inspector-site-coverage:2}") int minCoverage) {
        this.jdbc = jdbc;
        this.gateThresholds = new ReliabilityGate.Thresholds(minPairs, minWa, minKappa, minCiLower, maxAbsBias);
        this.hierarchicalReq = new HierarchicalModel.MinRequirements(
                minObs, minSites, minInsp, minRevisit, minCoverage);
    }

    // ============================ 元数据 / 编码字典 ============================

    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("levels", List.of(
                Map.of("value", 1, "label", "常规隔离"),
                Map.of("value", 2, "label", "加强关注"),
                Map.of("value", 3, "label", "限期整改"),
                Map.of("value", 4, "label", "重大管控")));
        m.put("pairTypes", List.of(
                Map.of("code", "SAME_SITE_REVISIT", "label", "同场所复查", "compare", "初查 vs 复查"),
                Map.of("code", "BLIND_PHOTO", "label", "盲审照片", "compare", "现场/另一检查员 vs 盲审照片"),
                Map.of("code", "ONSITE_VS_MEASURE", "label", "现场量测", "compare", "现场判定 vs 量测反演")));
        m.put("modes", List.of(
                Map.of("code", "ONSITE", "label", "现场检查"),
                Map.of("code", "REVISIT", "label", "同场所复查"),
                Map.of("code", "PHOTO_BLIND", "label", "盲审照片"),
                Map.of("code", "MEASURE", "label", "仪器量测反演")));
        m.put("conditionDimensions", List.of(
                Map.of("key", "lighting", "label", "光照", "codes", List.of("EVEN", "DIM", "BACKLIGHT")),
                Map.of("key", "distance", "label", "拍摄距离", "codes", List.of("NEAR", "MID", "FAR")),
                Map.of("key", "occlusion", "label", "遮挡", "codes", List.of("NONE", "PARTIAL", "HEAVY")),
                Map.of("key", "angle", "label", "角度", "codes", List.of("FRONT", "SIDE", "OBLIQUE", "TOP")),
                Map.of("key", "clarity", "label", "清晰度", "codes", List.of("HIGH", "MID", "LOW")),
                Map.of("key", "quality", "label", "综合照片条件", "codes", List.of("GOOD", "ADVERSE"))));
        m.put("anchorClasses", List.of(
                Map.of("code", "PERMANENT", "label", "永久设施锚样"),
                Map.of("code", "TEMP_CONSTRUCTION", "label", "临时施工锚样（不得并入永久设施）")));
        m.put("fields", List.of(
                Map.of("fieldId", FIELD_ISO, "label", "锂电隔离等级（标准四级）",
                        "description", "现场+复查+盲审+量测，配对充分、一致性高"),
                Map.of("fieldId", FIELD_PHOTO, "label", "锂电隔离照片快评等级",
                        "description", "仅照片快评，条件混杂、一致性不足，登记但禁止排名")));
        m.put("gateThresholds", gateThresholds);
        m.put("hierarchicalRequirements", hierarchicalReq);
        m.put("inspectors", jdbc.queryForList(
                "SELECT inspector_code, display_name, district, title FROM iso_inspector ORDER BY inspector_code"));
        return m;
    }

    // ============================ 观察记录 ============================

    public List<Map<String, Object>> observations(String fieldId, String mode) {
        String fid = fieldId == null || fieldId.isBlank() ? FIELD_ISO : fieldId;
        StringBuilder sql = new StringBuilder("""
                SELECT o.*, s.district, s.site_name, i.display_name AS inspector_name
                FROM iso_observation o
                LEFT JOIN iso_site s ON s.site_code=o.site_code
                LEFT JOIN iso_inspector i ON i.inspector_code=o.inspector_code
                WHERE o.field_id=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(fid);
        if (mode != null && !mode.isBlank()) {
            sql.append(" AND o.mode=?");
            args.add(mode);
        }
        sql.append(" ORDER BY o.observed_at, o.obs_id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(r -> r.put("conditions_json", DbJson.toMap(r.get("conditions_json"))));
        return rows;
    }

    // ============================ 配对账 ============================

    public List<Map<String, Object>> pairs(String fieldId, String pairType) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.pair_id, p.field_id, p.pair_type, p.site_code, p.orientation,
                       p.obs_a, p.obs_b,
                       oa.level_value AS level_a, oa.mode AS mode_a, oa.inspector_code AS inspector_a,
                       ob.level_value AS level_b, ob.mode AS mode_b, ob.inspector_code AS inspector_b,
                       ob.photo_condition_code AS condition_code_b,
                       ob.conditions_json AS conditions_b,
                       s.district, s.site_name,
                       ia.display_name AS inspector_a_name, ib.display_name AS inspector_b_name
                FROM iso_pair p
                JOIN iso_observation oa ON oa.obs_id=p.obs_a
                JOIN iso_observation ob ON ob.obs_id=p.obs_b
                LEFT JOIN iso_site s ON s.site_code=p.site_code
                LEFT JOIN iso_inspector ia ON ia.inspector_code=oa.inspector_code
                LEFT JOIN iso_inspector ib ON ib.inspector_code=ob.inspector_code
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (fieldId != null && !fieldId.isBlank()) { sql.append(" AND p.field_id=?"); args.add(fieldId); }
        if (pairType != null && !pairType.isBlank()) { sql.append(" AND p.pair_type=?"); args.add(pairType); }
        sql.append(" ORDER BY p.field_id, p.pair_type, p.pair_id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            int la = ((Number) r.get("level_a")).intValue();
            int lb = ((Number) r.get("level_b")).intValue();
            r.put("signed_diff", lb - la);
            r.put("abs_diff", Math.abs(lb - la));
            r.put("exact_match", la == lb);
            r.put("conditions_b", DbJson.toMap(r.get("conditions_b")));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> createPair(Map<String, Object> body) {
        requireStaff();
        String obsA = str(body.get("obsA"));
        String obsB = str(body.get("obsB"));
        String pairType = str(body.getOrDefault("pairType", "SAME_SITE_REVISIT"));
        if (obsA == null || obsB == null) throw new GovernanceException("obsA / obsB 不能为空");
        if (!List.of("SAME_SITE_REVISIT", "BLIND_PHOTO", "ONSITE_VS_MEASURE").contains(pairType)) {
            throw new GovernanceException("未知配对类型: " + pairType);
        }
        Map<String, Object> a = loadObs(obsA);
        Map<String, Object> b = loadObs(obsB);
        String fieldId = str(body.get("fieldId"));
        if (fieldId == null) fieldId = (String) a.get("field_id");
        if (!fieldId.equals(a.get("field_id")) || !fieldId.equals(b.get("field_id"))) {
            throw new GovernanceException("两条观察不属于同一字段，不能配对");
        }
        String pairId = str(body.get("pairId"));
        if (pairId == null || pairId.isBlank()) pairId = "P-WEB-" + UUID.randomUUID();
        String orientation = str(body.get("orientation"));
        if (orientation == null) orientation = defaultOrientation(pairType);
        String site = (String) a.get("site_code");
        jdbc.update("""
                INSERT INTO iso_pair(pair_id,field_id,pair_type,site_code,obs_a,obs_b,orientation)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (pair_id) DO NOTHING
                """, pairId, fieldId, pairType, site, obsA, obsB, orientation);
        int la = ((Number) a.get("level_value")).intValue();
        int lb = ((Number) b.get("level_value")).intValue();
        return Map.of("pairId", pairId, "fieldId", fieldId, "pairType", pairType,
                "levelA", la, "levelB", lb, "signedDiff", lb - la, "absDiff", Math.abs(lb - la),
                "note", "配对已登记并纳入下一次一致性估计");
    }

    private Map<String, Object> loadObs(String obsId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM iso_observation WHERE obs_id=?", obsId);
        if (rows.isEmpty()) throw new GovernanceException("观察记录不存在: " + obsId);
        return rows.get(0);
    }

    private String defaultOrientation(String t) {
        return switch (t) {
            case "SAME_SITE_REVISIT" -> "等级差=复查−初查；正=复查更严";
            case "BLIND_PHOTO" -> "等级差=盲审照片−对照侧；正=照片判读更严";
            case "ONSITE_VS_MEASURE" -> "等级差=量测反演−现场；正=量测口径更严";
            default -> "有符号等级差 B−A";
        };
    }

    // ============================ 一致性估计 + 闸门 ============================

    public Map<String, Object> consistency(String fieldId) {
        String fid = fieldId == null || fieldId.isBlank() ? FIELD_ISO : fieldId;
        AgreementStats.Result overall = AgreementStats.analyze(pairLevels(fid, null));
        Map<String, Object> byType = new LinkedHashMap<>();
        for (String t : List.of("SAME_SITE_REVISIT", "BLIND_PHOTO", "ONSITE_VS_MEASURE")) {
            byType.put(t, AgreementStats.analyze(pairLevels(fid, t)));
        }
        Map<String, Object> photoQuality = new LinkedHashMap<>();
        photoQuality.put("GOOD", AgreementStats.analyze(photoQualityPairs(fid, "GOOD")));
        photoQuality.put("ADVERSE", AgreementStats.analyze(photoQualityPairs(fid, "ADVERSE")));

        ReliabilityGate.Verdict verdict = ReliabilityGate.evaluate(fid, overall, gateThresholds);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldId", fid);
        m.put("overall", overall);
        m.put("byType", byType);
        m.put("byPhotoQuality", photoQuality);
        m.put("gate", verdict);
        return m;
    }

    public ReliabilityGate.Verdict gate(String fieldId) {
        String fid = fieldId == null || fieldId.isBlank() ? FIELD_ISO : fieldId;
        return ReliabilityGate.evaluate(fid, AgreementStats.analyze(pairLevels(fid, null)), gateThresholds);
    }

    private List<AgreementStats.Pair> pairLevels(String fieldId, String pairType) {
        if (pairType == null) {
            return jdbc.query("""
                    SELECT oa.level_value AS a, ob.level_value AS b
                    FROM iso_pair p
                    JOIN iso_observation oa ON oa.obs_id=p.obs_a
                    JOIN iso_observation ob ON ob.obs_id=p.obs_b
                    WHERE p.field_id=?
                    """, (rs, i) -> new AgreementStats.Pair(rs.getInt("a"), rs.getInt("b")),
                    fieldId);
        }
        return jdbc.query("""
                SELECT oa.level_value AS a, ob.level_value AS b
                FROM iso_pair p
                JOIN iso_observation oa ON oa.obs_id=p.obs_a
                JOIN iso_observation ob ON ob.obs_id=p.obs_b
                WHERE p.field_id=? AND p.pair_type=?
                """, (rs, i) -> new AgreementStats.Pair(rs.getInt("a"), rs.getInt("b")),
                fieldId, pairType);
    }

    private List<AgreementStats.Pair> photoQualityPairs(String fieldId, String quality) {
        return jdbc.query("""
                SELECT oa.level_value AS a, ob.level_value AS b
                FROM iso_pair p
                JOIN iso_observation oa ON oa.obs_id=p.obs_a
                JOIN iso_observation ob ON ob.obs_id=p.obs_b
                WHERE p.field_id=? AND p.pair_type='BLIND_PHOTO'
                  AND ob.conditions_json->>'quality'=?
                """, (rs, i) -> new AgreementStats.Pair(rs.getInt("a"), rs.getInt("b")),
                fieldId, quality);
    }

    // ============================ 跨区排名（受闸门保护） ============================

    public Map<String, Object> ranking(String fieldId) {
        String fid = fieldId == null || fieldId.isBlank() ? FIELD_ISO : fieldId;
        ReliabilityGate.Verdict verdict = gate(fid);
        if (!verdict.rankingEligible()) {
            throw new ReliabilityGateException(
                    "字段 " + fid + " 的等级一致性未通过闸门，已登记但不得驱动跨区排名", verdict);
        }
        List<Map<String, Object>> districts = jdbc.queryForList("""
                SELECT s.district AS district,
                       AVG(o.level_value) AS mean_level,
                       COUNT(*) AS observation_count,
                       COUNT(DISTINCT o.site_code) AS site_count
                FROM iso_observation o
                JOIN iso_site s ON s.site_code=o.site_code
                WHERE o.field_id=?
                GROUP BY s.district
                ORDER BY AVG(o.level_value) DESC
                """, fid);
        List<Map<String, Object>> ranking = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> d : districts) {
            Map<String, Object> row = new LinkedHashMap<>(d);
            row.put("rank", rank++);
            double mean = ((Number) d.get("mean_level")).doubleValue();
            row.put("meanLevel", Math.round(mean * 1000.0) / 1000.0);
            ranking.add(row);
        }
        return Map.of("fieldId", fid, "rankingEligible", true, "gate", verdict,
                "metric", "各区场所观察平均等级（越高隔离风险越突出）", "ranking", ranking);
    }

    // ============================ 分层测量模型 ============================

    public HierarchicalModel.Result hierarchical(String fieldId) {
        String fid = fieldId == null || fieldId.isBlank() ? FIELD_ISO : fieldId;
        List<HierarchicalModel.Obs> obs = jdbc.query("""
                SELECT level_value AS y, site_code AS site, inspector_code AS inspector
                FROM iso_observation
                WHERE field_id=? AND site_code IS NOT NULL AND inspector_code IS NOT NULL
                """, (rs, i) -> new HierarchicalModel.Obs(
                        rs.getInt("y"), rs.getString("site"), rs.getString("inspector")), fid);
        Integer revisit = jdbc.queryForObject(
                "SELECT count(*) FROM iso_pair WHERE field_id=? AND pair_type='SAME_SITE_REVISIT'",
                Integer.class, fid);
        return HierarchicalModel.fit(obs, revisit == null ? 0 : revisit, hierarchicalReq);
    }

    // ============================ 锚样校准器 ============================

    public List<Map<String, Object>> anchors() {
        return jdbc.queryForList("""
                SELECT a.*,
                  (SELECT count(*) FROM iso_anchor_score s WHERE s.anchor_id=a.anchor_id) AS score_count
                FROM iso_anchor_sample a ORDER BY a.anchor_class, a.anchor_id
                """);
    }

    public Map<String, Object> calibration(String inspectorCode) {
        List<Map<String, Object>> scoreRows = jdbc.queryForList("""
                SELECT s.anchor_id, s.inspector_code, s.given_level,
                       a.anchor_class, a.reference_level, a.scenario_code,
                       i.display_name AS inspector_name, i.district
                FROM iso_anchor_score s
                JOIN iso_anchor_sample a ON a.anchor_id=s.anchor_id
                JOIN iso_inspector i ON i.inspector_code=s.inspector_code
                """);
        if (inspectorCode != null && !inspectorCode.isBlank()) {
            scoreRows = scoreRows.stream().filter(r -> inspectorCode.equals(r.get("inspector_code"))).toList();
        }

        Map<String, AgreementStats.Result> perClass = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> classInspectorPairs = new LinkedHashMap<>();
        for (String cls : List.of("PERMANENT", "TEMP_CONSTRUCTION")) {
            List<AgreementStats.Pair> pairs = new ArrayList<>();
            for (Map<String, Object> r : scoreRows) {
                if (cls.equals(r.get("anchor_class"))) {
                    pairs.add(new AgreementStats.Pair(
                            ((Number) r.get("reference_level")).intValue(),
                            ((Number) r.get("given_level")).intValue()));
                }
            }
            perClass.put(cls, AgreementStats.analyze(pairs));
        }

        // 每名检查员的锚样偏差（全类别 + 分类别）
        List<Map<String, Object>> inspectors = jdbc.queryForList(
                "SELECT inspector_code, display_name, district FROM iso_inspector " +
                        "WHERE inspector_code <> 'INS-MEAS' ORDER BY inspector_code");
        List<Map<String, Object>> perInspector = new ArrayList<>();
        for (Map<String, Object> ins : inspectors) {
            String code = (String) ins.get("inspector_code");
            List<Map<String, Object>> mine = scoreRows.stream()
                    .filter(r -> code.equals(r.get("inspector_code"))).toList();
            if (mine.isEmpty()) continue;
            List<AgreementStats.Pair> all = toRefGivenPairs(mine);
            AgreementStats.Result stat = AgreementStats.analyze(all);
            Map<String, Object> classBias = new LinkedHashMap<>();
            for (String cls : List.of("PERMANENT", "TEMP_CONSTRUCTION")) {
                List<AgreementStats.Pair> cp = toRefGivenPairs(
                        mine.stream().filter(r -> cls.equals(r.get("anchor_class"))).toList());
                AgreementStats.Result cr = AgreementStats.analyze(cp);
                classBias.put(cls, cr.n() == 0 ? null : cr.meanSignedDiff());
            }
            boolean flagged = Math.abs(stat.meanSignedDiff()) >= 0.5
                    || (stat.kappaQuadratic() != null && stat.kappaQuadratic() < 0.6);
            Map<String, Object> row = new LinkedHashMap<>(ins);
            row.put("n", stat.n());
            row.put("exactAgreement", stat.exactAgreement());
            row.put("weightedAgreementQuadratic", stat.weightedAgreementQuadratic());
            row.put("kappaQuadratic", stat.kappaQuadratic());
            row.put("meanSignedBias", stat.meanSignedDiff());
            row.put("biasDirection", stat.biasDirection());
            row.put("classBias", classBias);
            row.put("flagged", flagged);
            perInspector.add(row);
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("poolingAllowed", false);
        rule.put("tempMustNotPoolWithPermanent", true);
        rule.put("message", "临时施工照片锚样与永久设施锚样的目标物、工况和失效模式不同，"
                + "必须分类校准、分类报告，禁止合并为同一类锚样评分。");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("perClass", perClass);
        m.put("perInspector", perInspector);
        m.put("coverage", anchorCoverage());
        m.put("rule", rule);
        return m;
    }

    /** 单类校准；显式要求跨类合并时直接 422，落实“临时施工不得与永久设施同类”。 */
    public Map<String, Object> evaluateAnchorClass(Map<String, Object> body) {
        Boolean pool = body == null ? null : bool(body.get("poolAcrossClasses"));
        Object classes = body == null ? null : body.get("anchorClasses");
        if (Boolean.TRUE.equals(pool) || (classes instanceof List<?> list && list.size() > 1)) {
            throw new GovernanceException("禁止把临时施工锚样与永久设施锚样合并为同类："
                    + "两类必须分别建立锚集、分别计算一致率与偏差。");
        }
        String cls = body == null ? "PERMANENT" : str(body.getOrDefault("anchorClass", "PERMANENT"));
        if (!"PERMANENT".equals(cls) && !"TEMP_CONSTRUCTION".equals(cls)) {
            throw new GovernanceException("未知锚样类别: " + cls);
        }
        List<AgreementStats.Pair> pairs = jdbc.query("""
                SELECT a.reference_level AS r, s.given_level AS g
                FROM iso_anchor_score s JOIN iso_anchor_sample a ON a.anchor_id=s.anchor_id
                WHERE a.anchor_class=?
                """, (rs, i) -> new AgreementStats.Pair(rs.getInt("r"), rs.getInt("g")), cls);
        return Map.of("anchorClass", cls, "pooled", false, "result", AgreementStats.analyze(pairs));
    }

    @Transactional
    public Map<String, Object> submitScore(Map<String, Object> body) {
        requireStaff();
        String anchorId = str(body.get("anchorId"));
        String inspector = str(body.get("inspectorCode"));
        Integer level = body.get("givenLevel") == null ? null : ((Number) body.get("givenLevel")).intValue();
        if (anchorId == null || inspector == null || level == null) {
            throw new GovernanceException("anchorId / inspectorCode / givenLevel 不能为空");
        }
        if (level < 1 || level > 4) throw new GovernanceException("锚样评分等级必须在 1..4");
        Integer anchorExists = jdbc.queryForObject(
                "SELECT count(*) FROM iso_anchor_sample WHERE anchor_id=?", Integer.class, anchorId);
        Integer inspExists = jdbc.queryForObject(
                "SELECT count(*) FROM iso_inspector WHERE inspector_code=?", Integer.class, inspector);
        if (anchorExists == null || anchorExists == 0) throw new GovernanceException("锚样不存在: " + anchorId);
        if (inspExists == null || inspExists == 0) throw new GovernanceException("检查员不存在: " + inspector);
        jdbc.update("""
                INSERT INTO iso_anchor_score(anchor_id,inspector_code,given_level,scored_at)
                VALUES (?,?,?,?)
                ON CONFLICT (anchor_id,inspector_code)
                DO UPDATE SET given_level=EXCLUDED.given_level
                """, anchorId, inspector, level, LocalDate.now());
        return Map.of("ok", true, "anchorId", anchorId, "inspectorCode", inspector, "givenLevel", level);
    }

    private List<AgreementStats.Pair> toRefGivenPairs(List<Map<String, Object>> rows) {
        List<AgreementStats.Pair> pairs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            pairs.add(new AgreementStats.Pair(
                    ((Number) r.get("reference_level")).intValue(),
                    ((Number) r.get("given_level")).intValue()));
        }
        return pairs;
    }

    /** 固定锚样的场景覆盖：典型场景解释力强，但新兴场景覆盖有限。 */
    private Map<String, Object> anchorCoverage() {
        record Scn(String code, String name, boolean novel) {}
        List<Scn> universe = List.of(
                new Scn("BATT_ROOM", "电池室/储能舱固定布置", false),
                new Scn("CAB_RACK", "电池柜/货架间距", false),
                new Scn("CHARGE_AREA", "固定充电区", false),
                new Scn("PART_WALL", "防火分隔/隔墙", false),
                new Scn("DOOR_EXIT", "疏散门/通道堆距", false),
                new Scn("TMP_CONSTRUCT", "临时施工现场充电", false),
                new Scn("TMP_STACK", "临时电芯堆放", false),
                new Scn("TMP_REPAIR", "维修临时工位", false),
                new Scn("TMP_EVENT", "活动临时储能", false),
                new Scn("SPRINKLER_LINK", "喷淋-探测联动失效", true),
                new Scn("BASEMENT", "地下车库储能点", true),
                new Scn("ROOFTOP", "屋面设备平台", true),
                new Scn("OUTDOOR_CONTAINER", "户外预制舱", true),
                new Scn("THERMAL_AFTER", "热失控后复勘", true),
                new Scn("SECOND_LIFE", "梯次利用电池堆", true));
        List<String> coveredCodes = jdbc.queryForList(
                "SELECT DISTINCT scenario_code FROM iso_anchor_sample", String.class);
        List<Map<String, Object>> scenarios = new ArrayList<>();
        List<String> uncoveredNovel = new ArrayList<>();
        int covered = 0, novelTotal = 0, novelCovered = 0;
        for (Scn s : universe) {
            boolean isCovered = coveredCodes.contains(s.code());
            if (isCovered) covered++;
            if (s.novel()) {
                novelTotal++;
                if (isCovered) novelCovered++; else uncoveredNovel.add(s.code());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", s.code());
            row.put("name", s.name());
            row.put("novel", s.novel());
            row.put("covered", isCovered);
            scenarios.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarios", scenarios);
        m.put("universeCount", universe.size());
        m.put("coveredCount", covered);
        m.put("coverageRate", Math.round(1000.0 * covered / universe.size()) / 1000.0);
        m.put("novelCount", novelTotal);
        m.put("novelCovered", novelCovered);
        m.put("novelCoverageRate", 0.0);
        m.put("uncoveredNovelScenarios", uncoveredNovel);
        m.put("limitation", "固定锚样评分对已覆盖的典型场景解释强、反馈快，但本轮锚样未覆盖 "
                + String.join("、", uncoveredNovel) + " 等新场景，不能据锚样校准结果外推这些场景；"
                + "新场景应依靠分层模型与额外复检/锚样扩展。");
        return m;
    }

    // ============================ 培训反馈台 ============================

    public List<Map<String, Object>> feedbackList() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.*, i.display_name AS inspector_name
                FROM iso_feedback f LEFT JOIN iso_inspector i ON i.inspector_code=f.inspector_code
                ORDER BY CASE f.status WHEN 'OPEN' THEN 0 WHEN 'ACKED' THEN 1 ELSE 2 END,
                         f.severity, f.feedback_id
                """);
        rows.forEach(r -> r.put("basis_json", DbJson.toMap(r.get("basis_json"))));
        return rows;
    }

    @Transactional
    public List<Map<String, Object>> generateFeedback() {
        requireStaff();
        // 1) 锚样偏差
        List<Map<String, Object>> scores = jdbc.queryForList("""
                SELECT s.inspector_code, a.reference_level r, s.given_level g
                FROM iso_anchor_score s JOIN iso_anchor_sample a ON a.anchor_id=s.anchor_id
                """);
        Map<String, double[]> biasAgg = new LinkedHashMap<>(); // code -> [sumDiff,count]
        for (Map<String, Object> r : scores) {
            String code = (String) r.get("inspector_code");
            double d = ((Number) r.get("g")).doubleValue() - ((Number) r.get("r")).doubleValue();
            double[] agg = biasAgg.computeIfAbsent(code, k -> new double[2]);
            agg[0] += d; agg[1]++;
        }
        // 2) 配对分歧归因（两侧检查员各记一次；严重 |d|>=2 单独计数）
        Map<String, int[]> pairAgg = new LinkedHashMap<>(); // code -> [disagree, severe]
        List<Map<String, Object>> pairRows = jdbc.queryForList("""
                SELECT oa.inspector_code ia, ob.inspector_code ib,
                       oa.level_value la, ob.level_value lb
                FROM iso_pair p
                JOIN iso_observation oa ON oa.obs_id=p.obs_a
                JOIN iso_observation ob ON ob.obs_id=p.obs_b
                """);
        for (Map<String, Object> r : pairRows) {
            int d = ((Number) r.get("lb")).intValue() - ((Number) r.get("la")).intValue();
            if (d == 0) continue;
            String ia = (String) r.get("ia"), ib = (String) r.get("ib");
            attribute(pairAgg, ia, Math.abs(d) >= 2);
            attribute(pairAgg, ib, Math.abs(d) >= 2);
        }

        List<Map<String, Object>> inspectors = jdbc.queryForList(
                "SELECT inspector_code, display_name, district FROM iso_inspector " +
                        "WHERE inspector_code <> 'INS-MEAS'");
        String reviewer = CurrentUser.get() == null ? "system" : CurrentUser.get().username();
        for (Map<String, Object> ins : inspectors) {
            String code = (String) ins.get("inspector_code");
            double[] ba = biasAgg.getOrDefault(code, new double[2]);
            double bias = ba[1] == 0 ? 0 : ba[0] / ba[1];
            int[] pa = pairAgg.getOrDefault(code, new int[2]);
            int disagree = pa[0], severe = pa[1];

            boolean biasIssue = Math.abs(bias) >= 0.5;
            boolean severeIssue = severe >= 1;
            boolean repeatIssue = disagree >= 4;
            if (!biasIssue && !severeIssue && !repeatIssue) continue;

            String severity = (Math.abs(bias) >= 0.75 || severe >= 2) ? "HIGH" : "MEDIUM";
            String direction = bias < 0 ? "系统性偏松（倾向给更低等级）"
                    : bias > 0 ? "系统性偏严（倾向给更高等级）" : "锚样无明显方向偏差";
            String topic;
            String suggestion;
            if (biasIssue) {
                topic = "锚样校准偏差：" + direction;
                suggestion = "重做永久/临时分类锚样各一轮，重点对齐相邻等级边界；由高级检查员带教并在 2 周后复测。";
            } else if (severeIssue) {
                topic = "出现 " + severe + " 次跨两级及以上判定分歧";
                suggestion = "针对跨级分歧案件开展个案复盘，统一重大(4)/整改(3)边界判定，完成 30 例盲审再认证。";
            } else {
                topic = "复检/盲审中相邻等级分歧 " + disagree + " 次";
                suggestion = "安排照片条件（逆光/遮挡/远距）专项训练，使用标准化观察条件编码后再采集。";
            }
            Map<String, Object> basis = new LinkedHashMap<>();
            basis.put("anchorMeanBias", Math.round(bias * 1000.0) / 1000.0);
            basis.put("anchorScored", (int) ba[1]);
            basis.put("pairDisagreements", disagree);
            basis.put("severeDisagreements", severe);
            basis.put("generatedBy", reviewer);

            jdbc.update("""
                    INSERT INTO iso_feedback(feedback_id,inspector_code,district,topic,severity,basis_json,suggestion,status,created_by)
                    VALUES (?,?,?,?,?,?::jsonb,?, 'OPEN', ?)
                    ON CONFLICT (feedback_id) DO UPDATE SET
                      topic=EXCLUDED.topic, severity=EXCLUDED.severity,
                      basis_json=EXCLUDED.basis_json, suggestion=EXCLUDED.suggestion,
                      updated_at=now()
                    """, "FB-AUTO-" + code, code, ins.get("district"), topic, severity,
                    DbJson.json(basis), suggestion, reviewer);
        }
        return feedbackList();
    }

    private void attribute(Map<String, int[]> agg, String code, boolean severe) {
        if (code == null || "INS-MEAS".equals(code)) return;
        int[] c = agg.computeIfAbsent(code, k -> new int[2]);
        c[0]++;
        if (severe) c[1]++;
    }

    @Transactional
    public Map<String, Object> updateFeedbackStatus(String feedbackId, Map<String, Object> body) {
        requireStaff();
        String status = body == null ? null : str(body.get("status"));
        if (!List.of("OPEN", "ACKED", "DONE").contains(status)) {
            throw new GovernanceException("状态必须为 OPEN / ACKED / DONE");
        }
        int n = jdbc.update(
                "UPDATE iso_feedback SET status=?, updated_at=now() WHERE feedback_id=?",
                status, feedbackId);
        if (n == 0) throw new GovernanceException("反馈条目不存在: " + feedbackId);
        return feedbackList().stream().filter(f -> feedbackId.equals(f.get("feedback_id"))).findFirst().orElse(Map.of());
    }

    // ============================ 权限 / 工具 ============================

    private void requireStaff() {
        var u = CurrentUser.get();
        if (u == null || (!"CITY_ADMIN".equals(u.role()) && !"DISTRICT_ANALYST".equals(u.role()))) {
            String role = u == null ? "ANONYMOUS" : u.role();
            throw new GovernanceException("权限不足：仅市级管理员或辖区分析员可执行该写操作（当前角色 " + role + "）");
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }
}
