/** @type {import('tailwindcss').Config} */

// Every colour resolves to a CSS variable defined in src/index.css (:root).
// Variables hold space-separated RGB channels so Tailwind's opacity modifiers
// (e.g. `ring-brand-400/20`) keep working.
const token = (name) => `rgb(var(--fm-${name}) / <alpha-value>)`;

const scale = (name, steps) =>
  Object.fromEntries(steps.map((s) => [s, token(`${name}-${s}`)]));

// Status / category accents: fill (DEFAULT), light tint, and AA-safe ink for text.
const accent = (name) => ({
  DEFAULT: token(name),
  tint:    token(`${name}-tint`),
  ink:     token(`${name}-ink`),
});

export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // FoodMap brand — cyan. The `brand` key is kept so existing classes follow.
        brand: scale('brand', [50, 100, 200, 300, 400, 500, 600, 700, 800, 900]),

        ink: {
          DEFAULT: token('ink'),
          muted:   token('ink-muted'),
          subtle:  token('ink-subtle'),
        },
        cream:   token('cream'),
        surface: {
          DEFAULT: token('surface'),
          sunken:  token('surface-sunken'),
        },
        line: {
          DEFAULT: token('line'),
          strong:  token('line-strong'),
        },

        success: accent('success'),
        warning: accent('warning'),
        danger:  accent('danger'),
        star:    token('star'),

        chili:       accent('chili'),
        turmeric:    accent('turmeric'),
        crust:       accent('crust'),
        taro:        accent('taro'),
        pandan:      accent('pandan'),
        dragonfruit: accent('dragonfruit'),
      },

      fontFamily: {
        sans:    ['"Be Vietnam Pro"', 'system-ui', 'sans-serif'],
        display: ['Nunito', '"Be Vietnam Pro"', 'system-ui', 'sans-serif'],
      },

      // Type scale. Headings pair these with `font-display`. Leading stays
      // ≥ 1.3 on headings so Vietnamese stacked diacritics never clip.
      fontSize: {
        display:   ['3rem',      { lineHeight: '3.625rem', fontWeight: '900' }],
        h1:        ['2rem',      { lineHeight: '2.625rem', fontWeight: '800' }],
        h2:        ['1.5rem',    { lineHeight: '2rem',     fontWeight: '800' }],
        h3:        ['1.125rem',  { lineHeight: '1.625rem', fontWeight: '800' }],
        'body-lg': ['1.0625rem', { lineHeight: '1.625rem' }],
        body:      ['0.9375rem', { lineHeight: '1.375rem' }],
        small:     ['0.8125rem', { lineHeight: '1.1875rem' }],
        label:     ['0.75rem',   { lineHeight: '1rem',     fontWeight: '600' }],
      },

      // Semantic radii — named so they never collide with Tailwind's defaults
      // (rounded-lg / rounded-xl are still used by screens not yet restyled).
      borderRadius: {
        tag:   'var(--fm-radius-tag)',
        field: 'var(--fm-radius-field)',
        card:  'var(--fm-radius-card)',
        tile:  'var(--fm-radius-tile)',
        panel: 'var(--fm-radius-panel)',
      },

      boxShadow: {
        e1: 'var(--fm-shadow-e1)',
        e2: 'var(--fm-shadow-e2)',
        e3: 'var(--fm-shadow-e3)',
        // Sticker depth — hard offset shadow, reserved for pressable elements
        sticker:                'var(--fm-shadow-sticker)',
        'sticker-pressed':      'var(--fm-shadow-sticker-pressed)',
        'sticker-soft':         'var(--fm-shadow-sticker-soft)',
        'sticker-soft-pressed': 'var(--fm-shadow-sticker-soft-pressed)',
      },

      transitionDuration: {
        instant: 'var(--fm-dur-instant)',
        fast:    'var(--fm-dur-fast)',
        base:    'var(--fm-dur-base)',
        slow:    'var(--fm-dur-slow)',
        reveal:  'var(--fm-dur-reveal)',
      },
      transitionTimingFunction: {
        standard: 'var(--fm-ease-out)',
        spring:   'var(--fm-ease-spring)',
        exit:     'var(--fm-ease-in)',
      },
    },
  },
  plugins: [],
};
