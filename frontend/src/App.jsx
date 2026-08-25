import { useEffect, useRef, useState } from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import KnowledgePage from './pages/KnowledgePage.jsx'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  Activity, Bot, CheckCircle2, Clipboard, Database, FileText, History,
  LoaderCircle, MessageSquare, PanelRight, Pin, Plus, Search, Send, Settings2,
  Sparkles, Trash2, UserRound, Wrench, XCircle
} from 'lucide-react'

const quickPrompts = [
  ['实时行情', '请查询当前股票的实时行情，并说明今天的涨跌情况。'],
  ['技术分析', '请基于真实日K分析当前股票的技术趋势。'],
  ['财务数据', '请查询当前股票最新财务数据和报告期。'],
  ['新闻与风险', '请搜索当前股票最近的新闻、公告和风险信息。']
]

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

function App() {
  return <Routes>
    <Route path="/" element={<ChatPage />} />
    <Route path="/knowledge" element={<KnowledgePage />} />
  </Routes>
}

function ChatPage() {
  const [userId, setUserId] = useState('demo-user')
  const [sessionId, setSessionId] = useState('')
  const [sessionTitle, setSessionTitle] = useState('')
  const [sessions, setSessions] = useState([])
  const [sessionFilter, setSessionFilter] = useState('')
  const [sessionsBusy, setSessionsBusy] = useState(false)
  const [pinnedSessionIds, setPinnedSessionIds] = useState([])
  const [symbol, setSymbol] = useState('600519')
  const [message, setMessage] = useState('')
  const [rag, setRag] = useState(true)
  const [tools, setTools] = useState(true)
  const [messages, setMessages] = useState([])
  const [details, setDetails] = useState({ tools: [], sources: [], duration: null })
  const [busy, setBusy] = useState(false)
  const [deletingSessionId, setDeletingSessionId] = useState('')
  const [savingTitle, setSavingTitle] = useState(false)
  const [connection, setConnection] = useState('ready')
  const [healthStatus, setHealthStatus] = useState('checking')
  const [healthLatency, setHealthLatency] = useState(null)
  const [memoryContent, setMemoryContent] = useState('')
  const [memoryTags, setMemoryTags] = useState('')
  const [memoryBusy, setMemoryBusy] = useState(false)
  const inputRef = useRef(null)
  const sessionsRequestRef = useRef(0)

  const resetConversation = () => { setMessages([]); setDetails({ tools: [], sources: [], duration: null }) }

  const loadSessions = async (requestedUserId = userId) => {
    const normalizedUserId = requestedUserId.trim()
    if (!normalizedUserId) { setSessions([]); return }
    const requestId = ++sessionsRequestRef.current
    setSessionsBusy(true)
    try {
      const response = await fetch(`/api/chat/users/${encodeURIComponent(normalizedUserId)}/sessions`)
      if (!response.ok) throw new Error('会话列表加载失败')
      const data = await response.json()
      if (requestId === sessionsRequestRef.current) setSessions(Array.isArray(data) ? data : [])
    } catch (error) {
      if (requestId === sessionsRequestRef.current) window.alert(error.message)
    } finally {
      if (requestId === sessionsRequestRef.current) setSessionsBusy(false)
    }
  }

  useEffect(() => {
    setSessionId('')
    setSessionTitle('')
    resetConversation()
    setSessionFilter('')
    loadSessions(userId)
  }, [userId])

  useEffect(() => {
    if (!sessionId || sessionTitle) return
    const activeSession = sessions.find((session) => session.sessionId === sessionId)
    if (activeSession?.title) setSessionTitle(activeSession.title)
  }, [sessions, sessionId, sessionTitle])

  useEffect(() => {
    let disposed = false
    const checkHealth = async () => {
      const started = performance.now()
      try {
        const response = await fetch(`/api/health?ts=${Date.now()}`, { cache: 'no-store' })
        const data = await response.json().catch(() => ({}))
        if (!response.ok || data.status !== 'UP') throw new Error('服务状态异常')
        if (!disposed) {
          setHealthStatus('ready')
          setHealthLatency(Math.round(performance.now() - started))
        }
      } catch {
        if (!disposed) { setHealthStatus('error'); setHealthLatency(null) }
      }
    }
    checkHealth()
    const timer = window.setInterval(checkHealth, 15000)
    return () => { disposed = true; window.clearInterval(timer) }
  }, [])

  const createSession = async () => {
    const normalizedUserId = userId.trim()
    if (!normalizedUserId) return window.alert('请填写用户 ID')
    if (busy || sessionsBusy) return
    setSessionsBusy(true)
    try {
      const params = new URLSearchParams({ userId: normalizedUserId })
      if (symbol.trim()) params.set('orderId', symbol.trim())
      const response = await fetch(`/api/chat/sessions?${params}`, { method: 'POST' })
      const data = await response.json()
      if (!response.ok || !data.sessionId) throw new Error(data.message || '新建会话失败')
      setSessionId(data.sessionId)
      setSessionTitle(data.title || '')
      resetConversation()
      setSessions((items) => [data, ...items.filter((item) => item.sessionId !== data.sessionId)])
    } catch (error) { window.alert(error.message) }
    finally { setSessionsBusy(false) }
  }

  const clearSession = () => { setSessionId(''); setSessionTitle(''); resetConversation() }

  const saveSessionTitle = async () => {
    const title = sessionTitle.trim()
    if (!sessionId || !title || savingTitle) return
    setSavingTitle(true)
    try {
      const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/title?userId=${encodeURIComponent(userId.trim())}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title })
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(data.message || '标题保存失败')
      setSessionTitle(data.title || title)
      setSessions((items) => items.map((item) => item.sessionId === sessionId ? { ...item, title: data.title || title } : item))
    } catch (error) { window.alert(error.message) }
    finally { setSavingTitle(false) }
  }

  const deleteSession = async (session) => {
    if (busy || deletingSessionId) return
    const title = session.title || '未命名会话'
    if (!window.confirm(`确定删除“${title}”吗？会话消息也会一并删除。`)) return
    setDeletingSessionId(session.sessionId)
    try {
      const response = await fetch(`/api/chat/sessions/${encodeURIComponent(session.sessionId)}?userId=${encodeURIComponent(userId.trim())}`, { method: 'DELETE' })
      const data = await response.json().catch(() => ({}))
      if (!response.ok || data.success === false) throw new Error(data.message || '会话删除失败')
      setSessions((items) => items.filter((item) => item.sessionId !== session.sessionId))
      if (session.sessionId === sessionId) clearSession()
    } catch (error) { window.alert(error.message) }
    finally { setDeletingSessionId('') }
  }

  const togglePin = (sessionIdToToggle) => {
    setPinnedSessionIds((ids) => ids.includes(sessionIdToToggle)
      ? ids.filter((id) => id !== sessionIdToToggle)
      : [sessionIdToToggle, ...ids])
  }
  const submit = async (event) => {
    event?.preventDefault()
    const text = message.trim()
    if (!text || busy) return
    if (!userId.trim()) return window.alert('请填写用户 ID')
    const prompt = symbol.trim() ? `${text}\n当前用户正在咨询股票：${symbol.trim()}` : text
    setMessage('')
    setMessages((items) => [...items, { role: 'user', content: text }, { role: 'assistant', content: '', pending: true }])
    setBusy(true); setConnection('loading')
    const started = performance.now()
    try {
      const response = await fetch('/api/chat/send', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: sessionId || null, userId: userId.trim(), message: prompt, orderId: symbol.trim() || null, enableRag: rag, enableTools: tools })
      })
      const raw = await response.text()
      let data
      try { data = raw ? JSON.parse(raw) : {} } catch { throw new Error(raw || `接口请求失败（HTTP ${response.status}）`) }
      if (!response.ok || data.success === false) throw new Error(data.errorMessage || '接口请求失败')
      setSessionId(data.sessionId || '')
      setMessages((items) => [...items.slice(0, -1), { role: 'assistant', content: data.content || '接口返回空内容' }])
      setDetails({ tools: data.toolInvocations || [], sources: data.knowledgeSources || [], duration: Math.round(performance.now() - started) })
      await loadSessions(userId)
      setConnection('ready')
    } catch (error) {
      setMessages((items) => [...items.slice(0, -1), { role: 'assistant', content: `请求失败：${error.message}`, error: true }])
      setConnection('error')
    } finally { setBusy(false) }
  }

  const loadHistory = async () => {
    if (!sessionId.trim()) return
    try {
      const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId.trim())}/messages?userId=${encodeURIComponent(userId.trim())}`)
      if (!response.ok) throw new Error('会话加载失败')
      const data = await response.json()
      setMessages(data.map((item) => ({ role: item.role?.toLowerCase() === 'user' ? 'user' : 'assistant', content: item.content || '' })))
    } catch (error) { window.alert(error.message) }
  }

  const selectSession = async (session) => {
    if (busy || session.sessionId === sessionId) return
    setSessionId(session.sessionId)
    setSessionTitle(session.title || '')
    if (session.orderId) setSymbol(session.orderId)
    resetConversation()
    try {
      const response = await fetch(`/api/chat/sessions/${encodeURIComponent(session.sessionId)}/messages?userId=${encodeURIComponent(userId.trim())}`)
      if (!response.ok) throw new Error('会话加载失败')
      const data = await response.json()
      setMessages(data.map((item) => ({ role: item.role?.toLowerCase() === 'user' ? 'user' : 'assistant', content: item.content || '' })))
    } catch (error) { window.alert(error.message) }
  }

  const visibleSessions = sessions.filter((session) => {
    const query = sessionFilter.trim().toLowerCase()
    if (!query) return true
    return [session.title, session.orderId, session.sessionId].filter(Boolean).some((value) => value.toLowerCase().includes(query))
  }).sort((a, b) => Number(pinnedSessionIds.includes(b.sessionId)) - Number(pinnedSessionIds.includes(a.sessionId)))

  const copySession = async () => { if (sessionId) await navigator.clipboard?.writeText(sessionId) }
  const saveMemory = async () => {
    const content = memoryContent.trim()
    if (!userId.trim()) return window.alert('请填写用户 ID')
    if (!content || memoryBusy) return
    setMemoryBusy(true)
    try {
      const response = await fetch('/api/memories', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: userId.trim(), content, tags: memoryTags.split(',').map((tag) => tag.trim()).filter(Boolean) })
      })
      const data = await response.json()
      if (!response.ok) throw new Error(data.message || '长期记忆保存失败')
      setMemoryContent(''); setMemoryTags('')
      window.alert('长期记忆已保存')
    } catch (error) { window.alert(error.message) }
    finally { setMemoryBusy(false) }
  }
  const choosePrompt = (prompt) => { setMessage(prompt.replace('当前股票', symbol || '当前股票')); inputRef.current?.focus() }

  return <div className="app-shell">
    <header className="topbar">
      <div className="brand"><span className="brand-mark"><Activity size={17} /></span><div><strong>Stock Insight Agent</strong><small>股票研究与预测工作台</small></div></div>
      <div className="top-actions"><nav className="route-nav" aria-label="主导航"><NavLink to="/" end><MessageSquare size={15} />问答</NavLink><NavLink to="/knowledge"><Database size={15} />知识库</NavLink></nav><label className="top-user"><UserRound size={15} /><span>用户 ID</span><input value={userId} onChange={(e) => setUserId(e.target.value)} aria-label="用户 ID" /></label><span className={`connection ${healthStatus}`} title={healthLatency ? `最近检测 ${healthLatency} ms` : '每 15 秒自动检测'}><i />{healthStatus === 'checking' ? '检测服务中' : healthStatus === 'error' ? '服务异常' : '服务可用'}{healthLatency && healthStatus === 'ready' ? <small>{healthLatency}ms</small> : null}</span><button className="button" onClick={createSession} disabled={sessionsBusy || busy}><Plus size={15} />新建会话</button></div>
    </header>
    <main className="workspace">
      <aside className="panel sidebar">
        <SectionTitle icon={<Settings2 size={14} />} title="会话设置" />
        <div className="history-section">
          <SectionTitle icon={<History size={14} />} title="历史会话" />
          <div className="history-search"><Search size={14} /><input value={sessionFilter} onChange={(e) => setSessionFilter(e.target.value)} placeholder="搜索标题、股票或 ID" /></div>
          <div className="session-list">{sessionsBusy && !sessions.length ? <Muted>会话加载中…</Muted> : visibleSessions.length ? visibleSessions.map((session) => <SessionItem key={session.sessionId} session={session} active={session.sessionId === sessionId} pinned={pinnedSessionIds.includes(session.sessionId)} deleting={deletingSessionId === session.sessionId} onClick={() => selectSession(session)} onPin={() => togglePin(session.sessionId)} onDelete={() => deleteSession(session)} />) : <Muted>{sessionFilter ? '没有匹配的会话' : '还没有历史会话'}</Muted>}</div>
        </div>
        <SectionTitle icon={<Sparkles size={14} />} title="分析上下文" />
        <Field label="当前股票代码"><input value={symbol} placeholder="例如 600519" onChange={(e) => setSymbol(e.target.value)} /></Field>
        <Toggle label="启用 RAG" checked={rag} onChange={setRag} />
        <Toggle label="启用工具调用" checked={tools} onChange={setTools} />
        <div className="memory-section">
          <SectionTitle icon={<Database size={14} />} title="长期记忆" />
          <p className="memory-hint">主动保存用户偏好，后续对话会按语义召回。</p>
          <textarea className="memory-input" value={memoryContent} onChange={(e) => setMemoryContent(e.target.value)} placeholder="例如：我偏好关注新能源和半导体行业" />
          <input value={memoryTags} onChange={(e) => setMemoryTags(e.target.value)} placeholder="标签，用逗号分隔" />
          <button className="button subtle full memory-button" onClick={saveMemory} disabled={memoryBusy || !memoryContent.trim()}>{memoryBusy ? '保存中…' : '保存为长期记忆'}</button>
        </div>
        <button className="button subtle full" onClick={clearSession}><Trash2 size={15} />清空当前会话</button>
      </aside>

      <section className="panel conversation">
        <div className="conversation-head"><div className="conversation-title"><div className="eyebrow">RESEARCH CHAT</div><div className="title-row"><input className="conversation-title-input" value={sessionTitle} maxLength={80} placeholder="研究对话" onChange={(e) => setSessionTitle(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') saveSessionTitle() }} disabled={!sessionId} /><button className="icon-button title-save" title={sessionId ? '保存会话标题' : '新建会话后可编辑标题'} onClick={saveSessionTitle} disabled={!sessionId || !sessionTitle.trim() || savingTitle}>{savingTitle ? <LoaderCircle className="spin" size={15} /> : <CheckCircle2 size={15} />}</button></div><p>{sessionId ? `当前会话 ID：${sessionId}` : '输入问题，Agent 会按需调用行情、技术、财务和资讯工具'}</p></div><button className="button subtle" onClick={copySession} disabled={!sessionId}><Clipboard size={15} />复制 ID</button></div>
        <div className="message-list">{messages.length === 0 ? <EmptyState /> : messages.map((item, index) => <Message key={index} {...item} />)}</div>
        <form className="composer" onSubmit={submit}><textarea ref={inputRef} value={message} onChange={(e) => setMessage(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(e) } }} placeholder="例如：帮我分析 600519 的行情、技术面和近期风险……" /><div className="composer-foot"><span>Enter 发送 · Shift + Enter 换行</span><button className="button primary" disabled={busy || !message.trim()}>{busy ? <LoaderCircle className="spin" size={16} /> : <Send size={16} />}发送分析</button></div></form>
      </section>

      <aside className="panel inspector">
        <SectionTitle icon={<PanelRight size={14} />} title="执行检查" />
        <div className="sample-block">
          <div className="block-heading"><span><Sparkles size={14} />试试这些</span><em>快捷提问</em></div>
          <div className="sample-list">
            {quickPrompts.map(([label, prompt]) => <button key={label} type="button" className="sample-button" onClick={() => choosePrompt(prompt)}>
              <span>{label}</span><MessageSquare size={13} />
            </button>)}
          </div>
        </div>
        <div className="stat-grid"><Stat label="会话" value={sessionId ? '已建立' : '未发送'} /><Stat label="响应时间" value={details.duration ? `${details.duration} ms` : '-'} /></div>
        <div className="inspector-block"><div className="block-heading"><span><Wrench size={14} />工具调用</span><em>{details.tools.length}</em></div>{details.tools.length ? details.tools.map((tool, index) => <ToolItem key={index} tool={tool} />) : <Muted>发送请求后显示工具执行结果</Muted>}</div>
        <div className="inspector-block"><div className="block-heading"><span><Database size={14} />知识来源</span><em>{details.sources.length}</em></div>{details.sources.length ? details.sources.map((source, index) => <SourceItem key={index} source={source} />) : <Muted>启用 RAG 或新闻检索后显示引用来源</Muted>}</div>
      </aside>
    </main>
  </div>
}

function SectionTitle({ icon, title }) { return <div className="section-title">{icon}<span>{title}</span></div> }
function Field({ label, children }) { return <label className="field"><span>{label}</span>{children}</label> }
function Toggle({ label, checked, onChange }) { return <div className="toggle-row"><span>{label}</span><button className={`switch ${checked ? 'on' : ''}`} onClick={() => onChange(!checked)} aria-pressed={checked}><i /></button></div> }
function Stat({ label, value }) { return <div className="stat"><span>{label}</span><strong>{value}</strong></div> }
function EmptyState() { return <div className="empty"><div className="empty-icon"><Bot size={25} /></div><h2>开始一次股票研究</h2><p>选择股票代码后，输入问题或使用左侧快捷提问</p></div> }
function Message({ role, content, pending, error }) { return <article className={`message ${role}`}><div className="message-label">{role === 'user' ? '你' : 'Agent'}<span>{role === 'assistant' ? <Bot size={13} /> : <MessageSquare size={13} />}</span></div><div className={`bubble ${error ? 'error' : ''}`}>{pending ? <span className="loading"><i /><i /><i /></span> : role === 'assistant' && !error ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{normalizeMarkdown(content)}</ReactMarkdown> : content}</div></article> }
function SessionItem({ session, active, pinned, deleting, onClick, onPin, onDelete }) {
  return <div className={`session-item ${active ? 'active' : ''}`}>
    <button className="session-main" onClick={onClick} disabled={deleting}>
      <strong>{session.title || '未命名会话'}</strong>
      <span>{session.orderId ? `股票 ${session.orderId}` : '未指定股票'} · {session.messageCount || 0} 条消息</span>
      <small>{formatSessionTime(session.lastUpdateTime || session.createTime)}</small>
    </button>
    <div className="session-actions">
      <button className={`session-action ${pinned ? 'pinned' : ''}`} onClick={onPin} disabled={deleting} aria-label={pinned ? '取消置顶' : '置顶会话'} title={pinned ? '取消置顶' : '置顶会话'}><Pin size={14} /></button>
      <button className="session-action delete" onClick={onDelete} disabled={deleting} aria-label={`删除${session.title || '未命名会话'}`} title="删除会话">{deleting ? <LoaderCircle className="spin" size={14} /> : <Trash2 size={14} />}</button>
    </div>
  </div>
}
function formatSessionTime(value) { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
function ToolItem({ tool }) { return <div className="tool-item"><div><span className={`tool-dot ${tool.success ? 'done' : 'fail'}`} />{tool.toolName || '工具调用'}{tool.success ? <CheckCircle2 className="tool-icon done" size={13} /> : <XCircle className="tool-icon fail" size={13} />}</div><small>{tool.errorMessage || `${tool.executionTime || 0} ms`}</small></div> }
function SourceItem({ source }) { return <a className="source-item" href={source.documentUrl || '#'} target="_blank" rel="noreferrer"><FileText size={14} /><span><strong>{source.documentTitle || source.source || '知识来源'}</strong>{source.documentType === 'WEB' ? <small>{source.location || '网页来源'} · 点击查看原文</small> : null}</span></a> }
function Muted({ children }) { return <p className="muted">{children}</p> }

export default App
