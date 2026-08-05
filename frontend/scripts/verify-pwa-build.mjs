import { readFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const distRoot = join(frontendRoot, 'dist')

try {
  await verifyPwaBuild()
  console.log('PWA manifest and regular icon dimensions verified.')
} catch (error) {
  console.error(error instanceof Error ? error.message : 'PWA build verification failed.')
  process.exitCode = 1
}

async function verifyPwaBuild() {
  const manifest = JSON.parse(await readFile(join(distRoot, 'manifest.webmanifest'), 'utf8'))

  assertEqual(manifest.name, 'HomeOps', 'manifest name')
  assertEqual(manifest.short_name, 'HomeOps', 'manifest short_name')
  assertEqual(manifest.display, 'standalone', 'manifest display')
  assertEqual(manifest.start_url, '/', 'manifest start_url')
  assertEqual(manifest.scope, '/', 'manifest scope')

  if (!Array.isArray(manifest.icons)) {
    throw new Error('manifest icons must be an array.')
  }

  await Promise.all([
    verifyRegularPngIcon(manifest.icons, 192),
    verifyRegularPngIcon(manifest.icons, 512),
  ])
}

async function verifyRegularPngIcon(icons, size) {
  const src = `/icon-${size}.png`
  const matchingIcons = icons.filter((candidate) => candidate.src === src)
  if (matchingIcons.length !== 1) {
    throw new Error(`manifest must contain exactly one ${src} entry.`)
  }
  const [icon] = matchingIcons

  assertEqual(icon.sizes, `${size}x${size}`, `${src} sizes`)
  assertEqual(icon.type, 'image/png', `${src} type`)
  assertEqual(icon.purpose, 'any', `${src} purpose`)

  const png = await readFile(join(distRoot, src.slice(1)))
  verifyPngDimensions(png, size, src)
}

function verifyPngDimensions(png, expectedSize, label) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  if (png.length < 24 || !png.subarray(0, 8).equals(signature) || png.toString('ascii', 12, 16) !== 'IHDR') {
    throw new Error(`${label} is not a valid PNG with an IHDR header.`)
  }

  const width = png.readUInt32BE(16)
  const height = png.readUInt32BE(20)
  if (width !== expectedSize || height !== expectedSize) {
    throw new Error(`${label} dimensions are ${width}x${height}; expected ${expectedSize}x${expectedSize}.`)
  }
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label} is ${String(actual)}; expected ${String(expected)}.`)
  }
}
