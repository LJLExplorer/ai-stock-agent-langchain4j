package com.ljl.ai.listener;

import com.ljl.ai.memoery.ChatMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 应用启动时清理脏数据
 */
@Slf4j
@Component
@Order(1)
public class DataCleanupRunner implements ApplicationRunner {

    @Resource
    private ChatMemoryService chatMemoryService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("应用启动，开始清理脏数据...");
        try {
            long cleanedCount = chatMemoryService.cleanupEmptyContentMessages();
            if (cleanedCount > 0) {
                log.info("启动清理完成，共清理 {} 条空内容消息", cleanedCount);
            } else {
                log.info("启动清理完成，没有发现需要清理的脏数据");
            }
        } catch (Exception e) {
            log.error("启动清理脏数据失败", e);
        }
    }
}
