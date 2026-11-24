package com.fire.fieldgov.reliability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层（交叉加法）测量模型：{@code y = μ + site_effect + inspector_effect + ε}。
 *
 * <p>用于把等级观测的波动分离为：
 * <ul>
 *   <li><b>场所效应</b> σ²_site：场所之间真实的隔离条件差异（泛化到新场所的信号）；</li>
 *   <li><b>检查员效应</b> σ²_inspector：检查员宽严倾向（可经校准/培训收敛）；</li>
 *   <li><b>残差</b> σ²_residual：场所×检查员交互、照片条件与随机测量噪声。</li>
 * </ul>
 *
 * <p>非平衡交叉设计下，用中心化反投影（backfitting）迭代拟合加法效应，再做描述性方差分解。
 * 该模型泛化能力好，但<b>需要足够的同场所复检/交叉评分</b>；样本不足时返回 {@code estimable=false}
 * 与具体缺口，而不是给出不稳定的效应值。
 */
public final class HierarchicalModel {

    public record Obs(double y, String site, String inspector) {}

    public record Effect(String code, double effect, double meanLevel, int n) {}

    public record Check(String key, String label, int required, int actual, boolean pass) {}

    public record Result(
            boolean estimable,
            int n,
            int siteCount,
            int inspectorCount,
            int revisitPairCount,
            double grandMean,
            List<Check> checks,
            List<String> missingRequirements,
            List<Effect> siteEffects,
            List<Effect> inspectorEffects,
            double varianceSite,
            double varianceInspector,
            double varianceResidual,
            double shareSite,
            double shareInspector,
            double shareResidual,
            double siteSignalRatio,
            String interpretation
    ) {}

    /** 充分性门槛（与 application.yml app.reliability.hierarchical 对应，亦可显式传参）。 */
    public record MinRequirements(int minObservations, int minSites, int minInspectors,
                                  int minRevisitPairs, int minInspectorSiteCoverage) {}

    private HierarchicalModel() {}

    public static Result fit(List<Obs> obs, int revisitPairCount, MinRequirements req) {
        List<Obs> data = obs == null ? List.of() : obs;

        long siteCount = data.stream().map(Obs::site).distinct().count();
        long inspCount = data.stream().map(Obs::inspector).distinct().count();
        // 每名检查员至少在多少个不同场所评分（取覆盖数的最小值，衡量交叉程度）
        Map<String, java.util.Set<String>> inspSites = new LinkedHashMap<>();
        for (Obs o : data) inspSites.computeIfAbsent(o.inspector(), k -> new java.util.HashSet<>()).add(o.site());
        int minCoverage = inspSites.values().stream().mapToInt(java.util.Set::size).min().orElse(0);

        List<Check> checks = List.of(
                new Check("observations", "有效观测数 ≥ " + req.minObservations(), req.minObservations(), data.size(),
                        data.size() >= req.minObservations()),
                new Check("sites", "不同场所数 ≥ " + req.minSites(), req.minSites(), (int) siteCount,
                        siteCount >= req.minSites()),
                new Check("inspectors", "不同检查员数 ≥ " + req.minInspectors(), req.minInspectors(), (int) inspCount,
                        inspCount >= req.minInspectors()),
                new Check("revisitPairs", "同场所复检配对 ≥ " + req.minRevisitPairs(), req.minRevisitPairs(),
                        revisitPairCount, revisitPairCount >= req.minRevisitPairs()),
                new Check("crossClassified", "每名检查员至少覆盖 " + req.minInspectorSiteCoverage() + " 个场所",
                        req.minInspectorSiteCoverage(), minCoverage, minCoverage >= req.minInspectorSiteCoverage())
        );
        List<String> missing = new ArrayList<>();
        for (Check c : checks) if (!c.pass()) missing.add(c.label() + "（当前 " + c.actual() + "）");
        boolean estimable = missing.isEmpty();

        if (!estimable) {
            return new Result(false, data.size(), (int) siteCount, (int) inspCount, revisitPairCount,
                    Double.NaN, checks, missing, List.of(), List.of(),
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    "复检/交叉评分不足，分层模型不可稳定估计；请先补足同场所复检后再用于泛化判断。");
        }

        // 固定顺序的类别索引
        List<String> sites = data.stream().map(Obs::site).distinct().toList();
        List<String> insps = data.stream().map(Obs::inspector).distinct().toList();
        Map<String, Integer> si = index(sites);
        Map<String, Integer> ii = index(insps);

        double mu = data.stream().mapToDouble(Obs::y).average().orElse(0);
        double[] a = new double[sites.size()];
        double[] b = new double[insps.size()];

        // 反投影迭代拟合（加法模型，类别效应零和约束）
        for (int iter = 0; iter < 500; iter++) {
            for (int s = 0; s < sites.size(); s++) {
                double sum = 0; int cnt = 0;
                for (Obs o : data) {
                    if (si.get(o.site()) == s) { sum += o.y() - mu - b[ii.get(o.inspector())]; cnt++; }
                }
                a[s] = cnt == 0 ? 0 : sum / cnt;
            }
            center(a);
            for (int j = 0; j < insps.size(); j++) {
                double sum = 0; int cnt = 0;
                for (Obs o : data) {
                    if (ii.get(o.inspector()) == j) { sum += o.y() - mu - a[si.get(o.site())]; cnt++; }
                }
                b[j] = cnt == 0 ? 0 : sum / cnt;
            }
            center(b);
        }

        // 残差与方差分量
        double rss = 0;
        for (Obs o : data) {
            double fit = mu + a[si.get(o.site())] + b[ii.get(o.inspector())];
            double e = o.y() - fit;
            rss += e * e;
        }
        int dof = Math.max(1, data.size() - sites.size() - insps.size() + 1);
        double vRes = rss / dof;
        double vSite = popVariance(a);
        double vInsp = popVariance(b);
        double total = vSite + vInsp + vRes;
        double shSite = total <= 0 ? Double.NaN : vSite / total;
        double shInsp = total <= 0 ? Double.NaN : vInsp / total;
        double shRes = total <= 0 ? Double.NaN : vRes / total;

        List<Effect> siteEffects = new ArrayList<>();
        for (int s = 0; s < sites.size(); s++) {
            final int idx = s;
            List<Obs> rows = data.stream().filter(o -> si.get(o.site()) == idx).toList();
            double mean = rows.stream().mapToDouble(Obs::y).average().orElse(0);
            siteEffects.add(new Effect(sites.get(s), r4(a[s]), r4(mean), rows.size()));
        }
        siteEffects.sort((e1, e2) -> Double.compare(e2.effect(), e1.effect()));

        List<Effect> inspEffects = new ArrayList<>();
        for (int j = 0; j < insps.size(); j++) {
            final int idx = j;
            List<Obs> rows = data.stream().filter(o -> ii.get(o.inspector()) == idx).toList();
            double mean = rows.stream().mapToDouble(Obs::y).average().orElse(0);
            inspEffects.add(new Effect(insps.get(j), r4(b[j]), r4(mean), rows.size()));
        }
        inspEffects.sort((e1, e2) -> Double.compare(e1.effect(), e2.effect())); // 最偏松（负）在前

        String interp = interpret(shSite, shInsp);
        return new Result(true, data.size(), sites.size(), insps.size(), revisitPairCount, r4(mu),
                checks, List.of(), siteEffects, inspEffects,
                r4(vSite), r4(vInsp), r4(vRes), r4(shSite), r4(shInsp), r4(shRes),
                r4(shSite), interp);
    }

    private static String interpret(double shareSite, double shareInsp) {
        if (Double.isNaN(shareSite)) return "方差近似为 0，等级高度一致或数据无变异。";
        if (shareSite >= 0.6) {
            return String.format("场所效应占主导（%.0f%%）：等级差异主要来自真实场所条件，模型对新场所泛化基础好；检查员效应占 %.0f%%，可经锚样校准继续收敛。",
                    shareSite * 100, shareInsp * 100);
        }
        if (shareInsp >= shareSite) {
            return String.format("检查员效应占比偏高（%.0f%%）：宽严倾向污染了风险判定，跨区排名前必须先做校准与培训。",
                    shareInsp * 100);
        }
        return String.format("场所效应 %.0f%%、检查员效应 %.0f%%、残差占比较高：建议增加同场所复检并统一照片条件后再用于排名。",
                shareSite * 100, shareInsp * 100);
    }

    private static Map<String, Integer> index(List<String> keys) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), i);
        return m;
    }

    private static void center(double[] v) {
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        for (int i = 0; i < v.length; i++) v[i] -= mean;
    }

    private static double popVariance(double[] v) {
        if (v.length == 0) return Double.NaN;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        double ss = 0;
        for (double x : v) ss += (x - mean) * (x - mean);
        return ss / v.length;
    }

    private static double r4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
