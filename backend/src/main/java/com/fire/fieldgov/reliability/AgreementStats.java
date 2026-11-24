package com.fire.fieldgov.reliability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 等级一致性统计（纯函数、无 Spring 依赖，便于单测）。
 *
 * <p>支持 1..K 的有序等级，提供：
 * <ul>
 *   <li>简单一致率（exact agreement）；</li>
 *   <li>线性 / 二次权重的<b>加权一致率</b>（部分分歧按距离打折）；</li>
 *   <li>机会校正的加权 Kappa（线性 / 二次权重）；</li>
 *   <li><b>系统性偏差</b>：有符号等级差均值（正=B 比 A 更严/更高），及偏差方向；</li>
 *   <li>固定随机种子的成对自助法（paired bootstrap）95% <b>区间</b>，结果可复现。</li>
 * </ul>
 */
public final class AgreementStats {

    /** 一对有序等级（x=基准侧，y=比较侧），取值 1..K。 */
    public record Pair(int x, int y) {}

    public record Ci(double lower, double upper) {}

    public record Result(
            int n,
            int categories,
            double exactAgreement,
            double weightedAgreementLinear,
            double weightedAgreementQuadratic,
            Double kappaLinear,
            Double kappaQuadratic,
            Ci exactAgreementCi95,
            Ci weightedAgreementQuadraticCi95,
            Ci kappaQuadraticCi95,
            double meanSignedDiff,
            double sdSignedDiff,
            Ci biasCi95,
            boolean biasSignificant,
            String biasDirection,
            Map<String, Integer> signedDiffCounts,
            Map<String, Integer> absDiffCounts
    ) {}

    /** 自助重采样次数；固定种子保证 E2E/评审看到的区间稳定。 */
    public static final int BOOTSTRAP_REPS = 2000;
    static final long BOOTSTRAP_SEED = 20261124L;

    private AgreementStats() {}

    /** 用默认 4 个等级计算完整指标与区间。 */
    public static Result analyze(List<Pair> pairs) {
        return analyze(pairs, 4);
    }

    public static Result analyze(List<Pair> pairs, int k) {
        List<Pair> p = pairs == null ? List.of() : pairs;
        int n = p.size();

        Map<String, Integer> signedCounts = new LinkedHashMap<>();
        Map<String, Integer> absCounts = new LinkedHashMap<>();
        for (int d = -(k - 1); d <= k - 1; d++) signedCounts.put(String.valueOf(d), 0);
        for (int d = 0; d <= k - 1; d++) absCounts.put(String.valueOf(d), 0);

        if (n == 0) {
            return new Result(0, k, Double.NaN, Double.NaN, Double.NaN, null, null,
                    null, null, null, Double.NaN, Double.NaN, null,
                    false, "INSUFFICIENT_DATA", signedCounts, absCounts);
        }

        int exact = 0;
        double sumD = 0, sumD2 = 0;
        for (Pair pr : p) {
            int d = pr.y() - pr.x();
            if (pr.x() == pr.y()) exact++;
            sumD += d;
            sumD2 += (double) d * d;
            signedCounts.merge(String.valueOf(d), 1, Integer::sum);
            absCounts.merge(String.valueOf(Math.abs(d)), 1, Integer::sum);
        }
        double meanD = sumD / n;
        double variance = Math.max(0, sumD2 / n - meanD * meanD);
        double sd = Math.sqrt(variance);

        double poLin = weightedAgreement(p, k, 1);
        double poQuad = weightedAgreement(p, k, 2);
        Double kLin = weightedKappa(p, k, 1);
        Double kQuad = weightedKappa(p, k, 2);
        double exactRate = (double) exact / n;

        Ci exactCi = bootstrap(p, k, (s, kk) -> exactAgreement(s), false);
        Ci poQuadCi = bootstrap(p, k, (s, kk) -> weightedAgreement(s, kk, 2), false);
        Ci kQuadCi = bootstrap(p, k, AgreementStats::kappaQuadraticFn, true);
        Ci biasCi = bootstrap(p, k, (s, kk) -> meanSignedDiff(s), false);

        // 自助 95% 区间不包含 0 才判定为存在系统性偏差
        boolean biasSig = biasCi != null && (biasCi.lower() > 0 || biasCi.upper() < 0);
        String direction;
        if (!biasSig) direction = biasCi == null ? "INSUFFICIENT_DATA" : "NO_SIGNIFICANT_BIAS";
        else if (meanD > 0) direction = "UPWARD";   // 比较侧系统性给级更高/更严
        else if (meanD < 0) direction = "DOWNWARD"; // 比较侧系统性给级更低/偏松
        else direction = "NO_SIGNIFICANT_BIAS";

        return new Result(n, k, round4(exactRate), round4(poLin), round4(poQuad),
                kLin == null ? null : round4(kLin),
                kQuad == null ? null : round4(kQuad),
                round(exactCi), round(poQuadCi), round(kQuadCi),
                round4(meanD), round4(sd), round(biasCi),
                biasSig, direction, signedCounts, absCounts);
    }

    // ---------- 核心公式 ----------

    /**
     * 加权一致率：weight(|i-j|) = 1 - (|i-j|/(K-1))^power。
     * power=1 线性权重；power=2 二次权重。
     */
    public static double weightedAgreement(List<Pair> pairs, int k, int power) {
        if (pairs.isEmpty()) return Double.NaN;
        double sum = 0;
        for (Pair p : pairs) sum += weight(p.x(), p.y(), k, power);
        return sum / pairs.size();
    }

    public static Double weightedKappa(List<Pair> pairs, int k, int power) {
        int n = pairs.size();
        if (n == 0) return null;
        double po = weightedAgreement(pairs, k, power);
        double[] mx = new double[k];
        double[] my = new double[k];
        for (Pair p : pairs) {
            mx[idx(p.x(), k)]++;
            my[idx(p.y(), k)]++;
        }
        double pe = 0;
        for (int i = 1; i <= k; i++) {
            for (int j = 1; j <= k; j++) {
                pe += weight(i, j, k, power) * (mx[idx(i, k)] / n) * (my[idx(j, k)] / n);
            }
        }
        if (Math.abs(1 - pe) < 1e-12) {
            // 边际完全退化为单一等级：若观察也完全一致，则完全一致，否则不可估计。
            return Math.abs(po - 1) < 1e-12 ? 1.0 : null;
        }
        return (po - pe) / (1 - pe);
    }

    private static Double kappaQuadraticFn(List<Pair> s, int k) {
        return weightedKappa(s, k, 2);
    }

    private static double exactAgreement(List<Pair> s) {
        long e = s.stream().filter(p -> p.x() == p.y()).count();
        return (double) e / s.size();
    }

    private static double meanSignedDiff(List<Pair> s) {
        return s.stream().mapToInt(p -> p.y() - p.x()).average().orElse(Double.NaN);
    }

    private static double weight(int a, int b, int k, int power) {
        if (k <= 1) return 1.0;
        double norm = (double) Math.abs(a - b) / (k - 1);
        return 1.0 - Math.pow(norm, power);
    }

    private static int idx(int level, int k) {
        return Math.max(0, Math.min(k - 1, level - 1));
    }

    // ---------- 成对自助区间 ----------

    @FunctionalInterface
    private interface StatFn {
        Double apply(List<Pair> sample, int k);
    }

    /**
     * 成对自助 95% 百分位区间。固定种子、有放回重采样配对（不拆散两侧）。
     * 若超过一半重采样无法估计统计量（如 Kappa 边际退化），返回 null。
     */
    private static Ci bootstrap(List<Pair> pairs, int k, StatFn fn, boolean nullable) {
        int n = pairs.size();
        if (n < 2) return null;
        Random rnd = new Random(BOOTSTRAP_SEED);
        List<Double> vals = new ArrayList<>(BOOTSTRAP_REPS);
        for (int r = 0; r < BOOTSTRAP_REPS; r++) {
            List<Pair> sample = new ArrayList<>(n);
            for (int i = 0; i < n; i++) sample.add(pairs.get(rnd.nextInt(n)));
            Double v;
            try {
                v = fn.apply(sample, k);
            } catch (RuntimeException ex) {
                v = null;
            }
            if (v != null && !v.isNaN() && !v.isInfinite()) vals.add(v);
        }
        if (vals.size() < BOOTSTRAP_REPS / 2) return null;
        vals.sort(Double::compare);
        double lo = percentile(vals, 0.025);
        double hi = percentile(vals, 0.975);
        return new Ci(lo, hi);
    }

    private static double percentile(List<Double> sorted, double p) {
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double frac = pos - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Ci round(Ci ci) {
        if (ci == null) return null;
        return new Ci(round4(ci.lower()), round4(ci.upper()));
    }
}
