package com.ljl.ai.service;

import com.ljl.ai.model.entity.UserLongTermMemory;

import java.util.Optional;

/** 受约束的候选提取边界；模型实现只能提出候选，不能直接发布记忆。 */
public interface MemoryCandidateExtractor {
    Optional<Proposal> extract(String userMessage);

    record Proposal(String content, String evidence, UserLongTermMemory.Type type, String canonicalKey) {
    }
}
