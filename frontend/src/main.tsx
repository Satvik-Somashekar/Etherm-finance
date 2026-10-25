import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { PrivacyProvider } from './state/PrivacyContext'
import { AuthProvider } from './state/AuthContext'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <PrivacyProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </PrivacyProvider>
    </BrowserRouter>
  </StrictMode>,
)
