package com.fire.fieldgov.governance;

import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.web.GovernanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 新隐患字段治理（两条路线）：
 *  A. CITY_STANDARD：市级目录直接立项统一标准字段，口径一致但新类型纳入需等待治理评审；
 *  B. NAMESPACE_EXT：辖区在命名空间内扩展字段并提交带证据的映射契约，市级批准后进入查询编译。
 * 批准前：契约为 DRAFT，编译器拒绝（语义未决，不得强行合并）。
 * 批准时：发布新目录版本写入字段定义、契约转 APPROVED；可给证据支持的追溯生效日，
 *         使旧实例在“不改写表单行”的前提下，于编译期按已批准契约回溯解释。
 */
@Service
public class ExtensionGovernanceService {

    private final JdbcTemplate jdbc;

    public ExtensionGovernanceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> listExtensions() {
        return jdbc.queryForList(
                "SELECT request_id, route, district, proposed_field_id, field_def_json, contract_json, " +
                        "status, evidence, catalog_version_introduced, reviewed_by, created_at, approved_at " +
                        "FROM extension_request ORDER BY created_at")
                .stream().map(row -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(row);
                    m.put("field_def_json", DbJson.toMap(row.get("field_def_json")));
                    Object cj = row.get("contract_json");
                    m.put("contract_json", cj == null ? null : DbJson.toMap(cj));
                    return m;
                }).toList();
    }

    @Transactional
    public Map<String, Object> approve(String requestId, String retroactiveFrom) {
        requireAdmin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM extension_request WHERE request_id=?", requestId);
        if (rows.isEmpty()) throw new GovernanceException("治理申请不存在: " + requestId);
        Map<String, Object> req = rows.get(0);
        if (!"DRAFT".equals(req.get("status"))) {
            throw new GovernanceException("申请已处理，当前状态 " + req.get("status"));
        }
        int newVersion = jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM field_catalog_version", Integer.class);
        String reviewer = CurrentUser.get().username();
        LocalDate retro = retroactiveFrom == null || retroactiveFrom.isBlank()
                ? LocalDate.now() : LocalDate.parse(retroactiveFrom);

        Map<String, Object> fieldJson = DbJson.toMap(req.get("field_def_json"));
        String route = (String) req.get("route");

        if ("CITY_STANDARD".equals(route)) {
            // 路线 A：市级标准字段直接入目录，等待各辖区表单采用
            insertStdField(newVersion, fieldJson, retro);
            jdbc.update("INSERT INTO field_catalog_version(version,note) VALUES (?,?)",
                    newVersion, "市级标准立项：" + fieldJson.get("field_id"));
        } else {
            // 路线 B：命名空间扩展 + 映射契约批准；同时确保标准目标字段存在
            Map<String, Object> contractJson = DbJson.toMap(req.get("contract_json"));
            String targetFieldId = (String) contractJson.get("target_field_id");
            Integer targetExists = jdbc.queryForObject(
                    "SELECT count(*) FROM field_def WHERE field_id=?", Integer.class, targetFieldId);
            if (targetExists == null || targetExists == 0) {
                insertStdField(newVersion, Map.of(
                        "field_id", targetFieldId,
                        "display_name", fieldJson.get("display_name") + "（标准目标）",
                        "definition", "由辖区扩展 " + fieldJson.get("field_id") + " 经证据映射汇入的市级标准字段",
                        "value_domain", fieldJson.getOrDefault("value_domain", Map.of("type", "enum")),
                        "namespace", "CITY"), retro);
            }
            insertNamespacedField(newVersion, fieldJson, (String) req.get("district"), retro);
            String contractId = (String) contractJson.get("contract_id");
            // 契约转 APPROVED，生效日可追溯（证据支持），旧表单行不做任何改写
            jdbc.update("UPDATE mapping_contract SET status='APPROVED', reviewed_by=?, effective_from=? " +
                            "WHERE contract_id=?", reviewer, retro, contractId);
            jdbc.update("INSERT INTO field_catalog_version(version,note) VALUES (?,?)",
                    newVersion, "批准辖区扩展：" + fieldJson.get("field_id") + "（契约 " + contractId + "，追溯生效 " + retro + "）");
        }

        jdbc.update("UPDATE extension_request SET status='APPROVED', reviewed_by=?, " +
                        "catalog_version_introduced=?, approved_at=now() WHERE request_id=?",
                reviewer, newVersion, requestId);
        jdbc.update("INSERT INTO audit_log(username,action,detail) VALUES (?,?,?::jsonb)",
                reviewer, "APPROVE_EXTENSION",
                DbJson.json(Map.of("requestId", requestId, "route", route,
                        "catalogVersion", newVersion, "retroactiveFrom", retro.toString())));
        return Map.of("requestId", requestId, "status", "APPROVED", "catalogVersionIntroduced", newVersion,
                "retroactiveFrom", retro.toString(),
                "note", "旧表单实例未改写；回溯由编译期按已批准契约与证据生效日完成");
    }

    @Transactional
    public Map<String, Object> reject(String requestId, String reason) {
        requireAdmin();
        int n = jdbc.update("UPDATE extension_request SET status='REJECTED', reviewed_by=? WHERE request_id=? AND status='DRAFT'",
                CurrentUser.get().username(), requestId);
        if (n == 0) throw new GovernanceException("申请不存在或已处理");
        jdbc.update("INSERT INTO audit_log(username,action,detail) VALUES (?,?,?::jsonb)",
                CurrentUser.get().username(), "REJECT_EXTENSION",
                DbJson.json(Map.of("requestId", requestId, "reason", reason == null ? "" : reason)));
        return Map.of("requestId", requestId, "status", "REJECTED");
    }

    private void requireAdmin() {
        if (!"CITY_ADMIN".equals(CurrentUser.get().role())) {
            throw new GovernanceException("仅市级管理员（CITY_ADMIN）可评审治理申请");
        }
    }

    private void insertStdField(int version, Map<String, Object> f, LocalDate validFrom) {
        jdbc.update("""
                INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,
                    event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace)
                VALUES (?,?,?,?,?::jsonb,?,?,?,'{}','INTERNAL',?,'ACTIVE','CITY')
                """,
                f.get("field_id"), version, f.get("display_name"),
                f.getOrDefault("definition", "市级标准字段（治理立项）"),
                DbJson.json(f.get("value_domain")),
                f.getOrDefault("unit", null), f.getOrDefault("granularity", "案件级"),
                f.getOrDefault("event_time_meaning", "检查发现时刻"), validFrom);
    }

    private void insertNamespacedField(int version, Map<String, Object> f, String district, LocalDate validFrom) {
        jdbc.update("""
                INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,
                    event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?,'INTERNAL',?,'ACTIVE',?)
                """,
                f.get("field_id"), version, f.get("display_name"),
                f.getOrDefault("definition", "辖区命名空间扩展字段"),
                DbJson.json(f.get("value_domain")),
                f.getOrDefault("unit", null), f.getOrDefault("granularity", "案件级"),
                f.getOrDefault("event_time_meaning", "检查发现时刻"),
                new String[]{district},
                validFrom, district);
    }
}
