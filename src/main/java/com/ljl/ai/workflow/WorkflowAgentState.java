package com.ljl.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.ResearchDecision;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 图内唯一业务状态。值为深度只读的 JSON 数据，避免节点或序列化边界共享可变 Bean。
 * ExecutionState 仅用于持久化和现有领域服务的隔离副本，每次转换均创建新对象。
 */
public final class WorkflowAgentState extends AgentState {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() { };

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "tasks", Channels.<Map<String, Object>>base(WorkflowAgentState::mergeTasks, LinkedHashMap::new),
            "eventSequence", Channels.<Number>base((left, right) -> Math.max(left.longValue(), right.longValue()), () -> 0L));

    public WorkflowAgentState(Map<String, Object> data) {
        super(freezeMap(data));
    }

    /** 将持久化快照转换为独立的不可变图状态，任务按唯一 taskId 建索引以支持并行分支增量合并。 */
    public static WorkflowAgentState from(ExecutionState execution) {
        Map<String, Object> data = MAPPER.convertValue(execution, MAP_TYPE);
        data.put("tasks", taskValues(execution.getTasks() == null ? List.of() : execution.getTasks()));
        data.put("eventSequence", execution.getEventSequence());
        data.put("version", execution.getVersion());
        data.values().removeIf(Objects::isNull);
        return new WorkflowAgentState(data);
    }

    static Map<String, Object> taskValues(Collection<ExecutionTask> snapshots) {
        Map<String, Object> tasks = new LinkedHashMap<>();
        for (ExecutionTask task : snapshots) {
            if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()
                    || tasks.putIfAbsent(task.getTaskId(), MAPPER.convertValue(task, MAP_TYPE)) != null) {
                throw new IllegalArgumentException("任务必须具有唯一且非空的 taskId");
            }
        }
        return freezeMap(tasks);
    }

    /** 创建供领域服务或持久化使用的可变副本，对该副本的修改不会回写当前图状态。 */
    public ExecutionState toExecutionState() {
        Map<String, Object> data = new LinkedHashMap<>(data());
        data.put("tasks", new ArrayList<>(tasks().values()));
        return MAPPER.convertValue(data, ExecutionState.class);
    }

    public String executionId() { return this.<String>value("executionId").orElseThrow(); }
    public String nextNode() { return this.<String>value("nextNode").orElse("INIT"); }
    public long version() { return this.<Number>value("version").orElse(0L).longValue(); }
    public long eventSequence() { return this.<Number>value("eventSequence").orElse(0L).longValue(); }
    public int retryCount() { return this.<Number>value("retryCount").orElse(0).intValue(); }
    public WorkflowStatus workflowStatus() {
        return MAPPER.convertValue(data().getOrDefault("workflowStatus", "PLANNED"), WorkflowStatus.class);
    }
    public String traceId() { return this.<String>value("traceId").orElse(null); }
    public Map<String, Object> tasks() { return this.<Map<String, Object>>value("tasks").orElse(Map.of()); }
    public List<ExecutionTask> taskSnapshots() {
        return MAPPER.convertValue(new ArrayList<>(tasks().values()), new TypeReference<List<ExecutionTask>>() { });
    }
    public List<ResearchDecision> decisionReviews() {
        return MAPPER.convertValue(data().getOrDefault("decisionReviews", List.of()), new TypeReference<List<ResearchDecision>>() { });
    }
    public AnalysisContext analysisContext() {
        return MAPPER.convertValue(data().get("analysisContext"), AnalysisContext.class);
    }
    public EvidencePack evidencePack() {
        return MAPPER.convertValue(data().get("evidencePack"), EvidencePack.class);
    }
    public WorkflowReflector.ReflectionDecision reflectionDecision() {
        return MAPPER.convertValue(value("reflectionDecision").orElseThrow(), WorkflowReflector.ReflectionDecision.class);
    }
    public WorkflowCritic.Decision criticDecision() {
        return MAPPER.convertValue(value("criticDecision").orElseThrow(), WorkflowCritic.Decision.class);
    }

    /** 按通道规则应用增量并返回新状态；任务按 ID 合并，事件序号取最大值以兼容并行更新。 */
    public WorkflowAgentState apply(Map<String, Object> delta) {
        return new WorkflowAgentState(AgentState.updateState(data(), delta, SCHEMA));
    }

    /** 直接构造字段增量，脱离领域对象引用；null 表示清除字段。 */
    public static Map<String, Object> delta(Map<String, ?> fields) {
        return freezeMap(MAPPER.convertValue(fields, MAP_TYPE));
    }

    /** 只返回发生变化的字段；tasks 按 taskId 返回增量，兄弟分支不会覆盖彼此。 */
    public Map<String, Object> deltaTo(ExecutionState execution) {
        WorkflowAgentState next = from(execution);
        Map<String, Object> delta = new LinkedHashMap<>();
        next.data().forEach((key, value) -> {
            if (!Objects.equals(data().get(key), value)) delta.put(key, value);
        });
        data().keySet().stream().filter(key -> !next.data().containsKey(key))
                .forEach(key -> delta.put(key, null));
        Map<String, Object> changedTasks = new LinkedHashMap<>();
        next.tasks().forEach((id, task) -> {
            if (!Objects.equals(tasks().get(id), task)) changedTasks.put(id, task);
        });
        if (!next.tasks().keySet().containsAll(tasks().keySet())) {
            throw new IllegalArgumentException("执行过程中不能删除已规划任务");
        }
        delta.remove("tasks");
        if (!changedTasks.isEmpty()) delta.put("tasks", changedTasks);
        return delta;
    }

    public static ExecutionState copyOf(ExecutionState execution) {
        return from(execution).toExecutionState();
    }

    /** 只覆盖增量包含的任务 ID 并冻结结果，保留其他并行分支已经提交的任务。 */
    private static Map<String, Object> mergeTasks(Map<String, Object> current, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(updates);
        return freezeMap(merged);
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, freeze(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, item) -> frozen.put((String) key, freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) return list.stream().map(WorkflowAgentState::freeze).toList();
        return value;
    }
}
