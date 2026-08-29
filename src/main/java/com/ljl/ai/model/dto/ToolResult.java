package com.ljl.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工具调用的统一返回协议。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult<T> {
    private boolean success;
    private T data;
    private String errorCode;
    private String errorMessage;
    private long costTime;

    public static <T> ToolResult<T> success(T data) {
        return ToolResult.<T>builder().success(true).data(data).build();
    }

    public static <T> ToolResult<T> failure(String errorCode, String errorMessage) {
        return ToolResult.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
