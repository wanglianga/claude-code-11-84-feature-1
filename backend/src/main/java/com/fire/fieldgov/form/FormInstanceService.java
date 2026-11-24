package com.fire.fieldgov.form;

import com.fire.fieldgov.model.FormInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表单实例读取（实例与取值均不可变）。 */
@Service
public class FormInstanceService {

    private final JdbcTemplate jdbc;

    public FormInstanceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<FormInstance> all() {
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT instance_id, case_id, template_id, district, catalog_version, inspect_date " +
                        "FROM form_instance ORDER BY district, case_id");
        return heads.stream().map(h -> {
            Map<String, String> values = new LinkedHashMap<>();
            String instanceId = (String) h.get("instance_id");
            jdbc.query("SELECT field_id, value_text FROM form_value WHERE instance_id=?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            values.put(rs.getString("field_id"), rs.getString("value_text")),
                    instanceId);
            return new FormInstance(
                    (String) h.get("instance_id"),
                    (String) h.get("case_id"),
                    (String) h.get("template_id"),
                    (String) h.get("district"),
                    (Integer) h.get("catalog_version"),
                    ((java.sql.Date) h.get("inspect_date")).toLocalDate(),
                    values);
        }).toList();
    }

    public FormInstance get(String instanceId) {
        return all().stream().filter(f -> f.instanceId().equals(instanceId)).findFirst().orElse(null);
    }
}
