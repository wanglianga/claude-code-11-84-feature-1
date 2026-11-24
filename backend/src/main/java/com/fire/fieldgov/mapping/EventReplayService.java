package com.fire.fieldgov.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件事件流重放：按事件时间重放 INSPECTION / REVIEW_DOWNGRADE / REVIEW_RESCIND / RECTIFY，
 * 得到 asOf 时点的隐患等级与整改状态。复查降级/撤销后，原等级不得继续入统。
 */
@Service
public class EventReplayService {

    private final JdbcTemplate jdbc;

    public EventReplayService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CaseState(String caseId, String district, String hazardLevel,
                            String rectificationStatus, List<Map<String, Object>> appliedEvents) {}

    public Map<String, CaseState> replayAll(LocalDate asOf) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT case_id, district, event_type, event_time, payload FROM case_event WHERE event_time::date <= ? ORDER BY event_time, event_id",
                asOf == null ? LocalDate.now() : asOf);
        Map<String, CaseState> states = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String caseId = (String) row.get("case_id");
            String district = (String) row.get("district");
            String type = (String) row.get("event_type");
            java.sql.Timestamp ts = (java.sql.Timestamp) row.get("event_time");
            OffsetDateTime time = ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
            Map<String, Object> payload = com.fire.fieldgov.db.DbJson.toMap(row.get("payload"));
            CaseState prev = states.get(caseId);
            String hazard = prev == null ? null : prev.hazardLevel();
            String rect = prev == null ? null : prev.rectificationStatus();
            List<Map<String, Object>> events = prev == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(prev.appliedEvents());
            switch (type) {
                case "INSPECTION" -> {
                    if (payload.get("hazard_level") != null) hazard = (String) payload.get("hazard_level");
                    if (payload.get("rectification_status") != null) rect = (String) payload.get("rectification_status");
                }
                case "REVIEW_DOWNGRADE" -> {
                    hazard = (String) payload.getOrDefault("to", hazard);
                }
                case "REVIEW_RESCIND" -> hazard = "RESCINDED";
                case "RECTIFY" -> rect = (String) payload.getOrDefault("rectification_status", "RECTIFIED");
                default -> { }
            }
            events.add(Map.of("eventType", type, "eventTime", time.toString(), "payload", payload));
            states.put(caseId, new CaseState(caseId, district, hazard, rect, events));
        }
        return states;
    }

    /** 单案件重放（按时间排序保证顺序）。 */
    public CaseState replay(String caseId, LocalDate asOf) {
        return replayAll(asOf).get(caseId);
    }

    public List<Map<String, Object>> listEvents(String caseId) {
        return jdbc.queryForList(
                "SELECT event_type, event_time, payload FROM case_event WHERE case_id=? ORDER BY event_time, event_id",
                caseId)
                .stream().sorted(Comparator.comparing(r -> r.get("event_time").toString())).toList();
    }
}
