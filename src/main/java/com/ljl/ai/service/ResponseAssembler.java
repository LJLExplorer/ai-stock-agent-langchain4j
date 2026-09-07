package com.ljl.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.ToolInvocation;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.FinancialFact;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.TaskStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 组装本轮工具调用、知识来源和对话响应。 */
@Slf4j
@Service
public class ResponseAssembler {
    private static final Map<String, String> TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("getRealtimeQuote", "查询实时行情"),
            Map.entry("analyzeTechnicalIndicators", "分析技术指标"),
            Map.entry("analyzeFinancialReport", "分析财务报告"),
            Map.entry("searchStockNewsAndAnnouncements", "搜索新闻与公告"),
            Map.entry("predictStockTrend", "预测股票趋势"),
            Map.entry("compareStocks", "比较多只股票"),
            Map.entry("analyzePortfolio", "分析投资组合")
    );

    @Resource
    private RedisChatMemoryProvider chatMemoryProvider;

    public record ResponseContent(String answer, List<KnowledgeSource> knowledgeSources,
                                  List<ToolInvocation> toolInvocations) {}

    /** 汇总本轮新增模型工具调用与工作流任务，并合并去重知识库、网页和结构化证据来源。 */
    public ResponseContent assemble(String modelMemoryId, Set<String> previousIds,
                                    List<KnowledgeSource> ragSources,
                                    AgentExecutionService.AgentResult result) {
        List<ToolInvocation> workflowInvocations = workflowToolInvocations(result.execution());
        List<ToolInvocation> invocations = new ArrayList<>(collectToolInvocations(modelMemoryId, previousIds));
        invocations.addAll(workflowInvocations);
        List<KnowledgeSource> sources = mergeKnowledgeSources(ragSources, extractWebSources(workflowInvocations));
        sources = mergeKnowledgeSources(sources, result.execution() == null ? List.of()
                : extractEvidenceSources(result.execution().getEvidencePack()));
        return new ResponseContent(result.answer(), sources, invocations);
    }

    public ChatResponse success(String sessionId, String messageId, ResponseContent content) {
        return ChatResponse.builder()
                .sessionId(sessionId).messageId(messageId).content(content.answer())
                .responseTime(LocalDateTime.now()).knowledgeSources(content.knowledgeSources())
                .toolInvocations(content.toolInvocations()).success(true).build();
    }

    /** 从成功的新闻工具结果提取网页来源，兼容数组及 data 包装格式，跳过无法解析的结果。 */
    static List<KnowledgeSource> extractWebSources(List<ToolInvocation> toolInvocations) {
        List<KnowledgeSource> sources = new ArrayList<>();
        if (toolInvocations == null) {
            return sources;
        }
        for (ToolInvocation invocation : toolInvocations) {
            if (!"searchStockNewsAndAnnouncements".equals(invocation.getFunctionName())
                    || !Boolean.TRUE.equals(invocation.getSuccess())
                    || StringUtils.isBlank(invocation.getResult())) {
                continue;
            }
            try {
                Object parsed = JSON.parse(invocation.getResult());
                JSONArray items = parsed instanceof JSONArray array ? array
                        : parsed instanceof JSONObject result ? result.getJSONArray("data") : null;
                if (items == null) {
                    continue;
                }
                for (int i = 0; i < items.size(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item == null || StringUtils.isBlank(item.getString("url"))) {
                        continue;
                    }
                    String url = item.getString("url");
                    String title = StringUtils.defaultIfBlank(item.getString("title"), url);
                    String source = item.getString("source");
                    String publishedAt = item.getString("publishedAt");
                    String location = String.join(" · ",
                            List.of(source == null ? "网页" : source,
                                    publishedAt == null ? "" : publishedAt)).replaceAll("^( · )|( · )$", "");
                    sources.add(KnowledgeSource.builder()
                            .documentId(url)
                            .documentTitle(title)
                            .documentType("WEB")
                            .contentSnippet(item.getString("summary"))
                            .documentUrl(url)
                            .location(location)
                            .build());
                }
            } catch (Exception e) {
                log.debug("网页来源解析失败，跳过展示: {}", e.getMessage());
            }
        }
        return sources;
    }

    /** 将证据转为可展示来源，过滤已拒绝项；时间未知的来源仍可展示，但不等同于可信事实。 */
    static List<KnowledgeSource> extractEvidenceSources(EvidencePack evidencePack) {
        if (evidencePack == null || evidencePack.evidenceByType() == null) {
            return List.of();
        }
        return evidencePack.evidenceByType().values().stream()
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .filter(fact -> fact.temporalStatus() != FinancialFact.TemporalStatus.REJECTED)
                .map(fact -> KnowledgeSource.builder()
                        .documentId(fact.evidenceId())
                        .documentTitle(StringUtils.defaultIfBlank(fact.sourceName(), "数据证据"))
                        .documentType("EVIDENCE")
                        .contentSnippet(evidenceSnippet(fact))
                        .documentUrl(fact.sourceUrl())
                        .location(fact.evidenceType() + (fact.asOf() == null ? "" : " · " + fact.asOf()))
                        .build())
                .toList();
    }

    private static String evidenceSnippet(FinancialFact fact) {
        String unit = StringUtils.isBlank(fact.unit()) ? "" : " " + fact.unit();
        return fact.metric() + "：" + StringUtils.defaultString(fact.value()) + unit;
    }

    static List<ToolInvocation> workflowToolInvocations(ExecutionState state) {
        if (state == null || state.getTasks() == null) {
            return Collections.emptyList();
        }
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        return state.getTasks().stream().map(task -> {
            boolean success = task.getStatus() == TaskStatus.COMPLETED;
            Long executionTime = task.getStartedAt() == null || task.getCompletedAt() == null ? null
                    : Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis();
            return ToolInvocation.builder()
                    .toolName(TOOL_DISPLAY_NAMES.getOrDefault(task.getTaskType().toolName(), task.getTaskType().toolName()))
                    .functionName(task.getTaskType().toolName())
                    .parameters("symbol=" + StringUtils.defaultString(symbol))
                    .result(task.getResult())
                    .success(success)
                    .errorMessage(success ? null : task.getErrorMessage())
                    .executionTime(executionTime)
                    .invokeTime(task.getStartedAt())
                    .build();
        }).toList();
    }

    /** 按证据 ID 或来源链接去重，保持原有顺序，并优先保留已收集的来源信息。 */
    private List<KnowledgeSource> mergeKnowledgeSources(List<KnowledgeSource> current,
                                                        List<KnowledgeSource> additional) {
        if ((current == null || current.isEmpty()) && (additional == null || additional.isEmpty())) {
            return current;
        }
        Map<String, KnowledgeSource> merged = new java.util.LinkedHashMap<>();
        if (current != null) {
            current.forEach(source -> merged.put(sourceKey(source), source));
        }
        if (additional != null) {
            additional.forEach(source -> merged.putIfAbsent(sourceKey(source), source));
        }
        return new ArrayList<>(merged.values());
    }

    private String sourceKey(KnowledgeSource source) {
        if ("EVIDENCE".equals(source.getDocumentType())) {
            return StringUtils.defaultString(source.getDocumentId());
        }
        return StringUtils.defaultIfBlank(source.getDocumentUrl(), source.getDocumentId());
    }

    /** 在调用助手前记录已有工具请求 ID，供响应组装时排除历史轮次的调用。 */
    public Set<String> collectToolInvocationIds(String memoryId) {
        Set<String> ids = new HashSet<>();
        var chatMemory = chatMemoryProvider.get(memoryId);
        if (chatMemory == null) {
            log.debug("ChatMemory不存在或未初始化, memoryId: {}", memoryId);
            return ids;
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = chatMemory.messages();
        if (messages == null) {
            log.debug("会话消息列表为空, memoryId: {}", memoryId);
            return ids;
        }
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests =
                        aiMessage.toolExecutionRequests();
                if (requests != null) {
                    requests.forEach(request -> ids.add(request.id()));
                }
            }
        }
        return ids;
    }

    /** 按工具请求 ID 配对请求和结果，只收集本轮新增项；尚无结果的请求保留待执行状态。 */
    private List<ToolInvocation> collectToolInvocations(String memoryId, Set<String> previousIds) {
        var chatMemory = chatMemoryProvider.get(memoryId);
        if (chatMemory == null) {
            log.debug("ChatMemory不存在或未初始化, memoryId: {}", memoryId);
            return Collections.emptyList();
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = chatMemory.messages();
        if (messages == null) {
            log.debug("会话消息列表为空, memoryId: {}", memoryId);
            return Collections.emptyList();
        }
        Map<String, ToolInvocation> invocations = new HashMap<>();

        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                if (requests == null) {
                    continue;
                }
                for (dev.langchain4j.agent.tool.ToolExecutionRequest request : requests) {
                    if (previousIds.contains(request.id())) {
                        continue;
                    }
                    String functionName = request.name();
                    invocations.put(request.id(), ToolInvocation.builder()
                            .toolName(toDisplayToolName(functionName))
                            .functionName(functionName)
                            .parameters(request.arguments())
                            .success(false)
                            .errorMessage("待执行")
                            .executionTime(0L)
                            .invokeTime(LocalDateTime.now())
                            .build());
                }
            } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage resultMessage) {
                if (previousIds.contains(resultMessage.id())) {
                    continue;
                }
                ToolInvocation invocation = invocations.get(resultMessage.id());
                if (invocation == null) {
                    String functionName = resultMessage.toolName();
                    invocation = ToolInvocation.builder()
                            .toolName(toDisplayToolName(functionName))
                            .functionName(functionName)
                            .invokeTime(LocalDateTime.now())
                            .build();
                }
                applyToolResult(invocation, resultMessage.text());
                invocations.put(resultMessage.id(), invocation);
            }
        }
        return new ArrayList<>(invocations.values());
    }

    private void applyToolResult(ToolInvocation invocation, String text) {
        invocation.setResult(text);
        try {
            var result = JSON.parseObject(text);
            if (result.containsKey("success")) {
                invocation.setSuccess(result.getBooleanValue("success"));
                invocation.setErrorMessage(result.getString("errorMessage"));
                if (result.containsKey("costTime")) {
                    invocation.setExecutionTime(result.getLongValue("costTime"));
                }
                return;
            }
        } catch (Exception ignored) {
            log.debug("工具结果不是标准 ToolResult JSON，按兼容文本处理");
        }
        invocation.setSuccess(true);
    }

    private String toDisplayToolName(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "工具调用";
        }
        return TOOL_DISPLAY_NAMES.getOrDefault(functionName, "执行工具");
    }
}
