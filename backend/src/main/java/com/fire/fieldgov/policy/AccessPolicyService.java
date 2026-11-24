package com.fire.fieldgov.policy;

import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 访问策略：按 用户辖区 / 案件权限 / 字段敏感级 / 目的 四重条件过滤。
 * - STAT 目的：辖区级授权即可聚合；RESTRICTED 字段要求用户具备 RESTRICTED 密级，否则整行剔除。
 * - CASE_INVEST/AUDIT 等个案目的：仅能访问 user_case_grant 显式授权的案件（辖区级 NULL 授权也算）。
 * - 跨辖区案件：仅在 user_case_grant 中逐案授权时放行。
 */
@Service
public class AccessPolicyService {

    private final JdbcTemplate jdbc;

    public AccessPolicyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Decision(boolean allowed, String reason) {
        public static Decision ok() { return new Decision(true, null); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
    }

    public Decision checkPurpose(UserContext user, String purpose) {
        if (!user.hasPurpose(purpose)) {
            return Decision.deny("用户 " + user.username() + " 未被授予目的 " + purpose);
        }
        return Decision.ok();
    }

    private Set<String> grantedCases(UserContext user, String purpose) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT case_id FROM user_case_grant WHERE username=? AND purpose=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String caseId = rs.getString("case_id");
                    if (caseId != null && !"*".equals(caseId)) out.add(caseId);
                }, user.username(), purpose);
        return out;
    }

    private boolean hasDistrictLevelGrant(UserContext user, String district, String purpose) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM user_case_grant WHERE username=? AND district=? AND case_id='*' AND purpose=?",
                Integer.class, user.username(), district, purpose);
        return n != null && n > 0;
    }

    /**
     * 行级访问判定。
     * @param instance 待评估表单实例
     * @param touchedFieldDefs 本次查询实际触达的字段定义（用于敏感级判定）
     */
    public Decision evaluateRow(UserContext user, FormInstance instance,
                                List<FieldDef> touchedFieldDefs, String purpose) {
        String district = instance.district();
        // 1) 辖区 / 案件权限
        boolean homeMatch = user.isCityWide() || district.equals(user.homeDistrict());
        boolean districtGranted = hasDistrictLevelGrant(user, district, purpose);
        boolean caseGranted = grantedCases(user, purpose).contains(instance.caseId());
        if ("STAT".equals(purpose)) {
            if (!homeMatch && !districtGranted && !caseGranted) {
                return Decision.deny("辖区 " + district + " 不在用户授权范围（目的 STAT）");
            }
        } else {
            // 个案类目的：需要显式案件授权或本辖区级授权
            if (!(districtGranted || caseGranted || (homeMatch && districtGranted))) {
                return Decision.deny("案件 " + instance.caseId() + " 未对目的 " + purpose + " 逐案授权");
            }
        }
        // 2) 字段敏感级
        boolean restricted = touchedFieldDefs.stream().anyMatch(d -> "RESTRICTED".equals(d.sensitivity()));
        if (restricted && !"RESTRICTED".equals(user.clearance())) {
            return Decision.deny("查询触达 RESTRICTED 敏感字段，用户密级为 " + user.clearance());
        }
        return Decision.ok();
    }

    public List<Map<String, Object>> listGrants(String username) {
        return jdbc.queryForList(
                "SELECT username, district, case_id, purpose FROM user_case_grant WHERE username=? ORDER BY district, purpose",
                username);
    }
}
