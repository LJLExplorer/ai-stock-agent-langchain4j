package com.ljl.ai.agent.controller;

import com.ljl.ai.agent.rag.RagPipelineService;
import com.ljl.ai.agent.rag.RagResult;
import com.ljl.ai.agent.rag.RetrievalResult;
import com.ljl.ai.agent.rag.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private RagPipelineService ragPipelineService;

    /**
     * 语义检索
     * POST /api/rag/search
     */
    @PostMapping("/search")
    public ResponseEntity<List<RetrievalResult>> search(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        int topK = (int) request.getOrDefault("topK", 5);

        log.info("语义检索, query: {}, topK: {}", query, topK);
        List<RetrievalResult> results = retrievalService.retrieve(query, topK);

        return ResponseEntity.ok(results);
    }

    /**
     * RAG增强查询
     * POST /api/rag/query
     */
    @PostMapping("/query")
    public ResponseEntity<RagResult> ragQuery(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");

        log.info("RAG增强查询, query: {}", query);
        RagResult result = ragPipelineService.executeRag(query);

        return ResponseEntity.ok(result);
    }
}
