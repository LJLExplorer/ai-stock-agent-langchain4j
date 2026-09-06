const EXECUTION_BASE = '/api/research/executions'

export function taskDurationMs(task) {
  if (!task?.startedAt || !task?.completedAt) return null
  const duration = Date.parse(task.completedAt) - Date.parse(task.startedAt)
  return Number.isFinite(duration) && duration >= 0 ? duration : null
}

export function formatToolDuration(duration) {
  return typeof duration === 'number' && Number.isFinite(duration) && duration >= 0
    ? `${duration} ms` : '耗时未记录'
}

export const RUN_EVENT_TYPES = Object.freeze([
  'EXECUTION_ACCEPTED',
  'PLAN_CREATED',
  'NODE_STARTED',
  'NODE_COMPLETED',
  'TOOL_STARTED',
  'TOOL_COMPLETED',
  'TOOL_FAILED',
  'WORKFLOW_RETRYING',
  'EVIDENCE_PACK_READY',
  'DEEP_RESEARCH_STARTED',
  'ROLE_STARTED',
  'ROLE_COMPLETED',
  'ANSWER_READY',
  'WORKFLOW_COMPLETED',
  'WORKFLOW_FAILED'
])

const TERMINAL_EVENT_TYPES = new Set(['WORKFLOW_COMPLETED', 'WORKFLOW_FAILED'])
const RUN_EVENT_TYPE_SET = new Set(RUN_EVENT_TYPES)
const EXECUTION_ID = /^[A-Za-z0-9._:-]{1,128}$/
const PHASES = Object.freeze([
  ['PLAN', '计划与上下文'],
  ['DATA', '数据与证据'],
  ['RESEARCH', '多角色审议'],
  ['ANSWER', '结论生成']
])
export function buildResearchRequest(mode, payload) {
  const normalizedMode = mode === 'DEEP' ? 'DEEP' : 'STANDARD'
  const deep = normalizedMode === 'DEEP'
  return Object.freeze({
    mode: normalizedMode,
    asynchronous: deep,
    endpoint: deep ? EXECUTION_BASE : '/api/chat/send',
    payload: {
      ...payload,
      ...(deep ? { enableTools: true } : {}),
      researchMode: normalizedMode
    }
  })
}

export function createResearchProgress(executionId) {
  return {
    executionId: requireExecutionId(executionId),
    phases: PHASES.map(([id, label]) => ({ id, label, status: 'pending' })),
    plannedTaskCount: null,
    plannedRoleCount: null,
    completedToolNodes: [],
    completedRoleNodes: [],
    activeRoleNodes: [],
    planCompleted: false,
    evidenceReady: false,
    answerReady: false,
    completedSteps: 0,
    totalSteps: null,
    percent: 0,
    lastSequence: 0,
    retryCount: 0,
    missingItems: [],
    connection: 'connecting',
    canReconnect: false,
    lastEvent: null,
    error: ''
  }
}

export function reduceResearchProgress(progress, rawEvent) {
  const event = parseRunEvent(rawEvent)
  if (!progress || progress.executionId !== event.executionId) {
    throw new Error('进度状态与 RunEvent executionId 不匹配')
  }
  if (event.sequence <= progress.lastSequence) return progress
  let phases = progress.phases.map((phase) => ({ ...phase }))
  const completeThrough = (phaseId) => {
    const target = phases.findIndex((phase) => phase.id === phaseId)
    phases = phases.map((phase, index) => index <= target ? { ...phase, status: 'completed' } : phase)
  }
  const activate = (phaseId) => {
    const target = phases.findIndex((phase) => phase.id === phaseId)
    phases = phases.map((phase, index) => index < target
      ? { ...phase, status: 'completed' }
      : index === target ? { ...phase, status: 'active' } : phase)
  }

  let plannedTaskCount = progress.plannedTaskCount
  let plannedRoleCount = progress.plannedRoleCount
  let completedToolNodes = new Set(progress.completedToolNodes || [])
  let completedRoleNodes = new Set(progress.completedRoleNodes || [])
  let activeRoleNodes = new Set(progress.activeRoleNodes || [])
  let planCompleted = progress.planCompleted || false
  let evidenceReady = progress.evidenceReady || false
  let answerReady = progress.answerReady || false

  if (event.eventType === 'EXECUTION_ACCEPTED') activate('PLAN')
  if (event.eventType === 'PLAN_CREATED') {
    planCompleted = true
    plannedTaskCount = parseTaskCount(event.summary)
    completeThrough('PLAN')
    activate('DATA')
  }
  if (['NODE_STARTED', 'TOOL_STARTED', 'TOOL_COMPLETED', 'TOOL_FAILED'].includes(event.eventType)) {
    if (event.node === 'DEEP_RESEARCH') activate('RESEARCH')
    else if (event.node === 'ANSWER') activate('ANSWER')
    else if (!['PLAN', 'INIT', 'CRITIC', 'REFLECTOR'].includes(event.node)) activate('DATA')
  }
  if (['TOOL_COMPLETED', 'TOOL_FAILED'].includes(event.eventType) && event.node) {
    completedToolNodes.add(event.node)
  }
  if (event.eventType === 'EVIDENCE_PACK_READY') {
    evidenceReady = true
    completeThrough('DATA')
  }
  if (event.eventType === 'DEEP_RESEARCH_STARTED') {
    plannedRoleCount = parseRoleCount(event.summary)
    activate('RESEARCH')
  }
  if (event.eventType === 'ROLE_STARTED') {
    if (event.node) activeRoleNodes.add(event.node)
    activate('RESEARCH')
  }
  if (event.eventType === 'ROLE_COMPLETED') {
    if (event.node) {
      activeRoleNodes.delete(event.node)
      completedRoleNodes.add(event.node)
    }
    if (plannedRoleCount != null && completedRoleNodes.size >= plannedRoleCount) {
      completeThrough('RESEARCH')
      activate('ANSWER')
    } else {
      activate('RESEARCH')
    }
  }
  if (event.eventType === 'ANSWER_READY') {
    answerReady = true
    completeThrough('ANSWER')
  }
  if (event.eventType === 'WORKFLOW_COMPLETED') completeThrough('ANSWER')
  if (event.eventType === 'WORKFLOW_FAILED') {
    phases = phases.map((phase) => phase.status === 'active' ? { ...phase, status: 'failed' } : phase)
  }
  return withProgressMetrics({
    ...progress,
    phases,
    plannedTaskCount,
    plannedRoleCount,
    completedToolNodes: [...completedToolNodes],
    completedRoleNodes: [...completedRoleNodes],
    activeRoleNodes: [...activeRoleNodes],
    planCompleted,
    evidenceReady,
    answerReady,
    lastSequence: event.sequence,
    retryCount: progress.retryCount + (event.eventType === 'WORKFLOW_RETRYING' ? 1 : 0),
    connection: isTerminalRunEvent(event) ? 'terminal' : 'connected',
    canReconnect: false,
    lastEvent: { eventType: event.eventType, node: event.node, summary: event.summary },
    error: event.eventType === 'WORKFLOW_FAILED' ? '研究任务执行失败' : progress.error
  }, event.eventType === 'WORKFLOW_COMPLETED')
}

export function applyStatusCompensation(progress, status) {
  if (!progress || !status || status.executionId !== progress.executionId) {
    throw new Error('状态补偿 executionId 不匹配')
  }
  const terminal = ['COMPLETED', 'FAILED'].includes(status.workflowStatus)
  const tasks = Array.isArray(status.tasks) ? status.tasks : []
  const roleNames = roleNamesFromPack(status.evidencePack)
  const completedToolNodes = tasks
    .filter((task) => ['COMPLETED', 'FAILED'].includes(task.status))
    .map((task) => String(task.taskType || task.taskId || ''))
    .filter(Boolean)
  const hasConclusion = Boolean(status.researchConclusion)
  return withProgressMetrics({
    ...progress,
    plannedTaskCount: tasks.length || progress.plannedTaskCount,
    plannedRoleCount: roleNames.length || progress.plannedRoleCount,
    completedToolNodes,
    completedRoleNodes: hasConclusion
      ? roleNames
      : progress.completedRoleNodes,
    activeRoleNodes: hasConclusion ? [] : progress.activeRoleNodes,
    planCompleted: Boolean(status.plan) || progress.planCompleted,
    evidenceReady: Boolean(status.evidencePack) || progress.evidenceReady,
    answerReady: Boolean(status.finalAnswer) || progress.answerReady,
    missingItems: evidenceLimitations(status.evidencePack),
    connection: terminal ? 'terminal' : 'disconnected',
    canReconnect: !terminal,
    error: status.workflowStatus === 'FAILED'
      ? String(status.errorMessage || '研究任务执行失败') : progress.error
  }, status.workflowStatus === 'COMPLETED')
}

function parseTaskCount(summary) {
  const match = String(summary || '').match(/(?:^|;)taskCount=(\d+)(?:;|$)/)
  return match ? Number(match[1]) : 0
}

function parseRoleCount(summary) {
  const match = String(summary || '').match(/(?:^|;)roleCount=(\d+)(?:;|$)/)
  return match ? Number(match[1]) : null
}

function withProgressMetrics(progress, completed) {
  const taskCount = Number.isSafeInteger(progress.plannedTaskCount)
    ? Math.max(0, progress.plannedTaskCount) : null
  const roleCount = Number.isSafeInteger(progress.plannedRoleCount)
    ? Math.max(0, progress.plannedRoleCount) : null
  const totalSteps = taskCount == null || roleCount == null ? null : taskCount + roleCount + 3
  const toolCount = taskCount == null ? 0 : progress.evidenceReady
    ? taskCount : Math.min(taskCount, new Set(progress.completedToolNodes || []).size)
  const completedSteps = (progress.planCompleted ? 1 : 0)
    + toolCount
    + (progress.evidenceReady ? 1 : 0)
    + Math.min(roleCount || 0, new Set(progress.completedRoleNodes || []).size)
    + (progress.answerReady ? 1 : 0)
  const percent = completed ? 100 : totalSteps
    ? Math.min(99, Math.floor(completedSteps * 100 / totalSteps)) : 0
  return { ...progress, completedSteps, totalSteps, percent }
}

export function mapTerminalResearchResult(status) {
  const workflowStatus = status?.workflowStatus
  const terminal = workflowStatus === 'COMPLETED' || workflowStatus === 'FAILED'
  const success = workflowStatus === 'COMPLETED'
  return {
    terminal,
    success,
    answer: success ? String(status.finalAnswer || '') : '',
    error: workflowStatus === 'FAILED' ? String(status.errorMessage || '研究任务执行失败') : '',
    missingItems: evidenceLimitations(status?.evidencePack),
    sources: evidenceSourcesFromPack(status?.evidencePack)
  }
}

export function evidenceSourcesFromPack(evidencePack) {
  const grouped = evidencePack?.evidenceByType
  if (!grouped || typeof grouped !== 'object') return []
  const sources = new Map()
  Object.values(grouped).flatMap((facts) => Array.isArray(facts) ? facts : []).forEach((fact) => {
    const evidenceId = String(fact?.evidenceId || '').trim()
    if (!evidenceId || fact?.temporalStatus === 'REJECTED' || sources.has(evidenceId)) return
    const title = String(fact.sourceName || '数据证据').trim() || '数据证据'
    const metric = String(fact.metric || '事实').trim() || '事实'
    const unit = String(fact.unit || '').trim()
    const value = String(fact.value || '').trim()
    const type = String(fact.evidenceType || 'EVIDENCE').trim()
    const asOf = String(fact.asOf || '').trim()
    sources.set(evidenceId, {
      documentId: evidenceId,
      documentTitle: title,
      documentType: 'EVIDENCE',
      contentSnippet: `${metric}：${value}${unit ? ` ${unit}` : ''}`,
      documentUrl: safeHttpUrl(fact.sourceUrl),
      location: [type, asOf, fact.temporalStatus === 'UNKNOWN' ? '时间未核实，不用于结论' : ''].filter(Boolean).join(' · ')
    })
  })
  return [...sources.values()]
}

export function evidenceLimitations(pack) {
  const facts = Object.values(pack?.evidenceByType || {}).flatMap((items) => Array.isArray(items) ? items : [])
  const labels = {
    NEWS_ANALYSIS: '未找到可核验的近期相关新闻或公告；不采用教程、skill 或无日期材料',
    TECHNICAL_ANALYSIS: '技术指标数据不可用',
    FINANCIAL_ANALYSIS: '财务报告数据不可用',
    MARKET_DATA: '行情数据不可用'
  }
  return (Array.isArray(pack?.missingItems) ? pack.missingItems : []).map((item) => {
    const value = String(item)
    const match = value.match(/^时间未知:\s*(ev-[A-Za-z0-9._-]+)$/)
    if (!match) return labels[value] || value
    const fact = facts.find((entry) => entry?.evidenceId === match[1])
    const title = fact?.evidenceType === 'NEWS' ? fact.metric : fact?.sourceName
    return `${title ? `《${title}》` : '一项来源材料'}的发布时间或数据日期未核实，不用于结论；可在来源信息中核对`
  })
}

export function formatEvidenceCitations(content, sources = []) {
  const byId = new Map((Array.isArray(sources) ? sources : [])
    .filter((source) => source?.documentId)
    .map((source) => [String(source.documentId), source]))
  return String(content || '').replace(/\[evidence:(ev-[A-Za-z0-9._-]+)]/g, (_, evidenceId) => {
    const source = byId.get(evidenceId)
    const title = String(source?.documentTitle || '数据来源').replace(/[\[\]\\]/g, '').trim() || '数据来源'
    const target = safeHttpUrl(source?.documentUrl) || `#evidence-${evidenceId}`
    return `[证据：${title}](${target})`
  })
}

function roleNamesFromPack(evidencePack) {
  const types = new Set(Object.keys(evidencePack?.evidenceByType || {}))
  return [
    ...(types.has('FINANCIAL') ? ['FUNDAMENTAL'] : []),
    ...(types.has('TECHNICAL') || types.has('MARKET') ? ['TECHNICAL'] : []),
    ...(types.has('NEWS') ? ['NEWS'] : []),
    'BULL', 'BEAR', 'RISK', 'JUDGE'
  ]
}

function safeHttpUrl(value) {
  const url = String(value || '').trim()
  return /^https?:\/\//i.test(url) ? url : null
}

export async function startResearch(request, { fetchImpl = globalThis.fetch } = {}) {
  if (!request || typeof request !== 'object') throw new Error('深度投研请求不能为空')
  if (!String(request.userId || '').trim() || !String(request.message || '').trim()) {
    throw new Error('userId 和 message 不能为空')
  }
  const payload = { ...request, researchMode: 'DEEP' }
  const data = await requestJson(fetchImpl, EXECUTION_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  return Object.freeze({ ...data, executionId: requireExecutionId(data.executionId) })
}

export async function getResearchStatus(executionId, userId, { fetchImpl = globalThis.fetch } = {}) {
  const id = requireExecutionId(executionId)
  const owner = String(userId || '').trim()
  if (!owner) throw new Error('userId 不能为空')
  const url = `${EXECUTION_BASE}/${encodeURIComponent(id)}?userId=${encodeURIComponent(owner)}`
  const data = await requestJson(fetchImpl, url)
  if (data.executionId != null && requireExecutionId(data.executionId) !== id) {
    throw new Error('状态响应 executionId 不匹配')
  }
  return data
}

export function parseRunEvent(input) {
  let value = input && typeof input === 'object' && 'data' in input ? input.data : input
  try {
    if (typeof value === 'string') value = JSON.parse(value)
  } catch (error) {
    throw new Error(`RunEvent JSON 非法：${error.message}`)
  }
  if (!value || typeof value !== 'object') throw new Error('RunEvent 必须是对象')
  const executionId = requireExecutionId(value.executionId)
  const sequence = Number(value.sequence)
  if (!Number.isSafeInteger(sequence) || sequence <= 0) throw new Error('RunEvent sequence 非法')
  if (!RUN_EVENT_TYPE_SET.has(value.eventType)) throw new Error('RunEvent eventType 非法')
  const summary = value.summary == null ? '' : String(value.summary)
  if (summary.length > 500) throw new Error('RunEvent summary 过长')
  return Object.freeze({
    executionId,
    traceId: value.traceId == null ? null : String(value.traceId),
    sequence,
    occurredAt: value.occurredAt == null ? null : String(value.occurredAt),
    eventType: value.eventType,
    node: value.node == null ? '' : String(value.node),
    summary
  })
}

export function isTerminalRunEvent(event) {
  return Boolean(event && TERMINAL_EVENT_TYPES.has(event.eventType))
}

export function subscribeResearch({
  executionId,
  userId,
  onEvent = () => {},
  onTerminal = () => {},
  onStatus = () => {},
  onError = () => {},
  fetchImpl = globalThis.fetch,
  eventSourceFactory = (url) => new globalThis.EventSource(url)
}) {
  const id = requireExecutionId(executionId)
  const owner = String(userId || '').trim()
  if (!owner) throw new Error('userId 不能为空')
  const url = `${EXECUTION_BASE}/${encodeURIComponent(id)}/events?userId=${encodeURIComponent(owner)}`
  const source = eventSourceFactory(url)
  if (!source || typeof source.addEventListener !== 'function' || typeof source.close !== 'function') {
    throw new Error('EventSource 工厂返回值非法')
  }
  let closed = false
  const close = () => {
    if (closed) return
    closed = true
    source.close()
  }
  const compensate = async (cause) => {
    close()
    const error = cause instanceof Error ? cause : new Error('事件流连接中断')
    try {
      const status = await getResearchStatus(id, owner, { fetchImpl })
      onStatus(status)
      if (['COMPLETED', 'FAILED'].includes(status.workflowStatus)) return
    } catch (statusError) {
      error.statusError = statusError
    }
    onError(error)
  }
  const receive = (message) => {
    try {
      const event = parseRunEvent(message)
      if (event.executionId !== id) throw new Error('RunEvent executionId 不匹配')
      onEvent(event)
      if (isTerminalRunEvent(event)) {
        close()
        onTerminal(event)
      }
    } catch (error) {
      void compensate(error)
    }
  }
  RUN_EVENT_TYPES.forEach((type) => source.addEventListener(type, receive))
  source.onerror = compensate
  return Object.freeze({ source, close })
}

async function requestJson(fetchImpl, url, options) {
  if (typeof fetchImpl !== 'function') throw new Error('fetch 不可用')
  const response = await fetchImpl(url, options)
  let data = {}
  try {
    data = await response.json()
  } catch {
    data = {}
  }
  if (!response.ok) {
    throw new Error(data.errorMessage || data.message || `接口请求失败（HTTP ${response.status}）`)
  }
  return data && typeof data === 'object' ? data : {}
}

function requireExecutionId(value) {
  const executionId = String(value || '').trim()
  if (!EXECUTION_ID.test(executionId)) throw new Error('executionId 非法或缺失')
  return executionId
}
