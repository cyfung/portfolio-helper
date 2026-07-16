import { X } from 'lucide-react'
import type { ToastType } from '@/hooks/useTransientToast'

interface TransientToastProps {
  msg: string
  type: ToastType
  onDismiss: () => void
}

export default function TransientToast({ msg, type, onDismiss }: TransientToastProps) {
  return (
    <div
      className={`config-status config-status-${type}${msg ? ' visible' : ''}`}
      role="status"
      aria-live={type === 'error' ? 'assertive' : 'polite'}
      aria-atomic="true"
    >
      <span className="config-status-message">{msg}</span>
      {msg && (
        <button
          type="button"
          className="config-status-dismiss"
          aria-label="Dismiss notification"
          title="Dismiss"
          onClick={onDismiss}
        >
          <X aria-hidden="true" size={16} strokeWidth={2.25} />
        </button>
      )}
    </div>
  )
}
