/* eslint-disable @typescript-eslint/unbound-method -- these only test whether the stand-in is needed */
import '@testing-library/jest-dom/vitest'

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
