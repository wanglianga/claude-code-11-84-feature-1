package com.fire.fieldgov.offline;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.web.GovernanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线草案合并：
 *  - 草案以“字段 ID + 基线目录版本”标识；同 username+client_id 重复同步走幂等。
 *  - 基线版本 == 当前版本：直接合并（MERGED）。
 *  - 基线版本 < 当前版本：逐字段比对基线与当前定义，definition/value_domain/unit 变化记 SEMANTIC_CHANGED，
 *    sensitivity 变化记 SENSITIVITY_CHANGED；任一命中转 PENDING_CONFLICT，不生成正式实例。
 *  - 仅无关字段变化时允许 REBASED 合并；实例仍按基线版本解释（提交时语义固定）。
 */
@Service
public class OfflineService {

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;

    public OfflineService(JdbcTemplate jdbc, CatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public record DraftPayload(String clientId, String caseId, String district, String templateId,
                               String inspectDate, Map<String, String> values) {}

    @Transactional
    public Map<String, Object> sync(DraftPayload draft) {
        String username = CurrentUser.get().username();
        int currentVersion = catalog.currentCatalogVersion();
        int baseVersion = detectBaseVersion(draft);

        List<Map<String, Object>> conflicts = detectConflicts(draft, baseVersion, currentVersion);
        String draftId = "DR-" + draft.clientId();
        String status;
        String mergedInstanceId = null;

        if (!conflicts.isEmpty()) {
            status = "PENDING_CONFLICT";
        } else {
            mergedInstanceId = insertInstance(draft, username, baseVersion);
            status = baseVersion < currentVersion ? "REBASED" : "MERGED";
        }

        jdbc.update("""
                INSERT INTO offline_draft(draft_id,username,client_id,base_catalog_version,payload,status,conflicts,merged_instance_id)
                VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?)
                ON CONFLICT (username, client_id) DO UPDATE
                  SET payload=EXCLUDED.payload, status=EXCLUDED.status,
                      conflicts=EXCLUDED.conflicts, merged_instance_id=EXCLUDED.merged_instance_id,
                      synced_at=now()
                """,
                draftId, username, draft.clientId(), baseVersion, DbJson.json(draft),
                status, DbJson.json(conflicts), mergedInstanceId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", draftId);
        out.put("status", status);
        out.put("baseCatalogVersion", baseVersion);
        out.put("currentCatalogVersion", currentVersion);
        out.put("conflicts", conflicts);
        out.put("mergedInstanceId", mergedInstanceId);
        if (!conflicts.isEmpty()) {
            out.put("instruction", "存在语义/敏感级并发改动，草案已转待决；请在治理界面复核后重新填报或申请重基线");
        }
        return out;
    }

    public List<Map<String, Object>> listMine() {
        return jdbc.queryForList(
                "SELECT draft_id, client_id, base_catalog_version, status, conflicts, merged_instance_id, created_at, synced_at " +
                        "FROM offline_draft WHERE username=? ORDER BY synced_at DESC",
                CurrentUser.get().username())
                .stream().map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>(row);
                    m.put("conflicts", parseConflicts(row.get("conflicts")));
                    return m;
                }).toList();
    }

    private List<?> parseConflicts(Object pg) {
        if (pg == null) return List.of();
        try {
            String text = pg instanceof org.postgresql.util.PGobject pgo ? pgo.getValue() : pg.toString();
            return DbJson.OM.readValue(text, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 待决草案复核处理：REBASE_RESUBMIT（用户按新目录重填后由客户端重新同步）或 ADMIN_MERGE（管理员强制合并）。 */
    @Transactional
    public Map<String, Object> resolve(String draftId, String decision) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM offline_draft WHERE draft_id=? AND username=?",
                draftId, CurrentUser.get().username());
        if (rows.isEmpty()) throw new GovernanceException("草案不存在或无权操作");
        Map<String, Object> row = rows.get(0);
        if (!"PENDING_CONFLICT".equals(row.get("status"))) {
            throw new GovernanceException("仅 PENDING_CONFLICT 草案可复核，当前状态 " + row.get("status"));
        }
        DraftPayload draft = DbJson.OM.convertValue(DbJson.toMap(row.get("payload")), DraftPayload.class);
        return switch (decision) {
            case "ADMIN_MERGE" -> {
                if (!"CITY_ADMIN".equals(CurrentUser.get().role())) {
                    throw new GovernanceException("强制合并仅市级管理员可执行，且必须完成人工语义复核");
                }
                int base = (Integer) row.get("base_catalog_version");
                String instanceId = insertInstance(draft, CurrentUser.get().username(), base);
                jdbc.update("UPDATE offline_draft SET status='MERGED', merged_instance_id=?, synced_at=now() WHERE draft_id=?",
                        instanceId, draftId);
                jdbc.update("INSERT INTO audit_log(username,action,detail) VALUES (?,?,?::jsonb)",
                        CurrentUser.get().username(), "OFFLINE_ADMIN_MERGE",
                        DbJson.json(Map.of("draftId", draftId, "instanceId", instanceId)));
                yield Map.of("draftId", draftId, "status", "MERGED", "mergedInstanceId", instanceId,
                        "note", "管理员人工复核语义等价后强制合并，实例仍按基线版本解释");
            }
            case "REBASE_RESUBMIT" -> {
                jdbc.update("UPDATE offline_draft SET status='REBASED', synced_at=now() WHERE draft_id=?", draftId);
                yield Map.of("draftId", draftId, "status", "REBASED",
                        "note", "草案退回，按当前目录版本重新填报后再次同步");
            }
            default -> throw new IllegalArgumentException("未知决策 " + decision);
        };
    }

    private int detectBaseVersion(DraftPayload draft) {
        // 模板决定基线版本；与客户端声明保持一致由 Worker 侧保证
        Integer v = jdbc.queryForObject("SELECT catalog_version FROM form_template WHERE template_id=?",
                Integer.class, draft.templateId());
        if (v == null) throw new GovernanceException("表单模板不存在: " + draft.templateId());
        return v;
    }

    private List<Map<String, Object>> detectConflicts(DraftPayload draft, int baseVersion, int currentVersion) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (baseVersion >= currentVersion) return conflicts;
        Map<String, FieldDef> baseDefs = catalog.defsAsOf(baseVersion, LocalDate.now());
        Map<String, FieldDef> currentDefs = catalog.defsAsOf(currentVersion, LocalDate.now());
        for (String fieldId : draft.values().keySet()) {
            FieldDef oldD = baseDefs.get(fieldId);
            FieldDef newD = currentDefs.get(fieldId);
            if (oldD == null) {
                conflicts.add(Map.of("fieldId", fieldId, "type", "FIELD_UNKNOWN_AT_BASELINE",
                        "detail", "基线目录中不存在该字段"));
                continue;
            }
            if (newD == null) {
                conflicts.add(Map.of("fieldId", fieldId, "type", "FIELD_REMOVED",
                        "detail", "字段已从当前目录移除/弃用：" + (oldD.supersededBy() != null ?
                                "替代字段 " + oldD.supersededBy() : "无替代")));
                continue;
            }
            if (newD.version() > oldD.version()) {
                if (!newD.definition().equals(oldD.definition())
                        || !newD.valueDomain().equals(oldD.valueDomain())
                        || !java.util.Objects.equals(newD.unit(), oldD.unit())) {
                    conflicts.add(Map.of("fieldId", fieldId, "type", "SEMANTIC_CHANGED",
                            "detail", "字段语义/值域/单位在 v" + oldD.version() + "→v" + newD.version() + " 间发生变化",
                            "oldVersion", oldD.version(), "newVersion", newD.version()));
                }
                if (!newD.sensitivity().equals(oldD.sensitivity())) {
                    conflicts.add(Map.of("fieldId", fieldId, "type", "SENSITIVITY_CHANGED",
                            "detail", "敏感级 " + oldD.sensitivity() + "→" + newD.sensitivity(),
                            "oldVersion", oldD.version(), "newVersion", newD.version()));
                }
            }
        }
        return conflicts;
    }

    private String insertInstance(DraftPayload draft, String username, int catalogVersion) {
        String instanceId = "FI-OFF-" + draft.clientId();
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM form_instance WHERE instance_id=?", Integer.class, instanceId);
        if (exists != null && exists > 0) return instanceId; // 幂等
        String hash = sha256(draft.clientId() + "|" + draft.values());
        jdbc.update("""
                INSERT INTO form_instance(instance_id,case_id,template_id,district,catalog_version,inspect_date,submitter,content_hash,origin)
                VALUES (?,?,?,?,?,?,?,?, 'OFFLINE_MERGE')
                """,
                instanceId, draft.caseId(), draft.templateId(), draft.district(), catalogVersion,
                LocalDate.parse(draft.inspectDate()), username, hash);
        for (Map.Entry<String, String> e : draft.values().entrySet()) {
            jdbc.update("INSERT INTO form_value(instance_id,field_id,value_text) VALUES (?,?,?)",
                    instanceId, e.getKey(), e.getValue());
        }
        return instanceId;
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
