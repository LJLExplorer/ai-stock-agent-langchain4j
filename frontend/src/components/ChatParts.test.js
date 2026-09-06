import test from 'node:test'
import assert from 'node:assert/strict'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { createResearchProgress } from '../researchExecution.js'

test('deep research progress renders when a run starts and roles become active', async () => {
  const server = await createServer({
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom'
  })
  try {
    const { ResearchProgress } = await server.ssrLoadModule('/src/components/ChatParts.jsx')
    const run = createResearchProgress('render-check')
    const initial = renderToStaticMarkup(createElement(ResearchProgress, { run }))
    assert.match(initial, /render-check/)
    assert.match(initial, /深度投研真实进度/)
    assert.match(initial, /正在确定总步骤/)

    const active = renderToStaticMarkup(
      createElement(ResearchProgress, {
        run: { ...run, activeRoleNodes: ['FUNDAMENTAL', 'RISK'] }
      })
    )
    assert.match(active, /正在并行审议：基本面、风险/)
  } finally {
    await server.close()
  }
})
