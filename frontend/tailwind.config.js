import defaultTheme from 'tailwindcss/defaultTheme'

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    container: {
      center: true,
      padding: { DEFAULT: '1rem', sm: '1.5rem', lg: '2rem' },
      screens: { sm: '640px', md: '768px', lg: '1024px', xl: '1200px', '2xl': '1320px' },
    },
    extend: {
      colors: {
        brand: {
          // 50, 100, 500, 600, 800, 900 and 950 are the specified brand values. 200 to 400 and 700
          // are interpolated between them so the scale has no holes: replace if the brand guide has them.
          50: '#EEF8F8',
          100: '#D6EFEF',
          200: '#A9DCDC',
          300: '#7CC6C6',
          400: '#3BA5A6',
          500: '#038D8F',
          600: '#037077',
          700: '#065F65',
          800: '#0A4A52',
          900: '#07363E',
          950: '#04262C',
        },
        gold: {
          50: '#FBF6E3',
          200: '#F0E2A8',
          500: '#C9A227',
          // Gold text on light backgrounds must use gold-600 (contrast). gold-500 is for fills and lines.
          600: '#8A6A12',
        },
        ivory: '#FAFAF7',
        ink: '#0F2A2E',
        muted: '#5B6F72',
        // DEFAULT is the brand colour for fills and icons. 50 is a tinted background and 700 a text
        // shade, both chosen so text on them meets 4.5:1 (the DEFAULT green is only 4.3:1 on white).
        success: { DEFAULT: '#3A8C10', 50: '#EEF7E8', 700: '#2B6A0D' },
        premium: { DEFAULT: '#5B2C91', 50: '#F3EDF9' },
        danger: { DEFAULT: '#B42318', 50: '#FEF3F2' },
        warning: { DEFAULT: '#B54708', 50: '#FFF4E5' },
      },
      fontFamily: {
        // Cinzel: wordmark and eyebrows. Playfair Display: headings. Inter: body. All self-hosted.
        display: ['Cinzel', ...defaultTheme.fontFamily.serif],
        heading: [
          '"Playfair Display Variable"',
          '"Playfair Fallback"',
          ...defaultTheme.fontFamily.serif,
        ],
        sans: ['"Inter Variable"', '"Inter Fallback"', ...defaultTheme.fontFamily.sans],
      },
      borderRadius: {
        DEFAULT: '0.75rem', // rounded-xl by default
        card: '1rem', // rounded-2xl for cards and panels
      },
      boxShadow: {
        soft: '0 1px 2px rgb(7 54 62 / 0.06), 0 4px 14px rgb(7 54 62 / 0.07)',
        lift: '0 2px 4px rgb(7 54 62 / 0.06), 0 12px 32px rgb(7 54 62 / 0.12)',
        focus: '0 0 0 3px rgb(3 141 143 / 0.35)',
      },
      maxWidth: {
        prose: '68ch',
      },
    },
  },
  plugins: [],
}
