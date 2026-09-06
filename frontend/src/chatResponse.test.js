import test from 'node:test'
import assert from 'node:assert/strict'
import { chatFailureMessage } from './chatResponse.js'

test('shows the backend recovery instruction instead of the generic failure message', () => {
  for (const content of [
    '已重置本次会话的对话上下文，请换一种更具体的问法。',
    '暂时无法清理本次会话的对话上下文，请稍后新建会话再试。'
  ]) {
    assert.equal(
      chatFailureMessage({
        success: false,
        sessionId: 'session-1',
        content,
        errorMessage: '对话处理失败，请稍后重试'
      }),
      content
    )
  }
})

test('falls back for HTTP errors, empty content and malformed error fields', () => {
  assert.equal(
    chatFailureMessage({ success: false, content: ' ', errorMessage: '参数错误' }),
    '参数错误'
  )
  assert.equal(
    chatFailureMessage({ content: 'not a ChatResponse', errorMessage: '服务不可用' }),
    '服务不可用'
  )
  assert.equal(
    chatFailureMessage({ success: false, content: {}, error: '会话已关闭' }),
    '会话已关闭'
  )
  assert.equal(chatFailureMessage({ message: '请求被拒绝' }), '请求被拒绝')
  assert.equal(chatFailureMessage(null), '接口请求失败')
})
