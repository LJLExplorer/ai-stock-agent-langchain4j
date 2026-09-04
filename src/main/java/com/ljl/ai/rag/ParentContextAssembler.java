package com.ljl.ai.rag;

import com.ljl.ai.model.entity.KnowledgeSection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将 Child 检索命中恢复为 Parent 上下文。Child 仅用于召回；正文合并严格使用
 * {@link KnowledgeSection.ChunkSpan} 的原文区间，避免依赖字符串内容去重。
 */
@Component
public class ParentContextAssembler {

    private static final int SHORT_PARENT_THRESHOLD = 1_200;

    public List<RetrievalResult> assemble(List<ChildHit> hits,
                                          Map<SectionVersionKey, KnowledgeSection> sections,
                                          int topK) {
        if (hits == null || hits.isEmpty() || sections == null || sections.isEmpty() || topK <= 0) {
            return List.of();
        }

        Map<SectionVersionKey, List<ChildHit>> bySection = hits.stream()
                .filter(Objects::nonNull)
                .filter(hit -> sections.containsKey(new SectionVersionKey(hit.parentSectionId(), hit.ingestionVersion())))
                .collect(Collectors.groupingBy(hit -> new SectionVersionKey(hit.parentSectionId(), hit.ingestionVersion()),
                        LinkedHashMap::new, Collectors.toList()));

        List<AssembledWindow> assembled = new ArrayList<>();
        bySection.forEach((key, sectionHits) -> {
            KnowledgeSection section = sections.get(key);
            if (contentLength(section) <= SHORT_PARENT_THRESHOLD) {
                assembled.add(buildWindow(section, sectionHits, sectionRange(section, sectionHits), true));
            } else {
                assembled.addAll(buildLongParentWindows(section, sectionHits));
            }
        });

        return assembled.stream()
                .sorted(Comparator.comparingDouble(AssembledWindow::rrfScore).reversed()
                        .thenComparing(Comparator.comparingInt(AssembledWindow::matchCount).reversed())
                        .thenComparing(Comparator.comparingDouble(AssembledWindow::semanticScore).reversed())
                        .thenComparingInt(AssembledWindow::firstOriginalOrder))
                .limit(topK)
                .map(AssembledWindow::result)
                .toList();
    }

    private List<AssembledWindow> buildLongParentWindows(KnowledgeSection section, List<ChildHit> hits) {
        int lastIndex = section.getChunkSpans() == null || section.getChunkSpans().isEmpty()
                ? hits.stream().mapToInt(ChildHit::chunkIndex).max().orElse(0)
                : section.getChunkSpans().stream().mapToInt(KnowledgeSection.ChunkSpan::getChunkIndex).max().orElse(0);
        List<Window> windows = hits.stream()
                .map(hit -> new Window(Math.max(0, hit.chunkIndex() - 1), Math.min(lastIndex, hit.chunkIndex() + 1), List.of(hit)))
                .sorted(Comparator.comparingInt(Window::startIndex))
                .toList();

        List<Window> merged = new ArrayList<>();
        for (Window next : windows) {
            if (merged.isEmpty() || next.startIndex() > merged.getLast().endIndex() + 1) {
                merged.add(next);
                continue;
            }
            Window current = merged.removeLast();
            List<ChildHit> combined = new ArrayList<>(current.hits());
            combined.addAll(next.hits());
            merged.add(new Window(current.startIndex(), Math.max(current.endIndex(), next.endIndex()), combined));
        }
        return merged.stream().map(window -> buildWindow(section, window.hits(), window, false)).toList();
    }

    private Window sectionRange(KnowledgeSection section, List<ChildHit> hits) {
        if (section.getChunkSpans() == null || section.getChunkSpans().isEmpty()) {
            return new Window(hits.stream().mapToInt(ChildHit::chunkIndex).min().orElse(0),
                    hits.stream().mapToInt(ChildHit::chunkIndex).max().orElse(0), hits);
        }
        return new Window(section.getChunkSpans().stream().mapToInt(KnowledgeSection.ChunkSpan::getChunkIndex).min().orElse(0),
                section.getChunkSpans().stream().mapToInt(KnowledgeSection.ChunkSpan::getChunkIndex).max().orElse(0), hits);
    }

    private AssembledWindow buildWindow(KnowledgeSection section, List<ChildHit> windowHits,
                                        Window window, boolean wholeParent) {
        List<ChildHit> uniqueHits = windowHits.stream()
                .collect(Collectors.toMap(ChildHit::chunkId, hit -> hit, (first, ignored) -> first, LinkedHashMap::new))
                .values().stream().sorted(Comparator.comparingInt(ChildHit::chunkIndex)).toList();
        ChildHit representative = uniqueHits.stream().max(Comparator
                        .comparingDouble((ChildHit hit) -> score(hit.result().getRrfScore()))
                        .thenComparingDouble(hit -> score(hit.result().getSemanticScore())))
                .orElseThrow();
        String body = wholeParent ? safeContent(section.getContent()) : contentForWindow(section, window, uniqueHits);
        RetrievalResult result = RetrievalResult.builder()
                .content(context(section, body, wholeParent))
                .similarity(representative.result().getSimilarity())
                .semanticScore(representative.result().getSemanticScore())
                .bm25Score(representative.result().getBm25Score())
                .rrfScore(representative.result().getRrfScore())
                .documentId(section.getDocumentId() == null ? representative.result().getDocumentId() : section.getDocumentId())
                .title(representative.result().getTitle())
                .documentType(representative.result().getDocumentType())
                .source(representative.result().getSource())
                .parentSectionId(section.getSectionId())
                .headingPath(section.getHeadingPath())
                .parentSummary(section.getSummary())
                .matchedChunkIds(uniqueHits.stream().map(ChildHit::chunkId).toList())
                .windowStartIndex(window.startIndex())
                .windowEndIndex(window.endIndex())
                .build();
        return new AssembledWindow(result,
                uniqueHits.stream().mapToDouble(hit -> score(hit.result().getRrfScore())).max().orElse(0D),
                uniqueHits.size(),
                uniqueHits.stream().mapToDouble(hit -> score(hit.result().getSemanticScore())).max().orElse(0D),
                uniqueHits.stream().mapToInt(ChildHit::originalOrder).min().orElse(Integer.MAX_VALUE));
    }

    private String contentForWindow(KnowledgeSection section, Window window, List<ChildHit> hits) {
        if (section.getChunkSpans() == null || section.getChunkSpans().isEmpty()) {
            return hits.stream().map(hit -> safeContent(hit.result().getContent())).collect(Collectors.joining("\n"));
        }
        List<Interval> intervals = section.getChunkSpans().stream()
                .filter(span -> span.getChunkIndex() >= window.startIndex() && span.getChunkIndex() <= window.endIndex())
                .map(span -> new Interval(span.getOverlapStartOffset(), span.getEndOffset()))
                .sorted(Comparator.comparingInt(Interval::start))
                .toList();
        return extractUnion(safeContent(section.getContent()), intervals);
    }

    private String extractUnion(String content, List<Interval> intervals) {
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : intervals) {
            int start = Math.max(0, Math.min(interval.start(), content.length()));
            int end = Math.max(start, Math.min(interval.end(), content.length()));
            if (merged.isEmpty() || start > merged.getLast().end()) {
                merged.add(new Interval(start, end));
            } else {
                Interval previous = merged.removeLast();
                merged.add(new Interval(previous.start(), Math.max(previous.end(), end)));
            }
        }
        return merged.stream().map(interval -> content.substring(interval.start(), interval.end()))
                .collect(Collectors.joining("\n"));
    }

    private String context(KnowledgeSection section, String body, boolean wholeParent) {
        List<String> parts = new ArrayList<>();
        if (section.getHeadingPath() != null && !section.getHeadingPath().isEmpty()) {
            parts.add("标题路径：" + String.join(" > ", section.getHeadingPath()));
        }
        if (!wholeParent && section.getSummary() != null && !section.getSummary().isBlank()) {
            parts.add("父章节摘要：" + section.getSummary());
        }
        parts.add((wholeParent ? "父章节全文：" : "相关正文：") + body);
        return String.join("\n", parts);
    }

    private static double score(Double value) {
        return value == null ? 0D : value;
    }

    private static String safeContent(String value) {
        return value == null ? "" : value;
    }

    private static int contentLength(KnowledgeSection section) {
        return section.getContentLength() > 0 ? section.getContentLength() : safeContent(section.getContent()).length();
    }

    public record SectionVersionKey(String sectionId, String ingestionVersion) {
    }

    public record ChildHit(String chunkId, String parentSectionId, String ingestionVersion, int chunkIndex,
                           RetrievalResult result, int originalOrder) {
    }

    private record Window(int startIndex, int endIndex, List<ChildHit> hits) {
    }

    private record Interval(int start, int end) {
    }

    private record AssembledWindow(RetrievalResult result, double rrfScore, int matchCount,
                                   double semanticScore, int firstOriginalOrder) {
    }
}
