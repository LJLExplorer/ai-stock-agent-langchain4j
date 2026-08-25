package com.ljl.ai.agent.controller;

import com.ljl.ai.agent.knowledge.KnowledgeService;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * 知识库管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private  KnowledgeService knowledgeService;
    
    /**
     * 同步飞书文档
     * POST /api/knowledge/feishu/sync
     *
     */
    @PostMapping("/feishu/sync")
    public ResponseEntity<KnowledgeDocument> syncFeishuDocument(@RequestBody Map<String, Object> request) {
        Object rawDocToken = request.get("docToken");
        Object rawDocumentType = request.getOrDefault("documentType", "FEISHU_DOC");
        Object rawTags = request.getOrDefault("tags", List.of());
        if (!(rawDocToken instanceof String docToken) || !StringUtils.hasText(docToken)
                || !(rawDocumentType instanceof String documentType)
                || !(rawTags instanceof List<?> rawTagList)
                || rawTagList.stream().anyMatch(tag -> !(tag instanceof String))) {
            return ResponseEntity.badRequest().build();
        }
        List<String> tags = rawTagList.stream().map(String.class::cast).toList();
        
        log.info("同步飞书文档, docToken: {}", docToken);
        KnowledgeDocument document = knowledgeService.syncFeishuDocument(docToken, documentType, tags);
        
        if (document != null) {
            return ResponseEntity.ok(document);
        } else {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 添加知识文档
     * POST /api/knowledge/documents
     * BUG B005修复: 添加参数验证，防止null或过大的内容导致异常
     */
    @PostMapping("/documents")
    public ResponseEntity<?> addDocument(@RequestBody Map<String, Object> request) {
        // 参数验证
        Object rawTitle = request.get("title");
        Object rawContent = request.get("content");
        if (!(rawTitle instanceof String title) || !(rawContent instanceof String content)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_REQUEST",
                    "errorMessage", "title 和 content 必须是字符串"
            ));
        }

        if (!StringUtils.hasText(title)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_TITLE",
                    "errorMessage", "文档标题不能为空"
            ));
        }

        if (!StringUtils.hasText(content)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_CONTENT",
                    "errorMessage", "文档内容不能为空"
            ));
        }

        // 标题长度限制
        if (title.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "TITLE_TOO_LONG",
                    "errorMessage", "标题长度不能超过200字符"
            ));
        }

        // 内容大小限制（10MB）
        if (content.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "CONTENT_TOO_LARGE",
                    "errorMessage", "文档内容不能超过10MB"
            ));
        }

        Object rawDocumentType = request.getOrDefault("documentType", "MANUAL");
        Object rawTags = request.getOrDefault("tags", List.of());
        Object rawMetadata = request.getOrDefault("metadata", Map.of());
        if (!(rawDocumentType instanceof String documentType)
                || !(rawTags instanceof List<?> rawTagList)
                || rawTagList.stream().anyMatch(tag -> !(tag instanceof String))
                || !(rawMetadata instanceof Map<?, ?> rawMetadataMap)
                || rawMetadataMap.entrySet().stream().anyMatch(entry ->
                !(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_REQUEST",
                    "errorMessage", "documentType、tags 和 metadata 类型不正确"
            ));
        }
        List<String> tags = rawTagList.stream().map(String.class::cast).toList();
        Map<String, String> metadata = rawMetadataMap.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        entry -> (String) entry.getKey(), entry -> (String) entry.getValue()));

        log.info("添加知识文档, title: {}, size: {}", title, content.length());

        try {
            KnowledgeDocument document = knowledgeService.addKnowledgeDocument(
                    title, content, documentType, tags, metadata);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", document,
                    "message", "文档添加成功"
            ));
        } catch (Exception e) {
            log.error("添加文档失败", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "errorCode", "ADD_DOCUMENT_ERROR",
                    "errorMessage", "文档添加失败: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 获取所有启用的知识文档
     * GET /api/knowledge/documents
     */
    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeDocument>> getAllDocuments() {
        log.info("获取所有知识文档");
        List<KnowledgeDocument> documents = knowledgeService.findAll();
        return ResponseEntity.ok(documents != null ? documents : List.of());
    }

    /**
     * 根据类型获取知识文档
     * GET /api/knowledge/documents/type/{type}
     */
    @GetMapping("/documents/type/{type}")
    public ResponseEntity<List<KnowledgeDocument>> getDocumentsByType(@PathVariable String type) {
        log.info("根据类型获取知识文档, type: {}", type);
        List<KnowledgeDocument> documents = knowledgeService.findByType(type);
        return ResponseEntity.ok(documents != null ? documents : List.of());
    }
    
    /**
     * 禁用知识文档
     * POST /api/knowledge/documents/{documentId}/disable
     */
    @PostMapping("/documents/{documentId}/disable")
    public ResponseEntity<Map<String, Object>> disableDocument(@PathVariable String documentId) {
        log.info("禁用知识文档, documentId: {}", documentId);
        try {
            knowledgeService.disableDocument(documentId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文档已禁用"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "errorMessage", e.getMessage()
            ));
        }
    }
    
    /**
     * 删除知识文档
     * DELETE /api/knowledge/documents/{documentId}
     */
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String documentId) {
        log.info("删除知识文档, documentId: {}", documentId);
        knowledgeService.deleteDocument(documentId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "文档已删除"
        ));
    }
}
