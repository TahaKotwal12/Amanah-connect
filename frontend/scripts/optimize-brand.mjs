// Regenerates the small, web-sized brand images from the original logo PNGs in public/brand.
// Run after replacing a logo: node scripts/optimize-brand.mjs
import sharp from 'sharp'

const dir = new URL('../public/brand/', import.meta.url)
const path = (name) => new URL(name, dir).pathname.replace(/^\/([A-Za-z]:)/, '$1')

const mark = path('logo-mark-transparent.png') // 670 x 430
const lockup = path('logo-lockup-transparent.png') // 860 x 665

// The mark is drawn at 62 x 40 CSS px; 124 x 80 covers 2x screens.
for (const [width, height] of [[124, 80]]) {
  const resized = sharp(mark).resize(width, height, {
    fit: 'contain',
    background: { r: 0, g: 0, b: 0, alpha: 0 },
  })
  await resized
    .clone()
    .avif({ quality: 55 })
    .toFile(path(`logo-mark-${width}.avif`))
  await resized
    .clone()
    .webp({ quality: 85 })
    .toFile(path(`logo-mark-${width}.webp`))
  await resized
    .clone()
    .png({ compressionLevel: 9, palette: true })
    .toFile(path(`logo-mark-${width}.png`))
}

// Open Graph / Twitter card: the lockup centred on ivory, 1200 x 630.
const lockupFit = await sharp(lockup).resize({ height: 520 }).toBuffer()
await sharp({ create: { width: 1200, height: 630, channels: 3, background: '#FAFAF7' } })
  .composite([{ input: lockupFit, gravity: 'centre' }])
  .png({ compressionLevel: 9, palette: true })
  .toFile(path('og-image.png'))
console.log('done')
