package com.fire.fieldgov.catalog;

import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.model.MappingContract;
import com.fire.fieldgov.model.MappingValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段目录与映射契约读取服务。
 * 版本解释规则：按请求目录版本（catalogVersion）取该 field_id “版本号 ≤ 请求版本”的最新一行；
 * 若该版本行在 asOf 日期尚未生效或已失效，则继续回退到更早版本。
 */
@Service
public class CatalogService {

    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int currentCatalogVersion() {
        return jdbc.queryForObject("SELECT max(version) FROM field_catalog_version", Integer.class);
    }

    public List<Map<String, Object>> listCatalogVersions() {
        return jdbc.queryForList("SELECT version, note, published_at FROM field_catalog_version ORDER BY version");
    }

    public List<FieldDef> allFieldDefs() {
        return jdbc.query("SELECT * FROM field_def ORDER BY field_id, version", (rs, i) -> new FieldDef(
                rs.getString("field_id"),
                rs.getInt("version"),
                rs.getString("display_name"),
                rs.getString("definition"),
                DbJson.toMap(rs.getObject("value_domain")),
                rs.getString("unit"),
                rs.getString("granularity"),
                rs.getString("event_time_meaning"),
                DbJson.toStringList(rs.getArray("applicable_districts")),
                rs.getString("sensitivity"),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getString("status"),
                rs.getString("namespace"),
                rs.getString("superseded_by")));
    }

    /** 当前目录版本下每个 field_id 的有效定义。 */
    public Map<String, FieldDef> currentDefs() {
        return defsAsOf(currentCatalogVersion(), LocalDate.now());
    }

    /**
     * 按目录版本与日期解释字段：先按版本上限过滤，再按有效区间过滤，取最大版本行。
     * 这样“字段版本改变后旧表仍按提交时语义解释”。
     */
    public Map<String, FieldDef> defsAsOf(Integer catalogVersion, LocalDate asOf) {
        int cap = catalogVersion == null ? currentCatalogVersion() : catalogVersion;
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Map<String, FieldDef> out = new LinkedHashMap<>();
        allFieldDefs().stream()
                .filter(d -> d.version() <= cap)
                .filter(d -> !d.validFrom().isAfter(date))
                .filter(d -> d.validTo() == null || !d.validTo().isBefore(date))
                .sorted(Comparator.comparingInt(FieldDef::version))
                .forEach(d -> out.put(d.fieldId(), d));
        return out;
    }

    public FieldDef resolveDef(String fieldId, Integer catalogVersion, LocalDate asOf) {
        return defsAsOf(catalogVersion, asOf).get(fieldId);
    }

    // ================= 映射契约 =================

    public List<MappingContract> allContracts() {
        List<MappingContract> contracts = jdbc.query(
                "SELECT * FROM mapping_contract ORDER BY source_field_id, version",
                (rs, i) -> new MappingContract(
                        rs.getString("contract_id"),
                        rs.getString("source_field_id"),
                        rs.getString("target_field_id"),
                        rs.getString("source_district"),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getString("evidence_note"),
                        rs.getString("reviewed_by"),
                        List.of()));
        return contracts.stream().map(c -> new MappingContract(
                c.contractId(), c.sourceFieldId(), c.targetFieldId(), c.sourceDistrict(), c.version(),
                c.status(), c.effectiveFrom(), c.effectiveTo(), c.evidenceNote(), c.reviewedBy(),
                loadValues(c.contractId()))).toList();
    }

    public List<MappingValue> loadValues(String contractId) {
        return jdbc.query(
                "SELECT source_value, target_value, transform, evidence, equivalent FROM mapping_value WHERE contract_id=?",
                (rs, i) -> new MappingValue(
                        rs.getString("source_value"),
                        rs.getString("target_value"),
                        rs.getString("transform"),
                        rs.getString("evidence"),
                        rs.getBoolean("equivalent")),
                contractId);
    }

    /**
     * 选择适用于某源代码字段在指定日期的已批准契约：
     * 状态 APPROVED + 生效区间覆盖日期 + 版本最大。旧版表单（catalog_version=1）会在编译器中
     * 按实例提交版本进一步限制契约选择，见 MappingEngine。
     */
    public MappingContract selectContract(String sourceFieldId, LocalDate asOf) {
        return allContracts().stream()
                .filter(c -> c.sourceFieldId().equals(sourceFieldId))
                .filter(c -> "APPROVED".equals(c.status()))
                .filter(c -> !c.effectiveFrom().isAfter(asOf))
                .filter(c -> c.effectiveTo() == null || !c.effectiveTo().isBefore(asOf))
                .max(Comparator.comparingInt(MappingContract::version))
                .orElse(null);
    }
}
