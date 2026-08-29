package com.ljl.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查控制器
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 健康检查
     * GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ai-stock-agent-langchain4j",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * 服务信息
     * GET /api/info
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "name", "Stock Insight Agent",
                "version", "1.0.0",
                "description", "基于 LangChain4j 的股票研究与预测智能体",
                "features", new String[]{
                        "实时行情与技术指标",
                        "财报与基本面分析",
                        "时序模型预测",
                        "新闻公告检索(RAG)",
                        "多股票对比",
                        "投资组合分析"
                }
        ));
    }
}
