package com.ljl.ai.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识文档实体 - 用于知识库管理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "knowledge_documents")
public class KnowledgeDocument {
    
    /**
     * 文档ID
     */
    @Id
    private String documentId;
    
    /**
     * 飞书文档Token（如有）
     */
    private String feishuDocToken;
    
    /**
     * 文档标题
     */
    private String title;
    
    /**
     * 文档类型
     */
    private String documentType;
    
    /**
     * 文档内容（原始）
     */
    private String rawContent;
    
    /**
     * 文档摘要
     */
    private String summary;
    
    /**
     * 文档来源
     */
    private String source;
    
    /**
     * 文档链接
     */
    private String url;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 同步时间
     */
    private LocalDateTime syncTime;
    
    /**
     * 文档版本
     */
    private Integer version;
    
    /**
     * 向量ID列表（Milvus中的ID）
     */
    private List<String> vectorIds;
    
    /**
     * 分块数量
     */
    private Integer chunkCount;
    
    /**
     * 标签
     */
    private List<String> tags;
    
    /**
     * 元数据
     */
    private Map<String, String> metadata;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
}
