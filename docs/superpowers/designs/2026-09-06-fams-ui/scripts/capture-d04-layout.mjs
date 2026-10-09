import fs from 'node:fs';
import path from 'node:path';
const { chromium } = await import(process.env.FAMS_PLAYWRIGHT_MODULE || 'playwright');

const root = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui');
const sourcePath = path.join(root, 'scripts/capture-figma-layout.js');
const outputPath = path.join(root, 'figma-native/d04-layout-spec.json');
const capture = (0, eval)(fs.readFileSync(sourcePath, 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.FAMS_CHROME_EXECUTABLE
    ? { executablePath: process.env.FAMS_CHROME_EXECUTABLE }
    : { channel: 'chrome' }),
  args: ['--no-sandbox']
});
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  const result = await capture(page);
  fs.writeFileSync(outputPath, JSON.stringify({
    capturedAt: new Date().toISOString(),
    source: 'capture-figma-layout.js',
    screens: result.screens,
    variants: result.variants
  }, null, 2) + '\n');
  console.log(JSON.stringify({ outputPath, screens: result.screens.length, variants: result.variants.length }));
} finally {
  await browser.close();
}
