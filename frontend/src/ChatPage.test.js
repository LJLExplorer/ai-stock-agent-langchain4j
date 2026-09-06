import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { act, createElement } from 'react'
import { createServer } from 'vite'

function deferred() {
  let resolve
  const promise = new Promise((done) => {
    resolve = done
  })
  return { promise, resolve }
}

async function mountChat(t) {
  const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/' })
  const previous = new Map()
  for (const [key, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    IS_REACT_ACT_ENVIRONMENT: true
  })) {
    previous.set(key, Object.getOwnPropertyDescriptor(globalThis, key))
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value })
  }
  const requests = []
  const history = new Map([
    ['session-a', deferred()],
    ['session-b', deferred()]
  ])
  const sessions = [...history.keys()].map((sessionId) => ({ sessionId, title: sessionId }))
  const response = (data) =>
    new Response(JSON.stringify(data), {
      headers: { 'Content-Type': 'application/json' }
    })
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    if (url.startsWith('/api/health')) return response({ status: 'UP' })
    if (url.endsWith('/sessions')) return response(sessions)
    const sessionId = url.match(/\/sessions\/([^/]+)\/messages/)?.[1]
    if (sessionId) return history.get(sessionId).promise
    if (url === '/api/chat/send')
      return response({ success: true, sessionId: 'session-a', content: 'new answer' })
    throw new Error(`Unexpected request: ${url}`)
  })
  const alerts = []
  dom.window.alert = (message) => alerts.push(message)
  const server = await createServer({
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom'
  })
  const { createRoot } = await import('react-dom/client')
  const { MemoryRouter } = await import('react-router-dom')
  const { default: ChatPage } = await server.ssrLoadModule('/src/ChatPage.jsx')
  const root = createRoot(dom.window.document.getElementById('root'))
  t.after(async () => {
    await act(async () => root.unmount())
    await server.close()
    dom.window.close()
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  })
  await act(async () => root.render(createElement(MemoryRouter, null, createElement(ChatPage))))
  const query = (selector) => dom.window.document.querySelector(selector)
  const select = async (index) =>
    act(async () => dom.window.document.querySelectorAll('.session-main')[index].click())
  const type = async (text) =>
    act(async () => {
      const textarea = query('.composer textarea')
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, 'value').set.call(
        textarea,
        text
      )
      textarea.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
    })
  const send = async () =>
    act(async () =>
      query('.composer').dispatchEvent(
        new dom.window.Event('submit', { bubbles: true, cancelable: true })
      )
    )
  return { dom, query, select, type, send, requests, history, response, alerts }
}

test('sending waits for session history and then preserves it beside the new reply', async (t) => {
  const page = await mountChat(t)
  await page.type('follow-up')
  await page.select(0)
  assert.equal(page.query('.composer button').disabled, true)
  await page.send()
  await act(async () =>
    page.query('.composer textarea').dispatchEvent(
      new page.dom.window.KeyboardEvent('keydown', {
        key: 'Enter',
        bubbles: true,
        cancelable: true
      })
    )
  )
  assert.equal(page.requests.filter(({ url }) => url === '/api/chat/send').length, 0)
  await act(async () =>
    page.history.get('session-a').resolve(
      page.response([
        { role: 'USER', content: 'old question' },
        { role: 'ASSISTANT', content: 'old answer' }
      ])
    )
  )
  assert.equal(page.query('.composer button').disabled, false)
  await page.send()
  assert.equal(page.requests.filter(({ url }) => url === '/api/chat/send').length, 1)
  const messages = page.query('.message-list').textContent
  for (const content of ['old question', 'old answer', 'follow-up', 'new answer']) {
    assert.ok(messages.includes(content), `Missing message: ${content}`)
  }
})

test('an old history response cannot unlock or replace a newly selected session', async (t) => {
  const page = await mountChat(t)
  await page.type('follow-up')
  await page.select(0)
  await page.select(1)
  await act(async () =>
    page.history
      .get('session-a')
      .resolve(page.response([{ role: 'USER', content: 'stale history' }]))
  )
  assert.equal(page.query('.composer button').disabled, true)
  assert.ok(!page.query('.message-list').textContent.includes('stale history'))
  await act(async () =>
    page.history
      .get('session-b')
      .resolve(page.response([{ role: 'USER', content: 'current history' }]))
  )
  assert.equal(page.query('.composer button').disabled, false)
  assert.ok(page.query('.message-list').textContent.includes('current history'))
})

test('a failed history request releases the loading state', async (t) => {
  const page = await mountChat(t)
  await page.type('follow-up')
  await page.select(0)
  await act(async () => page.history.get('session-a').resolve(new Response('', { status: 500 })))
  assert.equal(page.query('.composer button').disabled, false)
  assert.deepEqual(page.alerts, ['会话加载失败'])
})
