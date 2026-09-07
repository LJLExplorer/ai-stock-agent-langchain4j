package com.ljl.ai.research;

import com.ljl.ai.workflow.ExecutionState;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 保存决策并按所有者、标的和历史可见时间读取已完成复盘。 */
@Service
public class ResearchDecisionService {
    private final MongoTemplate mongoTemplate;

    public ResearchDecisionService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** 将完整执行上下文、证据哈希和研究结论记录为待复盘决策，同一用户的已有执行决策直接返回。 */
    public ResearchDecision save(ExecutionState state) {
        if (state == null || state.getResearchConclusion() == null || state.getEvidencePack() == null
                || state.getAnalysisContext() == null) {
            throw new IllegalArgumentException("保存研究决策需要完整的分析上下文、证据包和结论");
        }
        Query existingQuery = Query.query(Criteria.where("executionId").is(required(state.getExecutionId(), "executionId"))
                .and("userId").is(required(state.getUserId(), "userId")));
        ResearchDecision existing = mongoTemplate.findOne(existingQuery, ResearchDecision.class);
        if (existing != null) {
            return existing;
        }
        ResearchConclusion conclusion = state.getResearchConclusion();
        ResearchDecision decision = ResearchDecision.pending(UUID.randomUUID().toString(), state.getExecutionId(),
                state.getUserId(), required(state.getAnalysisContext().symbol(), "symbol"),
                state.getAnalysisContext().analysisDate(), conclusion.rating(), conclusion.confidence(),
                required(state.getEvidencePack().evidenceHash(), "evidenceHash"), conclusion.summary(),
                state.getGraphVersion());
        return mongoTemplate.insert(decision);
    }

    /** 只召回同一用户和标的、且后验结果在本次分析日已经可见的完整复盘，防止历史研究读取未来结果。 */
    public List<ResearchDecision> findCompletedReviews(String userId, String symbol, LocalDate analysisDate) {
        if (analysisDate == null) {
            throw new IllegalArgumentException("analysisDate 不能为空");
        }
        Query query = Query.query(Criteria.where("userId").is(required(userId, "userId"))
                        .and("symbol").is(required(symbol, "symbol"))
                        .and("reviewStatus").is(ResearchDecision.ReviewStatus.COMPLETED.name())
                        .and("analysisDate").lte(analysisDate)
                        .and("outcomeAvailableAt").lte(analysisDate))
                .with(Sort.by(Sort.Direction.DESC, "outcomeAvailableAt"));
        return List.copyOf(mongoTemplate.find(query, ResearchDecision.class));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
