package com.ljl.ai.agent.service;

import com.ljl.ai.agent.agent.StockAnalysisAssistant;
import com.ljl.ai.agent.memoery.ChatMemoryService;
import com.ljl.ai.agent.memoery.MongoChatMemoryProvider;
import com.ljl.ai.agent.model.dto.ChatRequest;
import com.ljl.ai.agent.model.dto.ChatResponse;
import com.ljl.ai.agent.model.entity.ChatMessage;
import com.ljl.ai.agent.model.entity.ChatSession;
import com.ljl.ai.agent.model.entity.KnowledgeSource;
import com.ljl.ai.agent.rag.RagPipelineService;
import com.ljl.ai.agent.rag.RagResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话服务 - 核心业务逻辑
 * 消息的持久化由 LangChain4j 通过 ChatMemoryStore 自动处理
 */
@Slf4j
@Service
public class ChatService {

    @Resource
    private StockAnalysisAssistant stockAnalysisAssistant;

    @Resource
    private ChatMemoryService chatMemoryService;

    @Resource
    private MongoChatMemoryProvider chatMemoryProvider;

    @Resource
    private RagPipelineService ragPipelineService;

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
            String userMessage = request.getMessage();

            // 2. 执行RAG检索（如果启用）
            List<KnowledgeSource> knowledgeSources = null;
            String ragContext = null;

            if (Boolean.TRUE.equals(request.getEnableRag())) {
                RagResult ragResult = ragPipelineService.executeRag(userMessage);
                knowledgeSources = ragResult.getKnowledgeSources();

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

            String aiResponse;
            if (ragContext != null) {
                // 有RAG上下文
                aiResponse = stockAnalysisAssistant.chatWithRag(sessionId, userMessage, ragContext);
            } else {
                // 普通对话
                aiResponse = stockAnalysisAssistant.chat(sessionId, userMessage);
            }

            // 4. 保存用户消息和AI回复到业务层（chat_messages 集合，用于前端展示）
            chatMemoryService.saveUserMessage(sessionId, userMessage);
            ChatMessage assistantMessage = chatMemoryService.saveAssistantMessage(sessionId, aiResponse);

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
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("对话处理失败", e);

            // 模型在工具调用中断时可能把不完整的消息序列持久化下来，
            // 清掉 LangChain4j 记忆，避免下一次请求重复提交坏消息。
            if (StringUtils.isNotBlank(activeSessionId) && hasMessage(e, "url error")) {
                chatMemoryProvider.clearMemory(activeSessionId);
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
