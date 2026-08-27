import { Ban, CalendarClock, Database, FileText, LoaderCircle, Trash2 } from 'lucide-react'
import { Link } from 'react-router-dom'

function formatTime(value) {
  if (!value) return '暂无时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '暂无时间' : date.toLocaleString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export default function KnowledgeList({ documents, loading, busyId, onDisable, onEnable, onDelete }) {
  if (loading) return <div className="knowledge-empty"><LoaderCircle className="spin" size={21} /><p>正在加载知识文档…</p></div>
  if (!documents.length) return <div className="knowledge-empty"><Database size={23} /><h3>知识库还没有文档</h3><p>从左侧添加第一篇文档，让 Agent 学会你的研究方法。</p></div>

  return <div className="knowledge-list">{documents.map((document) => {
    const enabled = document.enabled !== false
    const busy = busyId === document.documentId
    return <article className={`knowledge-card ${enabled ? '' : 'disabled'}`} key={document.documentId}>
      <div className="knowledge-card-main"><span className="knowledge-card-icon"><FileText size={17} /></span><div className="knowledge-card-copy"><div className="knowledge-card-title">{document.documentId ? <Link to={`/knowledge/documents/${encodeURIComponent(document.documentId)}`} state={{ document }}>{document.title || '未命名文档'}</Link> : <h3>{document.title || '未命名文档'}</h3>}<span className={`status-badge ${enabled ? 'active' : 'inactive'}`}>{enabled ? '已启用' : '已禁用'}</span></div><div className="knowledge-meta"><span>{document.documentType || 'MANUAL'}</span><span><Database size={12} />{document.chunkCount || 0} 个分块</span><span><CalendarClock size={12} />{formatTime(document.updateTime || document.createTime)}</span></div>{document.tags?.length ? <div className="knowledge-tags">{document.tags.map((tag, index) => <span key={`${tag}-${index}`}>#{tag}</span>)}</div> : null}</div></div>
      <div className="knowledge-card-actions">{enabled ? <button className="text-action warning" onClick={() => onDisable(document)} disabled={busy} title="禁用后不再参与 RAG 检索"><Ban size={14} />{busy ? '处理中…' : '禁用'}</button> : <button className="text-action enable" onClick={() => onEnable(document)} disabled={busy} title="重新向量化并启用文档">{busy ? <LoaderCircle className="spin" size={14} /> : <Database size={14} />}{busy ? '处理中…' : '重新启用'}</button>}<button className="text-action danger" onClick={() => onDelete(document)} disabled={busy}><Trash2 size={14} />删除</button></div>
    </article>
  })}</div>
}
