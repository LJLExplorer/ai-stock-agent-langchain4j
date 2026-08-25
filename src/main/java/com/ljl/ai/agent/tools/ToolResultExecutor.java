package com.ljl.ai.agent.tools;

import com.ljl.ai.agent.model.dto.ToolResult;

/** 统一包装工具执行结果、异常和耗时。 */
public final class ToolResultExecutor {
    private ToolResultExecutor() {
    }

    public static <T> ToolResult<T> execute(String errorCode, ThrowingSupplier<T> action) {
        long started = System.nanoTime();
        try {
            ToolResult<T> result = ToolResult.success(action.get());
            return withCost(result, elapsedMillis(started));
        } catch (Exception exception) {
            ToolResult<T> result = ToolResult.failure(errorCode, messageOf(exception));
            return withCost(result, elapsedMillis(started));
        }
    }

    public static <T> ToolResult<T> executeResult(String errorCode, ThrowingSupplier<ToolResult<T>> action) {
        long started = System.nanoTime();
        try {
            return withCost(action.get(), elapsedMillis(started));
        } catch (Exception exception) {
            return withCost(ToolResult.failure(errorCode, messageOf(exception)), elapsedMillis(started));
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static <T> ToolResult<T> withCost(ToolResult<T> result, long costTime) {
        result.setCostTime(costTime);
        return result;
    }
}
