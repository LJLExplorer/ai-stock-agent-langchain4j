import { FilePlus2, LoaderCircle } from 'lucide-react'
import { useState } from 'react'

const documentTypes = [
  ['MANUAL', '手动文档'],
  ['FAQ', '常见问题'],
  ['TECH_DOC', '技术文档'],
  ['SOP', '标准流程'],
  ['POLICY', '政策规则']
]

export default function KnowledgeForm({ busy, onSubmit }) {
  const [title, setTitle] = useState('')
  const [documentType, setDocumentType] = useState('MANUAL')
  const [tags, setTags] = useState('')
  const [content, setContent] = useState('')

  const submit = async (event) => {
    event.preventDefault()
    const saved = await onSubmit({
      title: title.trim(),
      content: content.trim(),
      documentType,
      tags: tags.split(',').map((tag) => tag.trim()).filter(Boolean),
      metadata: {}
    })
    if (saved) {
      setTitle('')
      setTags('')
      setContent('')
      setDocumentType('MANUAL')
    }
  }

  return <form className="knowledge-form" onSubmit={submit}>
    <div className="knowledge-form-heading"><span className="knowledge-icon"><FilePlus2 size={17} /></span><div><h2>新增知识文档</h2><p>保存后会自动切块并写入 RAG 向量库</p></div></div>
    <label className="knowledge-field"><span>文档标题 <em>*</em></span><input value={title} maxLength={200} onChange={(event) => setTitle(event.target.value)} placeholder="例如：贵州茅台估值研究方法" required /></label>
    <label className="knowledge-field"><span>文档类型 <em>*</em></span><select value={documentType} onChange={(event) => setDocumentType(event.target.value)}>{documentTypes.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    <label className="knowledge-field"><span>标签 <small>可选，用逗号分隔</small></span><input value={tags} onChange={(event) => setTags(event.target.value)} placeholder="估值, 白酒, 研究方法" /></label>
    <label className="knowledge-field"><span>正文内容 <em>*</em></span><textarea value={content} onChange={(event) => setContent(event.target.value)} placeholder="粘贴需要被 Agent 检索的知识内容……" required /></label>
    <button className="button primary knowledge-submit" type="submit" disabled={busy || !title.trim() || !content.trim()}>{busy ? <LoaderCircle className="spin" size={16} /> : <FilePlus2 size={16} />}{busy ? '正在处理文档…' : '保存到知识库'}</button>
  </form>
}
