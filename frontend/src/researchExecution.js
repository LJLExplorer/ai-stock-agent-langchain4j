const EXECUTION_BASE = '/api/research/executions'

export const RUN_EVENT_TYPES = Object.freeze([
  'PLAN_CREATED',
  'NODE_STARTED',
  'NODE_COMPLETED',
  'TOOL_STARTED',
  'TOOL_COMPLETED',
  'TOOL_FAILED',
  'WORKFLOW_RETRYING',
  'EVIDENCE_PACK_READY',
  'DEEP_RESEARCH_STARTED',
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

  if (event.eventType === 'PLAN_CREATED') activate('PLAN')
  if (['NODE_STARTED', 'TOOL_STARTED', 'TOOL_COMPLETED', 'TOOL_FAILED'].includes(event.eventType)) {
    if (event.node === 'DEEP_RESEARCH') activate('RESEARCH')
    else if (event.node === 'ANSWER') activate('ANSWER')
    else if (!['PLAN', 'INIT', 'CRITIC', 'REFLECTOR'].includes(event.node)) activate('DATA')
  }
  if (event.eventType === 'EVIDENCE_PACK_READY') completeThrough('DATA')
  if (event.eventType === 'DEEP_RESEARCH_STARTED' || event.eventType === 'ROLE_COMPLETED') activate('RESEARCH')
  if (event.eventType === 'ANSWER_READY') completeThrough('ANSWER')
  if (event.eventType === 'WORKFLOW_COMPLETED') completeThrough('ANSWER')
  if (event.eventType === 'WORKFLOW_FAILED') {
    phases = phases.map((phase) => phase.status === 'active' ? { ...phase, status: 'failed' } : phase)
  }
  return {
    ...progress,
    phases,
    lastSequence: event.sequence,
    retryCount: progress.retryCount + (event.eventType === 'WORKFLOW_RETRYING' ? 1 : 0),
    connection: isTerminalRunEvent(event) ? 'terminal' : 'connected',
    canReconnect: false,
    lastEvent: { eventType: event.eventType, node: event.node, summary: event.summary },
    error: event.eventType === 'WORKFLOW_FAILED' ? '研究任务执行失败' : progress.error
  }
}

export function applyStatusCompensation(progress, status) {
  if (!progress || !status || status.executionId !== progress.executionId) {
    throw new Error('状态补偿 executionId 不匹配')
  }
  const terminal = ['COMPLETED', 'FAILED'].includes(status.workflowStatus)
  return {
    ...progress,
    missingItems: Array.isArray(status.evidencePack?.missingItems)
      ? status.evidencePack.missingItems.map(String) : [],
    connection: terminal ? 'terminal' : 'disconnected',
    canReconnect: !terminal,
    error: status.workflowStatus === 'FAILED'
      ? String(status.errorMessage || '研究任务执行失败') : progress.error
  }
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
    missingItems: Array.isArray(status?.evidencePack?.missingItems)
      ? status.evidencePack.missingItems.map(String) : []
  }
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
