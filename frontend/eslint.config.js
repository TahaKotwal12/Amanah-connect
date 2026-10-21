import js from '@eslint/js'
import prettier from 'eslint-config-prettier'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default defineConfig([
  globalIgnores([
    'dist',
    'coverage',
    'playwright-report',
    'test-results',
    'src/types/api.generated.ts',
  ]),

  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommendedTypeChecked,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      jsxA11y.flatConfigs.recommended,
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': [
        'error',
        { checksVoidReturn: { attributes: false } },
      ],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      // Money is a string everywhere; this keeps `Number(amount)` style arithmetic out by review.
      eqeqeq: ['error', 'always'],
    },
  },

  // shadcn-style files export a component together with its cva variants and hooks.
  {
    files: ['src/shared/components/ui/**'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },

  // Node-side files (config, e2e) are not part of the browser tsconfig project.
  {
    files: ['e2e/**/*.ts', 'playwright.config.ts', 'vite.config.ts'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['*.js'],
    extends: [js.configs.recommended],
    languageOptions: { globals: globals.node },
  },

  prettier,
])
