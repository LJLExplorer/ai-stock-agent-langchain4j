package com.ljl.ai.knowledge;

import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将原始知识文档解析为带完整标题路径的 Parent Section 草稿。
 */
@Component
public class HierarchicalDocumentChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千万零〇两0-9]+[章节篇部分](?:\\s+\\S.*)?$");
    private static final Pattern CHINESE_ENUMERATION = Pattern.compile("^[一二三四五六七八九十百千万零〇两]+、(?:\\s*\\S.*)?$");
    private static final Pattern CHINESE_PARENTHESIZED = Pattern.compile("^（[一二三四五六七八九十百千万零〇两0-9]+）(?:\\s*\\S.*)?$");
    private static final Pattern DECIMAL_HEADING = Pattern.compile("^\\d+(?:\\.\\d+)+(?:\\s+\\S.*)?$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+\\.(?:\\s+\\S.*)?$");

    /**
     * 解析 Markdown 标题和常见中文章节编号。只有整行匹配标题模式的文本才会改变标题栈。
     */
    public List<ParentDraft> parseSections(String documentTitle, String rawContent,
                                           List<String> tags, Map<String, String> metadata) {
        String title = (documentTitle == null || documentTitle.isBlank()) ? "未命名文档" : documentTitle;
        String content = rawContent == null ? "" : rawContent;
        List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);
        Map<String, String> safeMetadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        List<ParentDraft> result = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder sectionContent = new StringBuilder();

        for (String lineWithEnding : content.split("(?<=\\n)", -1)) {
            String line = lineWithEnding.stripTrailing();
            Heading heading = headingOf(line);
            if (heading == null) {
                sectionContent.append(lineWithEnding);
                continue;
            }

            addDraftIfPresent(result, headingStack, sectionContent, safeTags, safeMetadata);
            sectionContent.setLength(0);
            while (headingStack.size() >= heading.level()) {
                headingStack.removeLast();
            }
            headingStack.add(heading.text());
        }
        addDraftIfPresent(result, headingStack, sectionContent, safeTags, safeMetadata);

        if (result.isEmpty()) {
            return List.of(new ParentDraft(0, List.of(title), content, safeTags, safeMetadata));
        }
        return result;
    }

    private void addDraftIfPresent(List<ParentDraft> drafts, List<String> headingStack,
                                   StringBuilder content, List<String> tags, Map<String, String> metadata) {
        if (headingStack.isEmpty() || content.toString().isBlank()) {
            return;
        }
        drafts.add(new ParentDraft(drafts.size(), List.copyOf(headingStack), content.toString().strip(), tags, metadata));
    }

    private Heading headingOf(String line) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), markdown.group(2));
        }
        if (CHINESE_CHAPTER.matcher(line).matches()) {
            return new Heading(1, line);
        }
        if (CHINESE_ENUMERATION.matcher(line).matches()) {
            return new Heading(2, line);
        }
        if (CHINESE_PARENTHESIZED.matcher(line).matches()) {
            return new Heading(3, line);
        }
        if (NUMBERED_HEADING.matcher(line).matches()) {
            return new Heading(4, line);
        }
        if (DECIMAL_HEADING.matcher(line).matches()) {
            return new Heading(5, line);
        }
        return null;
    }

    private record Heading(int level, String text) {
    }

    /** Parent Section 在持久化及 Child 切分前使用的不可变草稿。 */
    @Value
    public static class ParentDraft {
        int sectionIndex;
        List<String> headingPath;
        String content;
        List<String> tags;
        Map<String, String> metadata;
    }
}
