import test from 'node:test'
import assert from 'node:assert/strict'

import {
  getResearchStatus,
  isTerminalRunEvent,
  parseRunEvent,
  startResearch,
  subscribeResearch
} from './researchExecution.js'

test('starts a deep research execution and validates the returned handle', async () => {
  let captured
  const fetchImpl = async (url, options) => {
    captured = { url, options }
    return response(202, { executionId: 'exec-123', sessionId: 'session-1', status: 'ACCEPTED' })
  }
  const request = { userId: 'user-1', message: '分析600519', researchMode: 'DEEP' }

  const result = await startResearch(request, { fetchImpl })

  assert.equal(result.executionId, 'exec-123')
  assert.equal(captured.url, '/api/research/executions')
  assert.equal(captured.options.method, 'POST')
  assert.deepEqual(JSON.parse(captured.options.body), request)
  await assert.rejects(
    () => startResearch(request, { fetchImpl: async () => response(202, { status: 'ACCEPTED' }) }),
    /executionId/
  )
})

test('parses controlled RunEvent metadata and identifies terminal events', () => {
  const running = parseRunEvent(JSON.stringify({
    executionId: 'exec-1', sequence: 3, eventType: 'NODE_COMPLETED', node: 'MARKET_DATA', summary: 'status=completed'
  }))
  const completed = parseRunEvent({
    data: JSON.stringify({ executionId: 'exec-1', sequence: 4, eventType: 'WORKFLOW_COMPLETED', node: 'ANSWER' })
  })

  assert.equal(running.sequence, 3)
  assert.equal(isTerminalRunEvent(running), false)
  assert.equal(isTerminalRunEvent(completed), true)
  assert.throws(() => parseRunEvent('{"executionId":"exec-1","sequence":0,"eventType":"NODE_STARTED"}'), /RunEvent/)
  assert.throws(() => parseRunEvent('{"executionId":"exec-1","sequence":1,"eventType":"PROMPT_BODY"}'), /eventType/)
})

test('loads owner-filtered execution status', async () => {
  let requestedUrl
  const state = await getResearchStatus('exec-1', 'user/a', {
    fetchImpl: async (url) => {
      requestedUrl = url
      return response(200, { executionId: 'exec-1', workflowStatus: 'RUNNING' })
    }
  })

  assert.equal(requestedUrl, '/api/research/executions/exec-1?userId=user%2Fa')
  assert.equal(state.workflowStatus, 'RUNNING')
})

test('closes EventSource on terminal event', () => {
  const source = new FakeEventSource()
  const received = []
  let terminal
  const subscription = subscribeResearch({
    executionId: 'exec-1', userId: 'user-1',
    eventSourceFactory: (url) => { source.url = url; return source },
    onEvent: (event) => received.push(event),
    onTerminal: (event) => { terminal = event }
  })

  source.emit('NODE_STARTED', { executionId: 'exec-1', sequence: 1, eventType: 'NODE_STARTED', node: 'INIT' })
  source.emit('WORKFLOW_COMPLETED', { executionId: 'exec-1', sequence: 2, eventType: 'WORKFLOW_COMPLETED', node: 'ANSWER' })

  assert.equal(source.url, '/api/research/executions/exec-1/events?userId=user-1')
  assert.equal(received.length, 2)
  assert.equal(terminal.sequence, 2)
  assert.equal(source.closed, true)
  subscription.close()
})

test('closes failed EventSource and performs one status compensation request', async () => {
  const source = new FakeEventSource()
  let compensated
  let failure
  subscribeResearch({
    executionId: 'exec-1', userId: 'user-1',
    eventSourceFactory: () => source,
    fetchImpl: async () => response(200, { executionId: 'exec-1', workflowStatus: 'RUNNING' }),
    onStatus: (status) => { compensated = status },
    onError: (error) => { failure = error }
  })

  await source.fail(new Error('stream disconnected'))

  assert.equal(source.closed, true)
  assert.equal(compensated.workflowStatus, 'RUNNING')
  assert.match(failure.message, /disconnected/)
})

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body
  }
}

class FakeEventSource {
  constructor() {
    this.listeners = new Map()
    this.closed = false
    this.onerror = null
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener)
  }

  emit(type, data) {
    this.listeners.get(type)?.({ data: JSON.stringify(data) })
  }

  async fail(error) {
    await this.onerror?.(error)
  }

  close() {
    this.closed = true
  }
}
