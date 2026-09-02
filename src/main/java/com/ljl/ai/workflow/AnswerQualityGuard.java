package com.ljl.ai.workflow;

import java.util.ArrayList;
import java.util.List;

/** Rejects high-confidence malformed Markdown before a workflow answer is persisted. */
public final class AnswerQualityGuard {

    private static final int EXCESSIVE_MARKDOWN_PUNCTUATION = 24;
    private static final int EXCESSIVE_TABLE_SEPARATOR_CELLS = 8;
    private static final int REPETITIVE_FRAGMENT_REPETITIONS = 8;

    public enum Reason {
        OK,
        EMPTY,
        UNCLOSED_CODE_FENCE,
        INVALID_GFM_TABLE,
        EXCESSIVE_MARKDOWN_PUNCTUATION,
        REPETITIVE_OUTPUT,
        SUSPICIOUS_ENDING
    }

    public record Validation(boolean valid, Reason reason) {
        static Validation pass() {
            return new Validation(true, Reason.OK);
        }

        static Validation reject(Reason reason) {
            return new Validation(false, reason);
        }
    }

    public Validation validate(String answer) {
        if (answer == null || answer.isBlank()) {
            return Validation.reject(Reason.EMPTY);
        }

        String[] lines = answer.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (hasUnclosedCodeFence(lines)) {
            return Validation.reject(Reason.UNCLOSED_CODE_FENCE);
        }
        if (hasExcessiveMarkdownPunctuation(lines)) {
            return Validation.reject(Reason.EXCESSIVE_MARKDOWN_PUNCTUATION);
        }
        if (hasInvalidTable(lines)) {
            return Validation.reject(Reason.INVALID_GFM_TABLE);
        }
        if (hasRepetitiveFragment(answer)) {
            return Validation.reject(Reason.REPETITIVE_OUTPUT);
        }
        if (hasSuspiciousEnding(answer)) {
            return Validation.reject(Reason.SUSPICIOUS_ENDING);
        }
        return Validation.pass();
    }

    private boolean hasUnclosedCodeFence(String[] lines) {
        int fences = 0;
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                fences++;
            }
        }
        return fences % 2 != 0;
    }

    private boolean hasExcessiveMarkdownPunctuation(String[] lines) {
        boolean inCodeBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) {
                continue;
            }
            if (countUnescaped(line, '|') > EXCESSIVE_MARKDOWN_PUNCTUATION
                    || countUnescaped(line, '`') > EXCESSIVE_MARKDOWN_PUNCTUATION
                    || tableSeparatorCellCount(line) > EXCESSIVE_TABLE_SEPARATOR_CELLS) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInvalidTable(String[] lines) {
        boolean inCodeBlock = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock || !isTableSeparatorLine(line) || index == 0) {
                continue;
            }

            List<String> header = tableCells(lines[index - 1]);
            List<String> separator = tableCells(line);
            if (header.isEmpty() || header.size() != separator.size()
                    || header.stream().anyMatch(String::isBlank)) {
                return true;
            }

            for (int dataIndex = index + 1; dataIndex < lines.length; dataIndex++) {
                String dataLine = lines[dataIndex];
                if (dataLine.isBlank() || !containsUnescapedPipe(dataLine)) {
                    break;
                }
                if (tableCells(dataLine).size() != header.size()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasRepetitiveFragment(String answer) {
        String normalized = answer.replaceAll("\\s+", " ");
        for (int length = 2; length <= 8; length++) {
            for (int start = 0; start + length * REPETITIVE_FRAGMENT_REPETITIONS <= normalized.length(); start++) {
                String fragment = normalized.substring(start, start + length);
                int end = start + length;
                int repetitions = 1;
                while (end + length <= normalized.length()
                        && normalized.regionMatches(end, fragment, 0, length)) {
                    repetitions++;
                    end += length;
                }
                if (repetitions >= REPETITIVE_FRAGMENT_REPETITIONS && containsMarkdownPunctuation(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSuspiciousEnding(String answer) {
        String trimmed = answer.trim();
        return trimmed.endsWith(":---") || trimmed.matches("(?s).*`{4,}$");
    }

    private boolean containsMarkdownPunctuation(String value) {
        return value.indexOf('|') >= 0 || value.indexOf('`') >= 0 || value.contains(":-");
    }

    private boolean isTableSeparatorLine(String line) {
        List<String> cells = tableCells(line);
        return cells.size() >= 2 && cells.stream().allMatch(this::isTableSeparatorCell);
    }

    private boolean isTableSeparatorCell(String cell) {
        return cell.trim().matches(":?-{3,}:?");
    }

    private int tableSeparatorCellCount(String line) {
        int count = 0;
        for (String cell : tableCells(line)) {
            if (isTableSeparatorCell(cell)) {
                count++;
            }
        }
        return count;
    }

    private List<String> tableCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '|' && !escaped) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
            escaped = character == '\\' && !escaped;
            if (character != '\\') {
                escaped = false;
            }
        }
        cells.add(current.toString().trim());
        if (line.trim().startsWith("|") && !cells.isEmpty()) {
            cells.removeFirst();
        }
        if (line.trim().endsWith("|") && !cells.isEmpty()) {
            cells.removeLast();
        }
        return cells;
    }

    private int countUnescaped(String value, char target) {
        int count = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == target && !escaped) {
                count++;
            }
            escaped = character == '\\' && !escaped;
            if (character != '\\') {
                escaped = false;
            }
        }
        return count;
    }

    private boolean containsUnescapedPipe(String value) {
        return countUnescaped(value, '|') > 0;
    }
}
