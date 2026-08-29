package com.ljl.ai.service;

import java.util.ArrayList;
import java.util.List;

/** 规范 AI Markdown 文本的无效空白，不修改代码块内容。 */
public final class AnswerTextFormatter {
    private AnswerTextFormatter() {
    }

    public static String format(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> output = new ArrayList<>();
        boolean inCodeBlock = false;
        int blankLines = 0;
        for (String line : lines) {
            boolean fence = line.trim().startsWith("```");
            if (inCodeBlock || fence) {
                output.add(inCodeBlock ? line : line.stripTrailing());
                if (fence) {
                    inCodeBlock = !inCodeBlock;
                }
                blankLines = 0;
                continue;
            }
            String clean = line.stripTrailing();
            if (clean.isBlank()) {
                if (blankLines == 0) {
                    output.add("");
                }
                blankLines++;
            } else {
                output.add(clean);
                blankLines = 0;
            }
        }
        return String.join("\n", output).trim();
    }
}
