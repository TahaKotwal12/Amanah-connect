import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './i18n'
import App from './App'
import { initMonitoring } from './lib/monitoring'
import { offerInstall, registerServiceWorker } from './lib/pwa'

const root = document.getElementById('root')
if (!root) throw new Error('Missing #root element')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// After first render, so reporting never delays the page.
void initMonitoring()
registerServiceWorker()
offerInstall()
