import { Route, Routes } from 'react-router-dom'
import ChatPage from './ChatPage.jsx'
import KnowledgePage from './pages/KnowledgePage.jsx'
import KnowledgeDocumentDetailPage from './pages/KnowledgeDocumentDetailPage.jsx'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/knowledge" element={<KnowledgePage />} />
      <Route path="/knowledge/documents/:documentId" element={<KnowledgeDocumentDetailPage />} />
    </Routes>
  )
}
