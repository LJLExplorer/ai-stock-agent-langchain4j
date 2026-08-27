import { ArrowLeft, ArrowRight, BookOpen, CalendarClock, Database, FileText, LoaderCircle, TriangleAlert } from 'lucide-react'
import { Link, NavLink, useLocation, useParams } from 'react-router-dom'
import { useEffect, useState } from 'react'

function formatTime(value) {
  if (!value) return '暂无时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '暂无时间' : date.toLocaleString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function readResponse(response, fallback) {
  const data = await response.json().catch(() => ({}))
  if (!response.ok || data.success === false) {
    const error = new Error(data.errorMessage || data.message || fallback)
    error.status = response.status
    throw error
  }
  return data
}

async function loadDocument(documentId) {
  try {
    const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(documentId)}`)
    return await readResponse(response, '知识文档加载失败')
  } catch (error) {
    if (error.status !== 404) throw error
    const response = await fetch('/api/knowledge/documents')
    const documents = await readResponse(response, '知识文档加载失败')
    const document = Array.isArray(documents)
      ? documents.find((item) => item.documentId === documentId)
      : null
    if (!document) throw error
    return document
  }
}

export default function KnowledgeDocumentDetailPage() {
  const { documentId } = useParams()
  const location = useLocation()
  const linkedDocument = location.state?.document?.documentId === documentId ? location.state.document : null
  const [document, setDocument] = useState(null)
  const [state, setState] = useState('loading')
  const [message, setMessage] = useState('')

  useEffect(() => {
    let active = true
    if (linkedDocument) {
      setDocument(linkedDocument)
      setState('ready')
      setMessage('')
      return () => { active = false }
    }
    setState('loading')
    setMessage('')
    loadDocument(documentId)
      .then((data) => {
        if (!active) return
        setDocument(data)
        setState('ready')
      })
      .catch((error) => {
        if (!active) return
        setState(error.status === 404 ? 'not-found' : 'error')
        setMessage(error.message)
      })
    return () => { active = false }
  }, [documentId, linkedDocument])

  const pageTitle = document?.title || '知识文档详情'
  return <div className="knowledge-page knowledge-detail-page"><header className="topbar knowledge-topbar"><div className="brand"><span className="brand-mark"><BookOpen size={17} /></span><div><strong>Stock Insight Agent</strong><small>股票研究与预测工作台</small></div></div><nav className="route-nav" aria-label="主导航"><NavLink to="/" end><ArrowRight className="route-back-icon" size={15} />问答</NavLink><NavLink to="/knowledge"><BookOpen size={15} />知识库</NavLink></nav></header>
    <main className="knowledge-detail-shell" tabIndex="-1"><Link className="detail-back" to="/knowledge"><ArrowLeft size={15} />返回知识库</Link>
      {state === 'loading' ? <div className="knowledge-detail-state"><LoaderCircle className="spin" size={22} /><p>正在加载知识文档…</p></div> : null}
      {state === 'not-found' ? <div className="knowledge-detail-state" role="alert"><TriangleAlert size={24} /><h1>未找到知识文档</h1><p>{message || '该文档可能已被删除。'}</p><Link className="button subtle" to="/knowledge">返回知识库</Link></div> : null}
      {state === 'error' ? <div className="knowledge-detail-state" role="alert"><TriangleAlert size={24} /><h1>知识文档加载失败</h1><p>{message || '请稍后重试。'}</p><Link className="button subtle" to="/knowledge">返回知识库</Link></div> : null}
      {state === 'ready' ? <article className="panel knowledge-detail"><header className="knowledge-detail-head"><div><div className="eyebrow">KNOWLEDGE DOCUMENT</div><h1><FileText size={23} />{pageTitle}</h1><div className="knowledge-meta"><span>{document.documentType || 'MANUAL'}</span><span><Database size={12} />{document.chunkCount || 0} 个分块</span><span><CalendarClock size={12} />更新于 {formatTime(document.updateTime || document.createTime)}</span></div></div><span className={`status-badge ${document.enabled !== false ? 'active' : 'inactive'}`}>{document.enabled !== false ? '已启用' : '已禁用'}</span></header>
        {document.tags?.length ? <div className="knowledge-tags detail-tags">{document.tags.map((tag, index) => <span key={`${tag}-${index}`}>#{tag}</span>)}</div> : null}
        <dl className="knowledge-detail-info"><div><dt>创建时间</dt><dd>{formatTime(document.createTime)}</dd></div><div><dt>更新时间</dt><dd>{formatTime(document.updateTime)}</dd></div></dl>
        <section className="knowledge-document-content" aria-labelledby="knowledge-content-title"><h2 id="knowledge-content-title">正文内容</h2><pre>{document.rawContent || '该文档没有可显示的正文内容。'}</pre></section>
      </article> : null}
    </main>
  </div>
}
