import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { PrivacyProvider } from './state/PrivacyContext'
import { AccountsProvider } from './state/AccountsContext'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <PrivacyProvider>
        <AccountsProvider>
          <App />
        </AccountsProvider>
      </PrivacyProvider>
    </BrowserRouter>
  </StrictMode>,
)
