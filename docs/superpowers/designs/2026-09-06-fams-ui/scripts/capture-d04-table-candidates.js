async page => {
  const base = 'http://127.0.0.1:8785/prototype.html?present=1';
  const captures = [];
  for (const pageId of ['P03', 'P23']) {
    for (const density of ['default', 'compact']) {
      for (const [width, height] of [[1440, pageId === 'P23' ? 1140 : 1000], [1024, pageId === 'P23' ? 1140 : 900]]) {
        await page.setViewportSize({width, height});
        await page.goto(`${base}&page=${pageId}&tableDensity=${density}&qa=d04-table-candidate-${Date.now()}`, {waitUntil: 'networkidle'});
        await page.waitForTimeout(120);
        const suffix = width === 1440 ? '' : '-1024';
        const path = `output/playwright/fams-ui/d04-compare-${pageId}-table-${density}${suffix}.png`;
        await page.screenshot({path});
        captures.push({page: pageId, density, viewport: `${width}x${height}`, path});
      }
    }
  }
  return captures;
}
