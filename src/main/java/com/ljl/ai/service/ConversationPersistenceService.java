package com.ljl.ai.service;

import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.ConversationContextService.PreparedContext;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.RagTrace;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.ResearchConclusion;
import com.ljl.ai.research.ResearchDecisionService;
import com.ljl.ai.workflow.ExecutionState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.ljl.ai.memory.ConversationContextService.memoryId;

/** 管理会话与消息持久化，以及成功回答后的话题、摘要和研究决策更新。 */
@Slf4j
@Service
public class ConversationPersistenceService {
    @Resource
    private ChatMemoryService chatMemoryService;

    @Resource
    private RedisChatMemoryProvider chatMemoryProvider;

    @Resource
    private ShortTermSummaryService shortTermSummaryService;

    @Resource
    private ConversationTopicStore conversationTopicStore;

    @Resource
    private RagTraceService ragTraceService;

    @Resource
    private ResearchDecisionService researchDecisionService;

    /** 同步请求允许获取或创建会话；异步研究必须继续使用接单时绑定且仍可访问的会话。 */
    public ChatSession resolveSession(ChatRequest request, String preallocatedExecutionId) {
        // 异步句柄绑定原会话，接单后被删除时不可静默新建。
        return preallocatedExecutionId != null
                ? requireSessionForExecution(request.getSessionId(), request.getUserId())
                : chatMemoryService.getOrCreateSession(request.getSessionId(), request.getUserId(), request.getOrderId());
    }

    /**
     * 立即创建一个空会话，供前端在用户点击“新建会话”时使用。
     */
    public ChatSession createSession(String userId, String orderId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return chatMemoryService.createSession(userId.trim(), StringUtils.trimToNull(orderId));
    }

    /** 执行前校验会话存在、用户归属及未关闭状态，防止排队期间失效的会话被继续使用。 */
    public ChatSession requireSessionForExecution(String sessionId, String userId) {
        ChatSession session = chatMemoryService.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在，请新建会话后重新发起研究");
        }
        if (!java.util.Objects.equals(userId, session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }
        return session;
    }

    /**
     * 先保存用户原文和助手回答，再保存研究决策、推进话题、刷新摘要并关联检索轨迹。
     * 这些步骤并非跨存储事务；摘要刷新和研究决策的失败按各自的尽力保存策略处理。
     *
     * @return 助手消息标识；保存接口未返回消息对象时生成用于本次响应的标识
     */
    public String persistTurn(String sessionId, String originalUserMessage, PreparedContext context,
                              ResponseAssembler.ResponseContent response,
                              ExecutionState completedExecution, RagTrace ragTrace) {
        String modelMemoryId = context.modelMemoryId();
        String aiResponse = response.answer();
        List<KnowledgeSource> knowledgeSources = response.knowledgeSources();
        chatMemoryService.saveUserMessage(sessionId, originalUserMessage);
        ChatMessage assistantMessage = chatMemoryService.saveAssistantMessage(sessionId, aiResponse, knowledgeSources);
        if (assistantMessage == null) {
            log.warn("保存助手消息失败, sessionId: {}", sessionId);
        }
        persistResearchDecisionAfterMessage(completedExecution, assistantMessage);
        // 在业务消息保存调用返回后推进话题；保存抛出异常时不会走到这里。
        activateTopic(context.baseMemoryId(), context.query().topicKey());
        try {
            shortTermSummaryService.refresh(modelMemoryId);
        } catch (Exception e) {
            log.warn("短期记忆刷新失败，本次跳过, memoryId: {}", modelMemoryId, e);
        }
        if (ragTrace != null) {
            ragTrace.setMessageId(assistantMessage == null ? null : assistantMessage.getMessageId());
            ragTrace.setAnswerLength(aiResponse == null ? 0 : aiResponse.length());
            ragTrace.setSuccess(true);
            ragTraceService.saveBestEffort(ragTrace);
        }

        // 使用用户原始消息更新默认标题。
        String title = originalUserMessage.length() > 30
                ? originalUserMessage.substring(0, 30) + "..."
                : originalUserMessage;
        chatMemoryService.updateSessionTitle(sessionId, title);

        return assistantMessage != null ? assistantMessage.getMessageId() : UUID.randomUUID().toString();
    }

    /** 仅在助手消息已保存且深度结论有效时记录决策，避免把失败结论纳入后续复盘。 */
    void persistResearchDecisionAfterMessage(ExecutionState state, ChatMessage assistantMessage) {
        if (state == null || assistantMessage == null || researchDecisionService == null
                || state.getAnalysisContext() == null
                || state.getAnalysisContext().researchMode() != AnalysisContext.ResearchMode.DEEP
                || state.getResearchConclusion() == null
                || state.getResearchConclusion().rating() == ResearchConclusion.Rating.INSUFFICIENT_DATA) {
            return;
        }
        try {
            researchDecisionService.save(state);
        } catch (RuntimeException exception) {
            log.warn("research_decision_save_failed executionId={}, errorType={}", state.getExecutionId(),
                    exception.getClass().getSimpleName());
        }
    }

    private void activateTopic(String baseMemoryId, String topicKey) {
        if (conversationTopicStore != null) {
            conversationTopicStore.activate(baseMemoryId, topicKey);
        }
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getSessionHistory(String sessionId, String userId) {
        return chatMemoryService.getSessionMessages(sessionId, userId);
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
    public void closeSession(String sessionId, String userId) {
        chatMemoryService.closeSession(sessionId, userId);
    }

    /**
     * 删除会话及其持久化消息，同时清理模型记忆缓存。
     */
    public void deleteSession(String sessionId, String userId) {
        ChatSession session = chatMemoryService.getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        String memoryId = memoryId(userId, sessionId);
        List<String> modelMemoryIds = conversationTopicStore == null
                ? List.of(memoryId) : conversationTopicStore.modelMemoryIds(memoryId);
        for (String modelMemoryId : modelMemoryIds) {
            chatMemoryProvider.clearMemory(modelMemoryId);
            shortTermSummaryService.delete(modelMemoryId);
        }
        if (conversationTopicStore != null) {
            conversationTopicStore.delete(memoryId);
        }
        chatMemoryService.deleteSession(sessionId);
    }

    /**
     * 更新当前用户会话标题。
     */
    public ChatSession renameSession(String sessionId, String userId, String title) {
        ChatSession session = chatMemoryService.getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        chatMemoryService.renameSession(sessionId, title);
        return chatMemoryService.getSession(sessionId);
    }

    /**
     * 提交消息反馈
     */
    public void submitFeedback(String messageId, int feedback, String detail) {
        chatMemoryService.updateMessageFeedback(messageId, feedback, detail);
    }
}
