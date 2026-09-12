import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/lixd/.npm/_npx/0b9ff77863cb6e9f/node_modules/playwright/index.mjs';

const root = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui');
const sourcePath = path.join(root, 'scripts/capture-figma-layout.js');
const outputPath = path.join(root, 'figma-native/d04-layout-spec.json');
const capture = (0, eval)(fs.readFileSync(sourcePath, 'utf8'));
const browser = await chromium.launch({
  headless: true,
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
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
