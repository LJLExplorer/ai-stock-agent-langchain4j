package com.ljl.ai.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 只接收确定性适配器的离线 Agent 评测运行器。 */
public final class AgentEvalRunner {

    public Report run(List<EvalCase> cases, Adapter adapter, int k) {
        validateCases(cases, adapter, k);
        int labelCases = 0;
        int correctLabels = 0;
        int retrievalCases = 0;
        double recallSum = 0;
        double ndcgSum = 0;
        long expectedClaims = 0;
        long citedClaims = 0;
        long expectedNumericClaims = 0;
        long consistentNumericClaims = 0;
        long latencyMillis = 0;
        int totalCalls = 0;

        for (EvalCase evalCase : cases) {
            Observation observation = adapter.evaluate(evalCase);
            validateObservation(evalCase, observation);
            if (evalCase.expectedLabel() != null && !evalCase.expectedLabel().isBlank()) {
                labelCases++;
                if (evalCase.expectedLabel().equals(observation.actualLabel())) {
                    correctLabels++;
                }
            }
            if (!evalCase.relevantIds().isEmpty()) {
                retrievalCases++;
                List<String> topK = observation.rankedIds().stream().limit(k).toList();
                recallSum += recall(topK, evalCase.relevantIds());
                ndcgSum += ndcg(topK, evalCase.relevantIds());
            }
            expectedClaims += evalCase.expectedClaimCount();
            citedClaims += observation.citedClaimCount();
            expectedNumericClaims += evalCase.expectedNumericClaimCount();
            consistentNumericClaims += observation.consistentNumericClaimCount();
            latencyMillis += observation.latencyMillis();
            totalCalls += observation.callCount();
        }

        return new Report(cases.size(), ratio(correctLabels, labelCases),
                ratio(recallSum, retrievalCases), ratio(ndcgSum, retrievalCases),
                ratio(citedClaims, expectedClaims), ratio(consistentNumericClaims, expectedNumericClaims),
                ratio(latencyMillis, cases.size()), totalCalls);
    }

    public static List<EvalCase> load(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("评测样本资源不存在");
        }
        try (input) {
            JSONArray array = JSON.parseArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return array.stream().map(value -> toCase((JSONObject) value)).toList();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("评测样本解析失败", exception);
        }
    }

    private static EvalCase toCase(JSONObject json) {
        return new EvalCase(json.getString("caseId"), Category.valueOf(json.getString("category")),
                json.getString("input"), json.getString("expectedLabel"),
                list(json, "relevantIds"), integer(json, "expectedClaimCount"),
                integer(json, "expectedNumericClaimCount"));
    }

    private static List<String> list(JSONObject json, String key) {
        List<String> values = json.getList(key, String.class);
        return values == null ? List.of() : List.copyOf(values);
    }

    private static int integer(JSONObject json, String key) {
        Integer value = json.getInteger(key);
        return value == null ? 0 : value;
    }

    private void validateCases(List<EvalCase> cases, Adapter adapter, int k) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("至少一个评测样本");
        }
        if (adapter == null || k <= 0) {
            throw new IllegalArgumentException("适配器不能为空且 k 必须大于 0");
        }
        Set<String> ids = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (evalCase == null || evalCase.caseId() == null || evalCase.caseId().isBlank()
                    || evalCase.category() == null || evalCase.input() == null || evalCase.input().isBlank()) {
                throw new IllegalArgumentException("评测样本字段不完整");
            }
            if (!ids.add(evalCase.caseId())) {
                throw new IllegalArgumentException("重复 caseId: " + evalCase.caseId());
            }
            if (evalCase.expectedClaimCount() < 0 || evalCase.expectedNumericClaimCount() < 0
                    || evalCase.expectedNumericClaimCount() > evalCase.expectedClaimCount()) {
                throw new IllegalArgumentException("非法期望值: " + evalCase.caseId());
            }
            if (evalCase.relevantIds().size() != new LinkedHashSet<>(evalCase.relevantIds()).size()) {
                throw new IllegalArgumentException("非法期望值，relevantIds 重复: " + evalCase.caseId());
            }
        }
    }

    private void validateObservation(EvalCase evalCase, Observation observation) {
        if (observation == null || observation.rankedIds() == null
                || observation.claimCount() != evalCase.expectedClaimCount()
                || observation.numericClaimCount() != evalCase.expectedNumericClaimCount()
                || observation.citedClaimCount() < 0
                || observation.citedClaimCount() > observation.claimCount()
                || observation.consistentNumericClaimCount() < 0
                || observation.consistentNumericClaimCount() > observation.numericClaimCount()
                || observation.latencyMillis() < 0 || observation.callCount() < 0) {
            throw new IllegalArgumentException("非法观测值: " + evalCase.caseId());
        }
    }

    private double recall(List<String> ranked, List<String> relevant) {
        Set<String> matched = new HashSet<>();
        ranked.stream().filter(relevant::contains).forEach(matched::add);
        return ratio(matched.size(), relevant.size());
    }

    private double ndcg(List<String> ranked, List<String> relevant) {
        Set<String> seen = new HashSet<>();
        double dcg = 0;
        for (int index = 0; index < ranked.size(); index++) {
            String id = ranked.get(index);
            if (relevant.contains(id) && seen.add(id)) {
                dcg += 1.0 / log2(index + 2);
            }
        }
        double ideal = 0;
        for (int index = 0; index < Math.min(ranked.size(), relevant.size()); index++) {
            ideal += 1.0 / log2(index + 2);
        }
        return ideal == 0 ? 0 : dcg / ideal;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private double ratio(double numerator, long denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    public enum Category {
        PLANNER,
        TOPIC_ROUTING,
        RAG,
        EVIDENCE,
        RECOVERY
    }

    public record EvalCase(String caseId, Category category, String input, String expectedLabel,
                           List<String> relevantIds, int expectedClaimCount, int expectedNumericClaimCount) {
        public EvalCase {
            relevantIds = relevantIds == null ? List.of() : List.copyOf(relevantIds);
        }
    }

    public record Observation(String actualLabel, List<String> rankedIds,
                              int claimCount, int citedClaimCount,
                              int numericClaimCount, int consistentNumericClaimCount,
                              long latencyMillis, int callCount) {
        public Observation {
            rankedIds = rankedIds == null ? List.of() : List.copyOf(rankedIds);
        }
    }

    @FunctionalInterface
    public interface Adapter {
        Observation evaluate(EvalCase evalCase);
    }

    public record Report(int caseCount, double accuracy, double recallAtK, double ndcgAtK,
                         double citationCoverage, double numericConsistency,
                         double averageLatencyMillis, int totalCalls) {
        public String toStableJson() {
            return "{\"caseCount\":" + caseCount
                    + ",\"accuracy\":" + accuracy
                    + ",\"recallAtK\":" + recallAtK
                    + ",\"ndcgAtK\":" + ndcgAtK
                    + ",\"citationCoverage\":" + citationCoverage
                    + ",\"numericConsistency\":" + numericConsistency
                    + ",\"averageLatencyMillis\":" + averageLatencyMillis
                    + ",\"totalCalls\":" + totalCalls + "}";
        }
    }
}
