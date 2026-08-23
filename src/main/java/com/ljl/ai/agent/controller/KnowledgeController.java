package com.ljl.ai.agent.controller;

import com.ljl.ai.agent.knowledge.KnowledgeService;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
        String docToken = (String) request.get("docToken");
        String documentType = (String) request.getOrDefault("documentType", "FEISHU_DOC");
        List<String> tags = (List<String>) request.getOrDefault("tags", List.of());
        
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
     */
    @PostMapping("/documents")
    public ResponseEntity<KnowledgeDocument> addDocument(@RequestBody Map<String, Object> request) {
        String title = (String) request.get("title");
        String content = (String) request.get("content");
        String documentType = (String) request.getOrDefault("documentType", "MANUAL");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) request.getOrDefault("tags", List.of());
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) request.getOrDefault("metadata", Map.of());
        
        log.info("添加知识文档, title: {}", title);
        KnowledgeDocument document = knowledgeService.addKnowledgeDocument(
                title, content, documentType, tags, metadata);
        
        return ResponseEntity.ok(document);
    }
    
    /**
     * 获取所有启用的知识文档
     * GET /api/knowledge/documents
     */
    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeDocument>> getAllDocuments() {
        log.info("获取所有知识文档");
        List<KnowledgeDocument> documents = knowledgeService.findAllEnabled();
        return ResponseEntity.ok(documents);
    }
    
    /**
     * 根据类型获取知识文档
     * GET /api/knowledge/documents/type/{type}
     */
    @GetMapping("/documents/type/{type}")
    public ResponseEntity<List<KnowledgeDocument>> getDocumentsByType(@PathVariable String type) {
        log.info("根据类型获取知识文档, type: {}", type);
        List<KnowledgeDocument> documents = knowledgeService.findByType(type);
        return ResponseEntity.ok(documents);
    }
    
    /**
     * 禁用知识文档
     * POST /api/knowledge/documents/{documentId}/disable
     */
    @PostMapping("/documents/{documentId}/disable")
    public ResponseEntity<Map<String, Object>> disableDocument(@PathVariable String documentId) {
        log.info("禁用知识文档, documentId: {}", documentId);
        knowledgeService.disableDocument(documentId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "文档已禁用"
        ));
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
