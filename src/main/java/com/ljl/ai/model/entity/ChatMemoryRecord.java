package com.ljl.ai.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * ChatMemory 记录实体
 * 用于持久化 LangChain4j ChatMemory 的完整消息列表（包括工具调用消息）
 * 独立于业务层的 ChatMessage 集合
 *
 * chat_memory_records
 * LangChain4j 内部使用
 * 所有消息（含 ToolExecutionResultMessage）
 *
 *
 * chat_messages
 * 业务层/前端展示
 * 用户消息 + AI 最终回复
 */
@Data
@Builder
@Document(collection = "chat_memory_records")
public class ChatMemoryRecord {

    /**
     * 会话ID，作为主键
     */
    @Id
    private String sessionId;

    /**
     * 序列化的消息列表（JSON格式）
     * 包含所有消息类型：UserMessage, AiMessage, SystemMessage, ToolExecutionResultMessage
     */
    private String messagesJson;

    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdateTime;
}
