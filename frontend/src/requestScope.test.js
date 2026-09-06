import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequestScope } from './requestScope.js'

test('switching context invalidates old history, chat and status callbacks', async () => {
  const scope = createRequestScope()
  const old = scope.capture()
  let resolve
  const pending = new Promise((done) => {
    resolve = done
  })
  let displayed = 'new conversation'
  const callback = pending.then(() => {
    if (old()) displayed = 'old history'
  })
  scope.invalidate()
  const current = scope.capture()
  resolve()
  await callback
  assert.equal(displayed, 'new conversation')
  assert.equal(old(), false)
  assert.equal(current(), true)
  scope.invalidate()
  assert.equal(current(), false)
})
