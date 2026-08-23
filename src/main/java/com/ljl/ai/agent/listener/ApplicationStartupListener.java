package com.ljl.ai.agent.listener;

import com.ljl.ai.agent.memoery.ChatMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动监听器 - 用于执行初始化任务
 */
@Slf4j
@Component
public class ApplicationStartupListener implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ChatMemoryService chatMemoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("应用已启动，开始执行初始化任务...");
        
        // 清理空内容的消息
        try {
            chatMemoryService.cleanupEmptyContentMessages();
            log.info("初始化任务完成：空内容消息清理");
        } catch (Exception e) {
            log.error("执行初始化任务失败", e);
        }
    }
}