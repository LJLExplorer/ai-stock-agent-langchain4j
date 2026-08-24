package com.ljl.ai.agent.service;

import com.ljl.ai.agent.agent.StockAnalysisAssistant;
import com.ljl.ai.agent.memoery.ChatMemoryService;
import com.ljl.ai.agent.memoery.MongoChatMemoryProvider;
import com.ljl.ai.agent.memoery.ShortTermSummaryService;
import com.ljl.ai.agent.model.dto.ChatRequest;
import com.ljl.ai.agent.model.dto.ChatResponse;
import com.ljl.ai.agent.model.entity.ChatMessage;
import com.ljl.ai.agent.model.entity.ChatSession;
import com.ljl.ai.agent.model.entity.KnowledgeSource;
import com.ljl.ai.agent.model.entity.ToolInvocation;
import com.ljl.ai.agent.rag.RagPipelineService;
import com.ljl.ai.agent.rag.RagResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 对话服务 - 核心业务逻辑
 * 消息的持久化由 LangChain4j 通过 ChatMemoryStore 自动处理
 */
@Slf4j
@Service
public class ChatService {

    private static final Map<String, String> TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("getRealtimeQuote", "查询实时行情"),
            Map.entry("analyzeTechnicalIndicators", "分析技术指标"),
            Map.entry("analyzeFinancialReport", "分析财务报告"),
            Map.entry("searchStockNewsAndAnnouncements", "搜索新闻与公告"),
            Map.entry("predictStockTrend", "预测股票趋势"),
            Map.entry("compareStocks", "比较多只股票"),
            Map.entry("analyzePortfolio", "分析投资组合"),
            Map.entry("screenStocks", "筛选股票")
    );

    @Resource
    private StockAnalysisAssistant stockAnalysisAssistant;

    @Resource
    @Qualifier("stockAnalysisAssistantWithoutTools")
    private StockAnalysisAssistant stockAnalysisAssistantWithoutTools;

    @Resource
    private ChatMemoryService chatMemoryService;

    @Resource
    private MongoChatMemoryProvider chatMemoryProvider;

    @Resource
    private RagPipelineService ragPipelineService;

    @Resource
    private ShortTermSummaryService shortTermSummaryService;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private RagTraceService ragTraceService;

    public ChatResponse chat(ChatRequest request) {
        log.info("处理对话请求, userId: {}, sessionId: {}", request.getUserId(), request.getSessionId());
        String activeSessionId = request.getSessionId();

        try {
            // 1. 获取或创建会话
            ChatSession session = chatMemoryService.getOrCreateSession(
                    request.getSessionId(),
                    request.getUserId(),
                    request.getOrderId()
            );

            String sessionId = session.getSessionId();
            activeSessionId = sessionId;
            String memoryId = memoryId(request.getUserId(), sessionId);
            String userMessage = request.getMessage();
            Set<String> previousToolInvocationIds = collectToolInvocationIds(memoryId);

            // 2. 执行RAG检索（如果启用）
            List<KnowledgeSource> knowledgeSources = null;
            String ragContext = null;
            com.ljl.ai.agent.model.entity.RagTrace ragTrace = null;

            if (Boolean.TRUE.equals(request.getEnableRag())) {
                RagResult ragResult = ragPipelineService.executeRag(userMessage);
                knowledgeSources = ragResult.getKnowledgeSources();
                ragTrace = ragPipelineService.buildTrace(request.getUserId(), sessionId, userMessage, ragResult);

                if (!ragResult.getRetrievalResults().isEmpty()) {
                    // 获取RAG上下文，作为系统消息的一部分
                    ragContext = ragResult.getAugmentedContext();
                }
            }

            // 3. 调用智能体生成回复
            // ChatMemory 使用 MongoDB 持久化（chat_memory_records 集合）

            // 兼容模板请求字段：此处将 orderId 作为当前分析标的代码传递。
            if (StringUtils.isNotBlank(request.getOrderId())) {
                userMessage = userMessage + "\n当前用户正在咨询股票：" + request.getOrderId();
            }

            String memoryContext = buildMemoryContext(request.getUserId(), sessionId, userMessage);
            if (StringUtils.isNotBlank(memoryContext)) {
                userMessage = memoryContext + "\n\n当前问题：" + userMessage;
            }

            String aiResponse;
            StockAnalysisAssistant assistant = Boolean.TRUE.equals(request.getEnableTools())
                    ? stockAnalysisAssistant : stockAnalysisAssistantWithoutTools;
            if (ragContext != null) {
                // 有RAG上下文
                aiResponse = assistant.chatWithRag(memoryId, userMessage, ragContext);
            } else {
                // 普通对话
                aiResponse = assistant.chat(memoryId, userMessage);
            }

            List<ToolInvocation> toolInvocations = collectToolInvocations(memoryId, previousToolInvocationIds);

            // 4. 保存用户消息和AI回复到业务层（chat_messages 集合，用于前端展示）
            chatMemoryService.saveUserMessage(sessionId, userMessage);
            ChatMessage assistantMessage = chatMemoryService.saveAssistantMessage(sessionId, aiResponse);
            shortTermSummaryService.refresh(memoryId);
            if (ragTrace != null) {
                ragTrace.setMessageId(assistantMessage == null ? null : assistantMessage.getMessageId());
                ragTrace.setAnswerLength(aiResponse == null ? 0 : aiResponse.length());
                ragTrace.setSuccess(true);
                ragTraceService.saveBestEffort(ragTrace);
            }

            // 5. 更新会话标题（使用用户首条消息作为标题）
            String title = userMessage.length() > 30
                    ? userMessage.substring(0, 30) + "..."
                    : userMessage;
            chatMemoryService.updateSessionTitle(sessionId, title);

            // 6. 获取 messageId 用于返回
            String messageId = assistantMessage != null ? assistantMessage.getMessageId() : UUID.randomUUID().toString();

            // 7. 构建响应
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .content(aiResponse)
                    .responseTime(LocalDateTime.now())
                    .knowledgeSources(knowledgeSources)
                    .toolInvocations(toolInvocations)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("对话处理失败", e);

            // 模型在工具调用中断时可能把不完整的消息序列持久化下来，
            // 清掉 LangChain4j 记忆，避免下一次请求重复提交坏消息。
            if (StringUtils.isNotBlank(activeSessionId) && hasMessage(e, "url error")) {
                chatMemoryProvider.clearMemory(memoryId(request.getUserId(), activeSessionId));
                log.warn("已清理异常会话的模型记忆，可使用同一会话重试, sessionId: {}", activeSessionId);
            }

            return ChatResponse.builder()
                    .sessionId(request.getSessionId())
                    .messageId(UUID.randomUUID().toString())
                    .content("抱歉，处理您的请求时出现了问题，请稍后重试或联系人工投研助手。")
                    .responseTime(LocalDateTime.now())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    static String memoryId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    String buildMemoryContext(String userId, String sessionId, String query) {
        List<String> sections = new ArrayList<>();
        String summary = shortTermSummaryService.get(memoryId(userId, sessionId));
        if (StringUtils.isNotBlank(summary)) {
            sections.add("【历史对话摘要】\n" + summary);
        }
        try {
            List<com.ljl.ai.agent.model.entity.UserLongTermMemory> memories =
                    longTermMemoryService.recall(userId, query);
            if (memories != null && !memories.isEmpty()) {
                sections.add("【用户长期记忆】\n" + memories.stream()
                        .map(memory -> "- " + memory.getContent())
                        .collect(Collectors.joining("\n")));
            }
        } catch (Exception e) {
            log.warn("长期记忆召回失败，本轮跳过: {}", e.getMessage());
        }
        return String.join("\n\n", sections);
    }

    private Set<String> collectToolInvocationIds(String sessionId) {
        Set<String> ids = new HashSet<>();
        for (dev.langchain4j.data.message.ChatMessage message : chatMemoryProvider.get(sessionId).messages()) {
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

    private List<ToolInvocation> collectToolInvocations(String sessionId, Set<String> previousIds) {
        List<dev.langchain4j.data.message.ChatMessage> messages =
                chatMemoryProvider.get(sessionId).messages();
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
                invocation.setResult(resultMessage.text());
                invocation.setSuccess(true);
                invocations.put(resultMessage.id(), invocation);
            }
        }
        return new ArrayList<>(invocations.values());
    }

    private String toDisplayToolName(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "工具调用";
        }
        return TOOL_DISPLAY_NAMES.getOrDefault(functionName, "执行工具");
    }

    private boolean hasMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.containsIgnoreCase(current.getMessage(), expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getSessionHistory(String sessionId) {
        return chatMemoryService.getSessionMessages(sessionId);
    }

    /**
     * 获取用户会话列表
     */
    public List<ChatSession> getUserSessions(String userId) {
        return chatMemoryService.getUserSessions(userId);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        chatMemoryService.closeSession(sessionId);
    }

    /**
     * 提交消息反馈
     */
    public void submitFeedback(String messageId, int feedback, String detail) {
        chatMemoryService.updateMessageFeedback(messageId, feedback, detail);
    }
}
