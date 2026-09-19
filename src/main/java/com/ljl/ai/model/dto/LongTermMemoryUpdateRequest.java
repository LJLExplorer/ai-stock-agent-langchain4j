package com.ljl.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** 用户手动修正长期记忆；服务端仍按 userId 校验记忆归属。 */
@Data
public class LongTermMemoryUpdateRequest {
    @NotBlank
    private String userId;
    @NotBlank
    private String content;
    private List<String> tags;
}
