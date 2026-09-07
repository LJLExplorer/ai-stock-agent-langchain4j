package com.ljl.ai.support;

import org.apache.commons.lang3.StringUtils;

/** 提取模型回复中第一个完整 JSON 对象。 */
public final class ModelJsonExtractor {
    private ModelJsonExtractor() {}

    public static String extractJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalArgumentException("Planner 返回为空");
        }

        int start = raw.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("Planner 返回中未找到 JSON 对象");
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return raw.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("Planner 返回中的 JSON 对象不完整");
    }
}
