import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  Bot,
  MessageSquare,
  Pin,
  LoaderCircle,
  Trash2,
  CheckCircle2,
  XCircle,
  FileText
} from 'lucide-react'
import { formatEvidenceCitations, formatToolDuration } from '../researchExecution.js'

function normalizeMarkdown(text = '') {
  const lines = String(text).replace(/\r\n?/g, '\n').split('\n')
  const output = []
  let inCodeBlock = false
  let blankLines = 0
  lines.forEach((line) => {
    const fence = line.trim().startsWith('```')
    if (inCodeBlock || fence) {
      output.push(inCodeBlock ? line : line.trimEnd())
      if (fence) inCodeBlock = !inCodeBlock
      blankLines = 0
      return
    }
    const clean = line.trimEnd()
    if (!clean.trim()) {
      if (blankLines === 0) output.push('')
      blankLines += 1
    } else {
      output.push(clean)
      blankLines = 0
    }
  })
  return output.join('\n').trim()
}

export function SectionTitle({ icon, title }) {
  return (
    <div className="section-title">
      {icon}
      <span>{title}</span>
    </div>
  )
}
export function Field({ label, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  )
}
export function Toggle({ label, checked, onChange }) {
  return (
    <div className="toggle-row">
      <span>{label}</span>
      <button
        className={`switch ${checked ? 'on' : ''}`}
        onClick={() => onChange(!checked)}
        aria-pressed={checked}
      >
        <i />
      </button>
    </div>
  )
}
export function Stat({ label, value }) {
  return (
    <div className="stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}
export function EmptyState() {
  return (
    <div className="empty">
      <div className="empty-icon">
        <Bot size={25} />
      </div>
      <h2>开始一次股票研究</h2>
      <p>选择股票代码后，输入问题或使用左侧快捷提问</p>
    </div>
  )
}
export function Message({ role, content, sources, pending, error }) {
  return (
    <article className={`message ${role}`}>
      <div className="message-label">
        {role === 'user' ? '你' : 'Agent'}
        <span>{role === 'assistant' ? <Bot size={13} /> : <MessageSquare size={13} />}</span>
      </div>
      <div className={`bubble ${error ? 'error' : ''}`}>
        {pending ? (
          <span className="loading">
            <i />
            <i />
            <i />
          </span>
        ) : role === 'assistant' && !error ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {normalizeMarkdown(formatEvidenceCitations(content, sources))}
          </ReactMarkdown>
        ) : (
          content
        )}
      </div>
    </article>
  )
}
export function SessionItem({ session, active, pinned, deleting, onClick, onPin, onDelete }) {
  return (
    <div className={`session-item ${active ? 'active' : ''}`}>
      <button className="session-main" onClick={onClick} disabled={deleting}>
        <strong>{session.title || '未命名会话'}</strong>
        <span>
          {session.orderId ? `股票 ${session.orderId}` : '未指定股票'} · {session.messageCount || 0}{' '}
          条消息
        </span>
        <small>{formatSessionTime(session.lastUpdateTime || session.createTime)}</small>
      </button>
      <div className="session-actions">
        <button
          className={`session-action ${pinned ? 'pinned' : ''}`}
          onClick={onPin}
          disabled={deleting}
          aria-label={pinned ? '取消置顶' : '置顶会话'}
          title={pinned ? '取消置顶' : '置顶会话'}
        >
          <Pin size={14} />
        </button>
        <button
          className="session-action delete"
          onClick={onDelete}
          disabled={deleting}
          aria-label={`删除${session.title || '未命名会话'}`}
          title="删除会话"
        >
          {deleting ? <LoaderCircle className="spin" size={14} /> : <Trash2 size={14} />}
        </button>
      </div>
    </div>
  )
}
function formatSessionTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString('zh-CN', {
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      })
}
export function ToolItem({ tool }) {
  return (
    <div className="tool-item">
      <div>
        <span className={`tool-dot ${tool.success ? 'done' : 'fail'}`} />
        {tool.toolName || '工具调用'}
        {tool.success ? (
          <CheckCircle2 className="tool-icon done" size={13} />
        ) : (
          <XCircle className="tool-icon fail" size={13} />
        )}
      </div>
      <small>{tool.errorMessage || formatToolDuration(tool.executionTime)}</small>
    </div>
  )
}
export function SourceItem({ source }) {
  const evidence = source.documentType === 'EVIDENCE'
  const sourceId = evidence && source.documentId ? `evidence-${source.documentId}` : undefined
  const detail =
    source.location || (source.documentType === 'WEB' ? '网页来源' : evidence ? '分析证据' : '')
  const content = (
    <>
      <FileText size={14} />
      <span>
        <strong>{source.documentTitle || source.source || '知识来源'}</strong>
        {detail ? (
          <small>
            {detail}
            {source.documentUrl ? ' · 点击查看原文' : ''}
          </small>
        ) : null}
      </span>
    </>
  )
  if (source.documentUrl)
    return (
      <a
        id={sourceId}
        className="source-item"
        href={source.documentUrl}
        target="_blank"
        rel="noreferrer"
      >
        {content}
      </a>
    )
  if (!evidence && source.documentId)
    return (
      <Link
        className="source-item"
        to={`/knowledge/documents/${encodeURIComponent(source.documentId)}`}
      >
        {content}
      </Link>
    )
  return (
    <div id={sourceId} className="source-item source-item-static">
      {content}
    </div>
  )
}
export function Muted({ children }) {
  return <p className="muted">{children}</p>
}
export function ResearchProgress({ run, onReconnect }) {
  const [now, setNow] = useState(() => globalThis.performance?.now?.() || Date.now())
  useEffect(() => {
    setNow(globalThis.performance?.now?.() || Date.now())
    if (run.connection === 'terminal') return undefined
    const timer = setInterval(() => setNow(globalThis.performance?.now?.() || Date.now()), 1000)
    return () => clearInterval(timer)
  }, [run.connection, run.startedAt])
  const elapsedSeconds = run.startedAt ? Math.max(0, Math.floor((now - run.startedAt) / 1000)) : 0
  const roleLabels = {
    FUNDAMENTAL: '基本面',
    TECHNICAL: '技术面',
    NEWS: '新闻',
    BULL: '看多',
    BEAR: '看空',
    RISK: '风险',
    JUDGE: '裁决'
  }
  const activeRoles = (run.activeRoleNodes || []).map((role) => roleLabels[role] || role)
  const current = activeRoles.length
    ? `正在并行审议：${activeRoles.join('、')} · 已耗时 ${elapsedSeconds} 秒`
    : run.lastEvent
      ? `最新事件：${run.lastEvent.node || run.lastEvent.eventType} · 已耗时 ${elapsedSeconds} 秒`
      : null
  return (
    <div className={`research-progress ${run.connection}`}>
      <div className="research-progress-head">
        <span>
          执行 ID：<code>{run.executionId}</code>
        </span>
        <em>
          {run.connection === 'terminal'
            ? '已结束'
            : run.connection === 'disconnected'
              ? '连接中断'
              : run.totalSteps == null
                ? `${run.completedSteps} 步已完成 · 正在确定总步骤`
                : `${run.completedSteps}/${run.totalSteps} 步 · ${run.percent}%`}
        </em>
      </div>
      <div
        className="research-progress-track"
        role="progressbar"
        aria-label="深度投研真实进度"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-valuenow={run.percent || 0}
      >
        <i style={{ width: `${run.percent || 0}%` }} />
      </div>
      <div className="research-timeline">
        {run.phases.map((phase) => (
          <div key={phase.id} className={`research-phase ${phase.status}`}>
            <i /> <span>{phase.label}</span>
          </div>
        ))}
      </div>
      {current ? <div className="research-current">{current}</div> : null}
      {run.retryCount > 0 || run.missingItems?.length ? (
        <div className="research-notices">
          {run.retryCount > 0 ? <span>已受控重试 {run.retryCount} 次</span> : null}
          {run.missingItems?.map((item) => (
            <span key={item}>数据限制：{item}</span>
          ))}
        </div>
      ) : null}
      {run.connection === 'disconnected' ? (
        <div className="research-reconnect">
          <span>{run.error || '已通过状态接口完成补偿读取，请按需重新连接事件流。'}</span>
          {run.canReconnect ? (
            <button type="button" onClick={onReconnect}>
              重新连接
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
