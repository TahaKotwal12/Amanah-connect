/* eslint-disable @typescript-eslint/unbound-method -- these only test whether the stand-in is needed */
import * as jestDomMatchers from '@testing-library/jest-dom/matchers'
import { expect } from 'vitest'
import '@/i18n'

// The package's own '@testing-library/jest-dom/vitest' entry namespace-imports a CommonJS file,
// which vitest 5 turns into { default }, so none of the matchers registered. Register them here.
const matchers =
  (jestDomMatchers as { default?: typeof jestDomMatchers }).default ?? jestDomMatchers
expect.extend(matchers)
// Tests use the global `expect` (globals: true), which is a separate instance here.
;(globalThis as { expect?: typeof expect }).expect?.extend(matchers)

// jsdom lacks several browser APIs that Radix, cmdk and react-day-picker call. Minimal stand-ins:
// they exist so components render, not to simulate layout.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver ??= ResizeObserverStub

Element.prototype.hasPointerCapture ??= () => false
Element.prototype.setPointerCapture ??= () => undefined
Element.prototype.releasePointerCapture ??= () => undefined
Element.prototype.scrollIntoView ??= () => undefined

// jsdom has no layout. ProseMirror (the rich text editor) asks ranges and text for their boxes.
const noRects = () => ({ length: 0, item: () => null, [Symbol.iterator]: [][Symbol.iterator] })
Range.prototype.getClientRects ??= noRects as unknown as Range['getClientRects']
Range.prototype.getBoundingClientRect ??= () => new DOMRect()
