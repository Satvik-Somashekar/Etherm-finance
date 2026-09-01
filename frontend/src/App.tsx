import { Route, Routes, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { Header } from './components/Header'
import { Footer } from './components/Footer'
import { Dashboard } from './pages/Dashboard'
import { Accounts } from './pages/Accounts'
import { AiOracle } from './pages/AiOracle'
import { Consent } from './pages/Consent'

function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])
  return null
}

export function App() {
  // The Oracle is a full-height app view: a page footer under it would make the
  // window scroll on top of the chat's own scroll container.
  const chromeless = useLocation().pathname === '/oracle'

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <ScrollToTop />
      <Header />
      {/* Offset for the fixed header — taller on mobile, where the pill nav wraps. */}
      <main className="w-full flex-1 pt-[136px] lg:pt-20">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/accounts" element={<Accounts />} />
          <Route path="/oracle" element={<AiOracle />} />
          <Route path="/consent" element={<Consent />} />
        </Routes>
      </main>
      {!chromeless && <Footer />}
    </div>
  )
}
