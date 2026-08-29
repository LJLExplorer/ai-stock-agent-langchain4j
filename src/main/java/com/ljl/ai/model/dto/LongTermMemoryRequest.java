package com.ljl.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class LongTermMemoryRequest {
    @NotBlank
    private String userId;
    @NotBlank
    private String content;
    private List<String> tags;
}
