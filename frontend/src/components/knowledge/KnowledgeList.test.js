import test from 'node:test'
import assert from 'node:assert/strict'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

test('interrupted cleanup exposes the matching retry action and never re-enables unfinished cleanup', async () => {
  const server = await createServer({
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom'
  })
  try {
    const { default: KnowledgeList } = await server.ssrLoadModule(
      '/src/components/knowledge/KnowledgeList.jsx'
    )
    const render = (deleteStatus) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          {},
          createElement(KnowledgeList, {
            documents: [{ documentId: 'doc-1', title: '文档', enabled: false, deleteStatus }],
            loading: false,
            busyId: '',
            onDisable() {},
            onEnable() {},
            onDelete() {}
          })
        )
      )
    const deletion = render('DELETE_FAILED')
    assert.match(deletion, /重试删除/)
    assert.doesNotMatch(deletion, /重新启用/)
    const disabling = render('DISABLE_FAILED')
    assert.match(disabling, /重试禁用/)
    assert.doesNotMatch(disabling, /重新启用/)
    for (const status of ['DELETING', 'DISABLING']) {
      const pending = render(status)
      assert.match(pending, /清理中/)
      assert.doesNotMatch(pending, /重新启用/)
      assert.match(pending, /disabled=""/)
    }
    assert.match(render(null), /重新启用/)
  } finally {
    await server.close()
  }
})
