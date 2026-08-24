import { useEffect, useRef, useState } from 'react'
import {
  Activity, Bot, CheckCircle2, Clipboard, Database, FileText, History,
  LoaderCircle, MessageSquare, PanelRight, Plus, Send, Settings2,
  Sparkles, Trash2, Wrench, XCircle
} from 'lucide-react'

const quickPrompts = [
  ['实时行情', '请查询当前股票的实时行情，并说明今天的涨跌情况。'],
  ['技术分析', '请基于真实日K分析当前股票的技术趋势。'],
  ['财务数据', '请查询当前股票最新财务数据和报告期。'],
  ['新闻与风险', '请搜索当前股票最近的新闻、公告和风险信息。']
]

function App() {
  const [userId, setUserId] = useState('demo-user')
  const [sessionId, setSessionId] = useState('')
  const [symbol, setSymbol] = useState('600519')
  const [message, setMessage] = useState('')
  const [rag, setRag] = useState(true)
  const [tools, setTools] = useState(true)
  const [messages, setMessages] = useState([])
  const [details, setDetails] = useState({ tools: [], sources: [], duration: null })
  const [busy, setBusy] = useState(false)
  const [connection, setConnection] = useState('ready')
  const [memoryContent, setMemoryContent] = useState('')
  const [memoryTags, setMemoryTags] = useState('')
  const [memoryBusy, setMemoryBusy] = useState(false)
  const inputRef = useRef(null)

  const clearSession = () => { setSessionId(''); setMessages([]); setDetails({ tools: [], sources: [], duration: null }) }
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
      <div className="top-actions"><span className={`connection ${connection}`}><i />{connection === 'loading' ? '请求处理中' : connection === 'error' ? '请求失败' : '服务可用'}</span><button className="button" onClick={clearSession}><Plus size={15} />新建会话</button></div>
    </header>
    <main className="workspace">
      <aside className="panel sidebar">
        <SectionTitle icon={<Settings2 size={14} />} title="会话设置" />
        <Field label="用户 ID"><input value={userId} onChange={(e) => setUserId(e.target.value)} /></Field>
        <Field label="会话 ID"><div className="inline-field"><input value={sessionId} placeholder="发送后自动创建" onChange={(e) => setSessionId(e.target.value)} /><button className="icon-button" title="加载会话历史" onClick={loadHistory}><History size={16} /></button></div></Field>
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
        <div className="quick-section"><SectionTitle icon={<MessageSquare size={14} />} title="快捷提问" /><div className="quick-list">{quickPrompts.map(([label, prompt]) => <button className="quick-button" key={label} onClick={() => choosePrompt(prompt)}>{label}<span>→</span></button>)}</div></div>
        <button className="button subtle full" onClick={clearSession}><Trash2 size={15} />清空当前会话</button>
      </aside>

      <section className="panel conversation">
        <div className="conversation-head"><div><div className="eyebrow">RESEARCH CHAT</div><h1>研究对话</h1><p>{sessionId ? `会话 ${sessionId}` : '输入问题，Agent 会按需调用行情、技术、财务和资讯工具'}</p></div><button className="button subtle" onClick={copySession} disabled={!sessionId}><Clipboard size={15} />复制 ID</button></div>
        <div className="message-list">{messages.length === 0 ? <EmptyState /> : messages.map((item, index) => <Message key={index} {...item} />)}</div>
        <form className="composer" onSubmit={submit}><textarea ref={inputRef} value={message} onChange={(e) => setMessage(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(e) } }} placeholder="例如：帮我分析 600519 的行情、技术面和近期风险……" /><div className="composer-foot"><span>Enter 发送 · Shift + Enter 换行</span><button className="button primary" disabled={busy || !message.trim()}>{busy ? <LoaderCircle className="spin" size={16} /> : <Send size={16} />}发送分析</button></div></form>
      </section>

      <aside className="panel inspector">
        <SectionTitle icon={<PanelRight size={14} />} title="执行检查" />
        <div className="stat-grid"><Stat label="会话" value={sessionId ? '已建立' : '未发送'} /><Stat label="响应时间" value={details.duration ? `${details.duration} ms` : '-'} /></div>
        <div className="inspector-block"><div className="block-heading"><span><Wrench size={14} />工具调用</span><em>{details.tools.length}</em></div>{details.tools.length ? details.tools.map((tool, index) => <ToolItem key={index} tool={tool} />) : <Muted>发送请求后显示工具执行结果</Muted>}</div>
        <div className="inspector-block"><div className="block-heading"><span><Database size={14} />知识来源</span><em>{details.sources.length}</em></div>{details.sources.length ? details.sources.map((source, index) => <SourceItem key={index} source={source} />) : <Muted>启用 RAG 后显示引用来源</Muted>}</div>
      </aside>
    </main>
  </div>
}

function SectionTitle({ icon, title }) { return <div className="section-title">{icon}<span>{title}</span></div> }
function Field({ label, children }) { return <label className="field"><span>{label}</span>{children}</label> }
function Toggle({ label, checked, onChange }) { return <div className="toggle-row"><span>{label}</span><button className={`switch ${checked ? 'on' : ''}`} onClick={() => onChange(!checked)} aria-pressed={checked}><i /></button></div> }
function Stat({ label, value }) { return <div className="stat"><span>{label}</span><strong>{value}</strong></div> }
function EmptyState() { return <div className="empty"><div className="empty-icon"><Bot size={25} /></div><h2>开始一次股票研究</h2><p>选择股票代码后，输入问题或使用左侧快捷提问</p></div> }
function Message({ role, content, pending, error }) { return <article className={`message ${role}`}><div className="message-label">{role === 'user' ? '你' : 'Agent'}<span>{role === 'assistant' ? <Bot size={13} /> : <MessageSquare size={13} />}</span></div><div className={`bubble ${error ? 'error' : ''}`}>{pending ? <span className="loading"><i /><i /><i /></span> : content}</div></article> }
function ToolItem({ tool }) { return <div className="tool-item"><div><span className={`tool-dot ${tool.success ? 'done' : 'fail'}`} />{tool.toolName || '工具调用'}{tool.success ? <CheckCircle2 className="tool-icon done" size={13} /> : <XCircle className="tool-icon fail" size={13} />}</div><small>{tool.errorMessage || `${tool.executionTime || 0} ms`}</small></div> }
function SourceItem({ source }) { return <a className="source-item" href={source.documentUrl || '#'} target="_blank" rel="noreferrer"><FileText size={14} /><span>{source.documentTitle || source.source || '知识来源'}</span></a> }
function Muted({ children }) { return <p className="muted">{children}</p> }

export default App
