package com.ljl.ai.knowledge;

import com.ljl.ai.model.entity.KnowledgeDocument;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将原始知识文档解析为带完整标题路径的 Parent Section 草稿。
 */
@Component
public class HierarchicalDocumentChunker {

    private static final int TARGET_CHILD_SIZE = 700;
    private static final int MIN_CHILD_SIZE = 600;
    private static final int MAX_CHILD_SIZE = 800;
    private static final int TARGET_OVERLAP = 100;
    private static final int MIN_OVERLAP = 80;
    private static final int MAX_OVERLAP = 120;
    private static final int SHORT_PARENT_THRESHOLD = 1200;
    private static final int SUMMARY_MIN_SIZE = 400;
    private static final int SUMMARY_MAX_SIZE = 600;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千万零〇两0-9]+[章节篇部分](?:\\s+\\S.*)?$");
    private static final Pattern CHINESE_ENUMERATION = Pattern.compile("^[一二三四五六七八九十百千万零〇两]+、(?:\\s*\\S.*)?$");
    private static final Pattern CHINESE_PARENTHESIZED = Pattern.compile("^（[一二三四五六七八九十百千万零〇两0-9]+）(?:\\s*\\S.*)?$");
    private static final Pattern DECIMAL_HEADING = Pattern.compile("^\\d+(?:\\.\\d+)+(?:\\s+\\S.*)?$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+\\.(?:\\s+\\S.*)?$");
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;]+[。！？!?；;]?");

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
        List<Heading> headingStack = new ArrayList<>();
        StringBuilder sectionContent = new StringBuilder();

        for (String lineWithEnding : content.split("(?<=\\n)", -1)) {
            String line = lineWithEnding.stripTrailing();
            Heading heading = headingOf(line);
            if (heading == null) {
                sectionContent.append(lineWithEnding);
                continue;
            }

            addDraftIfPresent(result, headingStack, sectionContent, title, safeTags, safeMetadata);
            sectionContent.setLength(0);
            while (!headingStack.isEmpty() && headingStack.getLast().level() >= heading.level()) {
                headingStack.removeLast();
            }
            headingStack.add(heading);
        }
        addDraftIfPresent(result, headingStack, sectionContent, title, safeTags, safeMetadata);

        if (result.isEmpty()) {
            return List.of(parentDraft(0, List.of(title), content, safeTags, safeMetadata, title));
        }
        return result;
    }

    /**
     * 将文档的每个 Parent Section 独立切分为 Child。Child 的 startOffset 表示不含
     * overlap 的新正文起点，overlapStartOffset 表示实际 Child 正文的起点。
     */
    public ChunkedDocument chunk(KnowledgeDocument document, String ingestionVersion) {
        String title = document == null ? null : document.getTitle();
        String documentId = document == null || document.getDocumentId() == null ? "" : document.getDocumentId();
        String rawContent = document == null ? null : document.getRawContent();
        List<String> tags = document == null ? List.of() : document.getTags();
        Map<String, String> metadata = document == null ? Map.of() : document.getMetadata();
        List<ParentDraft> parents = parseSections(title, rawContent, tags, metadata);
        List<ChildDraft> children = new ArrayList<>();
        for (ParentDraft parent : parents) {
            children.addAll(splitParent(documentId, ingestionVersion, parent));
        }
        return new ChunkedDocument(parents, children);
    }

    private List<ChildDraft> splitParent(String documentId, String ingestionVersion, ParentDraft parent) {
        String content = parent.getContent();
        if (content.isEmpty()) {
            return List.of();
        }
        List<Integer> paragraphBoundaries = paragraphBoundaries(content);
        List<Integer> sentenceBoundaries = sentenceBoundaries(content);
        List<Integer> semanticBoundaries = mergeBoundaries(paragraphBoundaries, sentenceBoundaries);
        List<MutableChild> spans = new ArrayList<>();
        int newStart = 0;
        while (newStart < content.length()) {
            int overlapStart = spans.isEmpty() ? newStart
                    : overlapStart(content, semanticBoundaries, newStart);
            int end = chooseEnd(content.length(), paragraphBoundaries, sentenceBoundaries, overlapStart);
            spans.add(new MutableChild(newStart, overlapStart, end));
            newStart = end;
        }
        mergeShortTail(spans);

        String parentSectionId = documentId + ":" + parent.getSectionIndex();
        List<ChildDraft> children = new ArrayList<>(spans.size());
        for (int index = 0; index < spans.size(); index++) {
            MutableChild span = spans.get(index);
            String childContent = content.substring(span.overlapStartOffset, span.endOffset);
            String embeddingText = "[标题路径] " + String.join(" > ", parent.getHeadingPath())
                    + "\n[正文] " + childContent;
            children.add(new ChildDraft(
                    parentSectionId + ":" + index,
                    parentSectionId,
                    parent.getSectionIndex(),
                    index,
                    parent.getHeadingPath(),
                    childContent,
                    embeddingText,
                    parent.getStockCode(),
                    parent.getYear(),
                    parent.getTags(),
                    span.newStartOffset,
                    span.endOffset,
                    span.overlapStartOffset));
        }
        return children;
    }

    private List<Integer> paragraphBoundaries(String content) {
        List<Integer> boundaries = new ArrayList<>();
        Matcher paragraph = Pattern.compile("(?:\\r?\\n\\s*){2,}").matcher(content);
        while (paragraph.find()) {
            boundaries.add(paragraph.end());
        }
        return boundaries;
    }

    private List<Integer> sentenceBoundaries(String content) {
        List<Integer> boundaries = new ArrayList<>();
        Matcher sentence = Pattern.compile("[。！？!?；;]").matcher(content);
        while (sentence.find()) {
            boundaries.add(sentence.end());
        }
        return boundaries;
    }

    private List<Integer> mergeBoundaries(List<Integer> paragraphBoundaries, List<Integer> sentenceBoundaries) {
        List<Integer> boundaries = new ArrayList<>(paragraphBoundaries);
        boundaries.addAll(sentenceBoundaries);
        boundaries.sort(Integer::compareTo);
        return boundaries.stream().distinct().toList();
    }

    private int overlapStart(String content, List<Integer> boundaries, int newStart) {
        int lowerBound = Math.max(0, newStart - MAX_OVERLAP);
        int upperBound = newStart - MIN_OVERLAP;
        int selected = -1;
        for (int boundary : boundaries) {
            if (boundary < lowerBound) {
                continue;
            }
            if (boundary > upperBound) {
                break;
            }
            selected = boundary;
        }
        return selected >= 0 ? selected : Math.max(0, newStart - TARGET_OVERLAP);
    }

    private int chooseEnd(int contentLength, List<Integer> paragraphBoundaries,
                          List<Integer> sentenceBoundaries, int overlapStart) {
        int remaining = contentLength - overlapStart;
        if (remaining <= MAX_CHILD_SIZE) {
            return contentLength;
        }
        int lowerBound = overlapStart + MIN_CHILD_SIZE;
        int upperBound = overlapStart + MAX_CHILD_SIZE;
        int paragraphEnd = closestToTarget(paragraphBoundaries, lowerBound, upperBound, overlapStart);
        if (paragraphEnd >= 0) {
            return paragraphEnd;
        }
        int sentenceEnd = closestToTarget(sentenceBoundaries, lowerBound, upperBound, overlapStart);
        return sentenceEnd >= 0 ? sentenceEnd : Math.min(contentLength, overlapStart + TARGET_CHILD_SIZE);
    }

    private int closestToTarget(List<Integer> boundaries, int lowerBound, int upperBound, int overlapStart) {
        int selected = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int boundary : boundaries) {
            if (boundary < lowerBound) {
                continue;
            }
            if (boundary > upperBound) {
                break;
            }
            int distance = Math.abs(boundary - (overlapStart + TARGET_CHILD_SIZE));
            if (distance < bestDistance) {
                selected = boundary;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private void mergeShortTail(List<MutableChild> spans) {
        if (spans.size() < 2) {
            return;
        }
        MutableChild tail = spans.getLast();
        MutableChild previous = spans.get(spans.size() - 2);
        if (tail.length() < MIN_CHILD_SIZE && tail.endOffset - previous.overlapStartOffset <= MAX_CHILD_SIZE) {
            previous.endOffset = tail.endOffset;
            spans.removeLast();
        }
    }

    private void addDraftIfPresent(List<ParentDraft> drafts, List<Heading> headingStack,
                                   StringBuilder content, String documentTitle,
                                   List<String> tags, Map<String, String> metadata) {
        if (content.toString().isBlank()) {
            return;
        }
        List<String> path = headingStack.isEmpty() ? List.of(documentTitle)
                : headingStack.stream().map(Heading::text).toList();
        drafts.add(parentDraft(drafts.size(), path, content.toString().strip(), tags, metadata, documentTitle));
    }

    private ParentDraft parentDraft(int sectionIndex, List<String> headingPath, String content,
                                    List<String> tags, Map<String, String> metadata, String documentTitle) {
        String normalizedContent = content == null ? "" : content.strip();
        String stockCode = resolveMetadata(metadata, "stockCode", STOCK_CODE, documentTitle, headingPath, normalizedContent);
        String year = resolveMetadata(metadata, "year", YEAR, documentTitle, headingPath, normalizedContent);
        String summary = normalizedContent.length() > SHORT_PARENT_THRESHOLD
                ? createExtractiveSummary(headingPath, normalizedContent) : null;
        return new ParentDraft(sectionIndex, headingPath, normalizedContent, tags, metadata, stockCode, year, summary);
    }

    private String resolveMetadata(Map<String, String> metadata, String key, Pattern pattern,
                                   String documentTitle, List<String> headingPath, String content) {
        String explicit = metadata == null ? null : metadata.get(key);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        for (String source : List.of(documentTitle == null ? "" : documentTitle,
                String.join(" > ", headingPath), content)) {
            Matcher matcher = pattern.matcher(source);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String createExtractiveSummary(List<String> headingPath, String content) {
        String heading = String.join(" > ", headingPath);
        String firstParagraph = firstEffectiveParagraph(content);
        List<SentenceCandidate> candidates = sentenceCandidates(content);
        List<String> selected = new ArrayList<>();
        selected.add(firstParagraph);
        Set<String> seen = new HashSet<>();
        seen.add(firstParagraph);
        candidates.stream()
                .filter(candidate -> firstParagraph.contains(candidate.text()))
                .forEach(candidate -> seen.add(candidate.text()));

        candidates.stream()
                .sorted(Comparator.comparingInt(SentenceCandidate::score).reversed()
                        .thenComparingInt(SentenceCandidate::position))
                .filter(candidate -> seen.add(candidate.text()))
                .limit(3)
                .sorted(Comparator.comparingInt(SentenceCandidate::position))
                .forEach(candidate -> selected.add(candidate.text()));

        appendUntilMinimum(selected, seen, candidates, heading.length() + 1);
        return joinWithinLimit(heading, selected);
    }

    private String firstEffectiveParagraph(String content) {
        for (String paragraph : content.split("(?:\\r?\\n\\s*){2,}")) {
            if (!paragraph.isBlank()) {
                return paragraph.strip();
            }
        }
        return content.strip();
    }

    private List<SentenceCandidate> sentenceCandidates(String content) {
        List<SentenceCandidate> candidates = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(content);
        while (matcher.find()) {
            String sentence = matcher.group().strip();
            if (!sentence.isEmpty()) {
                candidates.add(new SentenceCandidate(matcher.start(), sentence, scoreSentence(sentence)));
            }
        }
        return candidates;
    }

    private int scoreSentence(String sentence) {
        int score = 0;
        for (String keyword : List.of("营业收入", "净利润", "毛利率", "财务指标", "同比", "环比", "增长", "下降",
                "估值", "市盈率", "现金流", "负债", "风险", "减值", "诉讼")) {
            if (sentence.contains(keyword)) {
                score += 4;
            }
        }
        if (sentence.matches(".*\\d.*")) {
            score += 2;
        }
        if (sentence.matches(".*(?:%|亿元|万元|倍).*")) {
            score += 3;
        }
        return score;
    }

    private void appendUntilMinimum(List<String> selected, Set<String> seen,
                                    List<SentenceCandidate> candidates, int prefixLength) {
        for (SentenceCandidate candidate : candidates) {
            if (summaryLength(prefixLength, selected) >= SUMMARY_MIN_SIZE) {
                return;
            }
            if (seen.add(candidate.text()) && summaryLength(prefixLength, selected) + candidate.text().length() + 1
                    <= SUMMARY_MAX_SIZE) {
                selected.add(candidate.text());
            }
        }
    }

    private int summaryLength(int prefixLength, List<String> parts) {
        return prefixLength + parts.stream().mapToInt(String::length).sum() + parts.size();
    }

    private String joinWithinLimit(String heading, List<String> parts) {
        StringBuilder summary = new StringBuilder(heading).append('\n');
        for (String part : parts) {
            if (summary.length() + part.length() + 1 > SUMMARY_MAX_SIZE) {
                continue;
            }
            if (summary.length() > heading.length() + 1) {
                summary.append('\n');
            }
            summary.append(part);
        }
        return summary.toString();
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
            return new Heading(1, line);
        }
        if (DECIMAL_HEADING.matcher(line).matches()) {
            return new Heading((int) line.chars().filter(character -> character == '.').count() + 1, line);
        }
        return null;
    }

    private record Heading(int level, String text) {
    }

    private record SentenceCandidate(int position, String text, int score) {
    }

    /** Parent Section 在持久化及 Child 切分前使用的不可变草稿。 */
    @Value
    public static class ParentDraft {
        int sectionIndex;
        List<String> headingPath;
        String content;
        List<String> tags;
        Map<String, String> metadata;
        String stockCode;
        String year;
        String summary;
    }

    /** 一次层级切分产生的 Parent 草稿及其 Child 草稿。 */
    @Value
    public static class ChunkedDocument {
        List<ParentDraft> parents;
        List<ChildDraft> children;
    }

    /** 可写入向量索引的 Child 草稿，offset 相对于 Parent 正文。 */
    @Value
    public static class ChildDraft {
        String chunkId;
        String parentSectionId;
        int parentSectionIndex;
        int chunkIndex;
        List<String> headingPath;
        String content;
        String embeddingText;
        String stockCode;
        String year;
        List<String> tags;
        int startOffset;
        int endOffset;
        int overlapStartOffset;
    }

    private static class MutableChild {
        private final int newStartOffset;
        private final int overlapStartOffset;
        private int endOffset;

        private MutableChild(int newStartOffset, int overlapStartOffset, int endOffset) {
            this.newStartOffset = newStartOffset;
            this.overlapStartOffset = overlapStartOffset;
            this.endOffset = endOffset;
        }

        private int length() {
            return endOffset - overlapStartOffset;
        }
    }
}
