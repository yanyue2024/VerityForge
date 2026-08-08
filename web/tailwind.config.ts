import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: {
          950: '#0f172a',
          900: '#172033',
          800: '#273449',
          700: '#41516a',
          600: '#64748b',
          500: '#7c8ba1',
          400: '#94a3b8',
        },
        paper: {
          50: '#f8fafc',
          100: '#f1f5f9',
          200: '#e2e8f0',
          300: '#cbd5e1',
        },
        brand: {
          800: '#1e40af',
          700: '#1d4ed8',
          600: '#2563eb',
          200: '#bfdbfe',
          100: '#dbeafe',
          50: '#eff6ff',
        },
        evidence: {
          700: '#1d4ed8',
          600: '#2563eb',
          100: '#dbeafe',
          50: '#eff6ff',
        },
        amber: {
          700: '#9a5b12',
          100: '#f7e7c7',
          50: '#fff8e9',
        },
        coral: {
          700: '#b64252',
          200: '#efc2c8',
          100: '#f7dfe3',
          50: '#fff2f4',
        },
      },
      boxShadow: {
        panel: '0 16px 40px rgba(15, 23, 42, 0.10)',
        shell: '0 18px 44px rgba(15, 23, 42, 0.16)',
      },
      fontFamily: {
        sans: [
          '"Noto Sans SC"',
          '"PingFang SC"',
          '"Microsoft YaHei"',
          'ui-sans-serif',
          'system-ui',
          'sans-serif',
        ],
        display: [
          'Aptos',
          '"Noto Sans SC"',
          '"Microsoft YaHei"',
          'ui-sans-serif',
          'system-ui',
          'sans-serif',
        ],
      },
    },
  },
  plugins: [],
} satisfies Config
