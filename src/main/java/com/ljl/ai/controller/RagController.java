package com.ljl.ai.controller;

import com.ljl.ai.rag.RagPipelineService;
import com.ljl.ai.rag.RagResult;
import com.ljl.ai.rag.RetrievalResult;
import com.ljl.ai.rag.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG检索控制器 - 提供知识检索API
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {
    private final RetrievalService retrievalService;
    private final RagPipelineService ragPipelineService;

    /**
     * 语义检索
     * POST /api/rag/search
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> request) {
        Object rawQuery = request.get("query");
        Object rawTopK = request.getOrDefault("topK", 5);
        if (!(rawQuery instanceof String query) || !StringUtils.hasText(query)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "errorCode", "EMPTY_QUERY"));
        }
        if (!(rawTopK instanceof Number number) || number.intValue() < 1 || number.intValue() > 50
                || number.doubleValue() != number.intValue()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "errorCode", "INVALID_TOP_K"));
        }
        int topK = number.intValue();

        log.info("语义检索, query: {}, topK: {}", query, topK);
        List<RetrievalResult> results = retrievalService.retrieve(query, topK);

        return ResponseEntity.ok(results);
    }

    /**
     * RAG增强查询
     * POST /api/rag/query
     * 检索失败时降级为普通查询，避免知识库故障中断对话。
     */
    @PostMapping("/query")
    public ResponseEntity<?> ragQuery(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");

        if (!StringUtils.hasText(query)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "EMPTY_QUERY",
                    "errorMessage", "查询内容不能为空"
            ));
        }

        log.info("RAG增强查询, query: {}", query);

        try {
            RagResult result = ragPipelineService.executeRag(query);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", result
            ));

        } catch (Exception e) {
            log.error("RAG检索失败，执行降级处理，query: {}", query, e);

            // 降级方案: 返回空知识源，但允许对话继续
            RagResult fallbackResult = RagResult.builder()
                    .originalQuery(query)
                    .augmentedPrompt(query)  // 无增强
                    .augmentedContext("")     // 无上下文
                    .knowledgeSources(List.of())  // 空知识源
                    .retrievalResults(List.of())
                    .build();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", fallbackResult,
                    "warning", "知识库检索失败，已降级为普通对话"
            ));
        }
    }
}
