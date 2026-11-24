package com.fire.fieldgov.reliability;

import java.util.ArrayList;
import java.util.List;

/**
 * 一致性闸门：决定某个锂电隔离等级字段能否驱动<b>跨区排名</b>。
 *
 * <p>核心治理规则：<b>一致性不足时，字段仍可登记入目录、可逐件填报，
 * 但不得用于跨区排名/考核</b>，避免把检查员宽严或照片条件差异误当成辖区风险差异。
 */
public final class ReliabilityGate {

    /** 闸门阈值（可由 application.yml 覆盖）。 */
    public record Thresholds(
            int minPairs,
            double minWeightedAgreement,
            double minWeightedKappa,
            double minKappaCiLower,
            double maxAbsBias
    ) {}

    public record Criterion(String key, String label, boolean pass, String detail) {}

    public record Verdict(
            String fieldId,
            boolean rankingEligible,
            String status,                 // ELIGIBLE / NOT_ELIGIBLE / INSUFFICIENT_DATA
            List<Criterion> criteria,
            List<String> blockingReasons,
            String decision
    ) {}

    private ReliabilityGate() {}

    public static Verdict evaluate(String fieldId, AgreementStats.Result r, Thresholds t) {
        List<Criterion> cs = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        boolean enough = r.n() >= t.minPairs();
        cs.add(new Criterion("sampleSize", "配对样本数 ≥ " + t.minPairs(), enough,
                "实际 " + r.n() + " 对"));
        if (!enough) blocks.add("复检配对不足（" + r.n() + "/" + t.minPairs() + "），一致性尚无法稳定评价");

        boolean wa = !Double.isNaN(r.weightedAgreementQuadratic())
                && r.weightedAgreementQuadratic() >= t.minWeightedAgreement();
        cs.add(new Criterion("weightedAgreement",
                "二次加权一致率 ≥ " + trim(t.minWeightedAgreement()), wa,
                "实际 " + trim(r.weightedAgreementQuadratic())));
        if (!wa) blocks.add("加权一致率 " + trim(r.weightedAgreementQuadratic())
                + " 低于 " + trim(t.minWeightedAgreement()));

        boolean kappa = r.kappaQuadratic() != null && r.kappaQuadratic() >= t.minWeightedKappa();
        cs.add(new Criterion("weightedKappa",
                "机会校正加权 Kappa ≥ " + trim(t.minWeightedKappa()), kappa,
                "实际 " + (r.kappaQuadratic() == null ? "不可估计" : trim(r.kappaQuadratic()))));
        if (!kappa) blocks.add("加权 Kappa "
                + (r.kappaQuadratic() == null ? "不可估计" : trim(r.kappaQuadratic()))
                + " 低于 " + trim(t.minWeightedKappa()) + "（剔除机会一致后重复性不足）");

        boolean ci = r.kappaQuadraticCi95() != null
                && r.kappaQuadraticCi95().lower() >= t.minKappaCiLower();
        cs.add(new Criterion("kappaCiLower",
                "Kappa 95% 区间下界 ≥ " + trim(t.minKappaCiLower()), ci,
                r.kappaQuadraticCi95() == null ? "区间不可估计"
                        : "区间 [" + trim(r.kappaQuadraticCi95().lower()) + ", "
                          + trim(r.kappaQuadraticCi95().upper()) + "]"));
        if (!ci) blocks.add("Kappa 95% 区间下界不足 " + trim(t.minKappaCiLower())
                + "，不能排除“一致性仅属偶然/样本波动”");

        boolean bias = !Double.isNaN(r.meanSignedDiff())
                && Math.abs(r.meanSignedDiff()) <= t.maxAbsBias();
        cs.add(new Criterion("systematicBias",
                "平均系统性偏差 |Δ| ≤ " + trim(t.maxAbsBias()) + " 级", bias,
                "实际 Δ=" + trim(r.meanSignedDiff()) + " 级（" + r.biasDirection() + "）"));
        if (!bias) blocks.add("存在超过 " + trim(t.maxAbsBias()) + " 级的系统性偏差（Δ="
                + trim(r.meanSignedDiff()) + "），辖区均值不可直接横向比较");

        boolean eligible = blocks.isEmpty();
        String status = r.n() == 0 ? "INSUFFICIENT_DATA" : eligible ? "ELIGIBLE" : "NOT_ELIGIBLE";
        String decision = eligible
                ? "一致性达到闸门要求：该字段可用于跨区排名，但仍应持续用锚样校准并保留区间。"
                : "一致性不足：字段可继续登记、填报与治理，但本轮不得驱动跨区排名/考核；请先校准检查员、统一照片条件并补足复检。";
        return new Verdict(fieldId, eligible, status, cs, blocks, decision);
    }

    private static String trim(double v) {
        if (Double.isNaN(v)) return "NA";
        String s = String.format("%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
