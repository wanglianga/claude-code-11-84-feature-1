package com.fire.fieldgov.web;

import com.fire.fieldgov.reliability.HierarchicalModel;
import com.fire.fieldgov.reliability.ReliabilityGate;
import com.fire.fieldgov.reliability.ReliabilityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 锂电隔离等级“测量一致性”工作台：
 * 配对账 / 观察条件 / 一致性估计 / 闸门与跨区排名 / 分层模型 / 锚样校准 / 培训反馈。
 * 所有接口都在 /api/** 认证拦截之后；写操作在 Service 内再做角色校验。
 */
@RestController
@RequestMapping("/api/reliability")
public class ReliabilityController {

    private final ReliabilityService svc;

    public ReliabilityController(ReliabilityService svc) { this.svc = svc; }

    @GetMapping("/meta")
    public Map<String, Object> meta() { return svc.meta(); }

    @GetMapping("/observations")
    public List<Map<String, Object>> observations(@RequestParam(required = false) String fieldId,
                                                 @RequestParam(required = false) String mode) {
        return svc.observations(fieldId, mode);
    }

    @GetMapping("/pairs")
    public List<Map<String, Object>> pairs(@RequestParam(required = false) String fieldId,
                                           @RequestParam(required = false) String pairType) {
        return svc.pairs(fieldId, pairType);
    }

    @PostMapping("/pairs")
    public Map<String, Object> createPair(@RequestBody Map<String, Object> body) {
        return svc.createPair(body);
    }

    @GetMapping("/consistency")
    public Map<String, Object> consistency(@RequestParam(required = false) String fieldId) {
        return svc.consistency(fieldId);
    }

    @GetMapping("/gate")
    public ReliabilityGate.Verdict gate(@RequestParam(required = false) String fieldId) {
        return svc.gate(fieldId);
    }

    /** 闸门保护的跨区排名：一致性不足时返回 409 + gate，且不产出任何排名行。 */
    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestParam(required = false) String fieldId) {
        return svc.ranking(fieldId);
    }

    @GetMapping("/hierarchical")
    public HierarchicalModel.Result hierarchical(@RequestParam(required = false) String fieldId) {
        return svc.hierarchical(fieldId);
    }

    @GetMapping("/anchors")
    public List<Map<String, Object>> anchors() { return svc.anchors(); }

    @GetMapping("/anchors/calibration")
    public Map<String, Object> calibration(@RequestParam(required = false) String inspectorCode) {
        return svc.calibration(inspectorCode);
    }

    /** 单类锚样校准；请求跨类合并（永久 + 临时施工）时返回 422。 */
    @PostMapping("/anchors/calibration/evaluate")
    public Map<String, Object> evaluateAnchorClass(@RequestBody(required = false) Map<String, Object> body) {
        return svc.evaluateAnchorClass(body);
    }

    @PostMapping("/anchors/scores")
    public Map<String, Object> submitScore(@RequestBody Map<String, Object> body) {
        return svc.submitScore(body);
    }

    @GetMapping("/feedback")
    public List<Map<String, Object>> feedback() { return svc.feedbackList(); }

    @PostMapping("/feedback/generate")
    public List<Map<String, Object>> generateFeedback() { return svc.generateFeedback(); }

    @PostMapping("/feedback/{feedbackId}/status")
    public Map<String, Object> feedbackStatus(@PathVariable String feedbackId,
                                             @RequestBody Map<String, Object> body) {
        return svc.updateFeedbackStatus(feedbackId, body);
    }
}
