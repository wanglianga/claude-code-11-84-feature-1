package com.fire.fieldgov.reliability;

/**
 * 一致性闸门未通过：字段已登记，但其等级一致性不足以驱动跨区排名。
 * 映射为 HTTP 409，并携带完整闸门结论，供前端渲染“禁止排名”面板。
 */
public class ReliabilityGateException extends RuntimeException {
    private final transient ReliabilityGate.Verdict verdict;

    public ReliabilityGateException(String message, ReliabilityGate.Verdict verdict) {
        super(message);
        this.verdict = verdict;
    }

    public ReliabilityGate.Verdict verdict() {
        return verdict;
    }
}
