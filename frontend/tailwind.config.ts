import type { Config } from 'tailwindcss'

const config: Config = {
  // Use [data-theme="dark"] to match the existing app's theme system
  darkMode: ['class', '[data-theme="dark"]'],
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        border: 'var(--color-border-subtle)',
        input: 'var(--color-border-medium)',
        ring: 'var(--color-accent-primary)',
        background: 'var(--color-bg-primary)',
        foreground: 'var(--color-text-primary)',
        primary: {
          DEFAULT: 'var(--color-accent-primary)',
          foreground: '#ffffff',
        },
        secondary: {
          DEFAULT: 'var(--color-bg-secondary)',
          foreground: 'var(--color-text-primary)',
        },
        destructive: {
          DEFAULT: 'var(--color-negative)',
          foreground: '#ffffff',
        },
        muted: {
          DEFAULT: 'var(--color-bg-secondary)',
          foreground: 'var(--color-text-tertiary)',
        },
        accent: {
          DEFAULT: 'var(--color-bg-hover)',
          foreground: 'var(--color-text-primary)',
        },
        popover: {
          DEFAULT: 'var(--color-bg-elevated)',
          foreground: 'var(--color-text-primary)',
        },
        card: {
          DEFAULT: 'var(--color-bg-elevated)',
          foreground: 'var(--color-text-primary)',
        },
        positive: 'var(--color-positive)',
        negative: 'var(--color-negative)',
        warning: 'var(--color-warning)',
      },
      borderRadius: {
        lg: 'var(--radius-lg)',
        md: 'var(--radius-md)',
        sm: 'var(--radius-sm)',
      },
      fontFamily: {
        sans: ['var(--font-family-base)'],
        mono: ['var(--font-family-mono)'],
      },
    },
  },
  plugins: [],
}

export default config
