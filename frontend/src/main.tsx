import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './index.css'
import './styles/theme.css'
import './styles/base.css'
import './styles/header.css'
import './styles/config.css'
import './styles/portfolio-tabs.css'
import './styles/portfolio-tables.css'
import './styles/portfolio-responsive.css'
import './styles/portfolio-edit.css'
import './styles/portfolio-cash.css'
import './styles/portfolio-ops.css'
import './styles/rebalance-weight-warning.css'
import './styles/backtest-form.css'
import './styles/monte-carlo.css'
import './styles/portfolio-blocks.css'
import './styles/rebalance-strategy.css'
import './styles/button-utilities.css'
import './styles/portfolio-builder.css'
import './styles/overlays.css'
import './styles/portfolio-markers.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
