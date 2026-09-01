import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { PrivacyProvider } from './state/PrivacyContext'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <PrivacyProvider>
        <App />
      </PrivacyProvider>
    </BrowserRouter>
  </StrictMode>,
)
