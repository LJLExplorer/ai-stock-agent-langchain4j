import { ArrowRight, BookOpen, CheckCircle2, RefreshCw, TriangleAlert } from 'lucide-react'
import { NavLink, Link } from 'react-router-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import KnowledgeForm from '../components/knowledge/KnowledgeForm.jsx'
import KnowledgeList from '../components/knowledge/KnowledgeList.jsx'

async function readResponse(response, fallback) {
  const data = await response.json().catch(() => ({}))
  if (!response.ok || data.success === false) throw new Error(data.errorMessage || data.message || fallback)
  return data
}

export default function KnowledgePage() {
  const [documents, setDocuments] = useState([])
  const [loading, setLoading] = useState(true)
  const [formBusy, setFormBusy] = useState(false)
  const [busyId, setBusyId] = useState('')
  const [notice, setNotice] = useState(null)
  const documentsRequestRef = useRef(0)

  const loadDocuments = useCallback(async () => {
    const requestId = ++documentsRequestRef.current
    setLoading(true)
    try {
      const response = await fetch('/api/knowledge/documents')
      const data = await readResponse(response, '知识文档加载失败')
      if (requestId === documentsRequestRef.current) setDocuments(Array.isArray(data) ? data : [])
    } catch (error) {
      if (requestId === documentsRequestRef.current) setNotice({ type: 'error', message: error.message })
    } finally {
      if (requestId === documentsRequestRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => { loadDocuments() }, [loadDocuments])

  const addDocument = async (payload) => {
    setFormBusy(true)
    setNotice(null)
    try {
      const response = await fetch('/api/knowledge/documents', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
      await readResponse(response, '文档保存失败')
      await loadDocuments()
      setNotice({ type: 'success', message: '文档已保存，正在参与 RAG 检索。' })
      return true
    } catch (error) {
      setNotice({ type: 'error', message: error.message })
      return false
    } finally { setFormBusy(false) }
  }

  const disableDocument = async (document) => {
    if (!window.confirm(`确定禁用“${document.title || '未命名文档'}”吗？禁用后 Agent 将不再检索它。`)) return
    setBusyId(document.documentId); setNotice(null)
    try {
      const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(document.documentId)}/disable`, { method: 'POST' })
      await readResponse(response, '文档禁用失败')
      await loadDocuments()
      setNotice({ type: 'success', message: '文档已禁用，不会再参与 RAG 检索。' })
    } catch (error) { setNotice({ type: 'error', message: error.message }) }
    finally { setBusyId('') }
  }

  const deleteDocument = async (document) => {
    if (!window.confirm(`确定永久删除“${document.title || '未命名文档'}”吗？此操作不可撤销。`)) return
    setBusyId(document.documentId); setNotice(null)
    try {
      const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(document.documentId)}`, { method: 'DELETE' })
      await readResponse(response, '文档删除失败')
      setDocuments((items) => items.filter((item) => item.documentId !== document.documentId))
      setNotice({ type: 'success', message: '文档及其向量已删除。' })
    } catch (error) { setNotice({ type: 'error', message: error.message }) }
    finally { setBusyId('') }
  }

  const enableDocument = async (document) => {
    if (!window.confirm(`确定重新启用“${document.title || '未命名文档'}”吗？系统会重新生成它的 RAG 向量。`)) return
    setBusyId(document.documentId); setNotice(null)
    try {
      const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(document.documentId)}/enable`, { method: 'POST' })
      await readResponse(response, '文档重新启用失败')
      await loadDocuments()
      setNotice({ type: 'success', message: '文档已重新启用，并已恢复 RAG 检索。' })
    } catch (error) { setNotice({ type: 'error', message: error.message }) }
    finally { setBusyId('') }
  }

  const enabledCount = documents.filter((document) => document.enabled !== false).length
  return <div className="knowledge-page"><header className="topbar knowledge-topbar"><div className="brand"><span className="brand-mark"><BookOpen size={17} /></span><div><strong>Stock Insight Agent</strong><small>股票研究与预测工作台</small></div></div><nav className="route-nav" aria-label="主导航"><NavLink to="/" end><ArrowRight className="route-back-icon" size={15} />问答</NavLink><NavLink to="/knowledge"><BookOpen size={15} />知识库</NavLink></nav></header>
    <header className="knowledge-page-head"><div><div className="eyebrow">KNOWLEDGE BASE</div><h1><BookOpen size={23} />知识库</h1><p>管理会被股票 Agent 检索的研究资料和业务知识。</p></div><Link className="button subtle back-chat" to="/"><ArrowRight size={15} />返回问答</Link></header>
    {notice ? <div className={`knowledge-notice ${notice.type}`} role={notice.type === 'error' ? 'alert' : 'status'} aria-live="polite">{notice.type === 'error' ? <TriangleAlert size={16} /> : <CheckCircle2 size={16} />}{notice.message}</div> : null}
    <main className="knowledge-layout"><section className="panel knowledge-form-panel"><KnowledgeForm busy={formBusy} onSubmit={addDocument} /></section><section className="panel knowledge-list-panel"><div className="knowledge-list-head"><div><div className="section-title"><BookOpen size={14} /><span>全部文档</span></div><p>{documents.length} 篇文档 · {enabledCount} 篇正在参与 RAG</p></div><button className="icon-button" onClick={loadDocuments} disabled={loading} aria-label="刷新文档列表" title="刷新文档列表"><RefreshCw className={loading ? 'spin' : ''} size={16} /></button></div><KnowledgeList documents={documents} loading={loading} busyId={busyId} onDisable={disableDocument} onEnable={enableDocument} onDelete={deleteDocument} /></section></main>
  </div>
}
