async page => {
  await page.setViewportSize({width: 1024, height: 1140});
  await page.goto(`http://127.0.0.1:8785/prototype.html?present=1&page=P23&qa=p23-responsive-${Date.now()}`, {waitUntil: 'domcontentloaded'});
  await page.waitForTimeout(100);
  const result = await page.evaluate(() => {
    const grid = document.querySelector('.content>.grid2');
    const panels = [...document.querySelectorAll('.content>.grid2>.panel')];
    const summaryValues = [...document.querySelectorAll('.content>.grid2>.panel:first-child .kv dd')];
    const stageWrap = document.querySelector('.content>.grid2>.panel:last-child .tablewrap');
    const stageLinks = [...document.querySelectorAll('.content>.grid2>.panel:last-child .d04-table-link')];
    const rect = node => {
      const box = node?.getBoundingClientRect();
      return box ? {width: box.width, height: box.height} : null;
    };
    return {
      grid: rect(grid),
      panels: panels.map(rect),
      summaryValues: summaryValues.map(rect),
      stageTable: stageWrap ? {clientWidth: stageWrap.clientWidth, scrollWidth: stageWrap.scrollWidth} : null,
      stageLinks: stageLinks.map(rect),
    };
  });
  if (!result.grid || result.panels.length !== 2 || result.panels.some(panel => panel.width < 300)) {
    throw new Error(`P23 summary/table columns are too narrow at 1024px: ${JSON.stringify(result)}`);
  }
  if (result.summaryValues.some(value => value.width < 80)) {
    throw new Error(`P23 summary values cannot keep a readable value column: ${JSON.stringify(result)}`);
  }
  if (!result.stageTable || result.stageTable.scrollWidth > result.stageTable.clientWidth + 1) {
    throw new Error(`P23 stage table hides its quantity/action columns behind horizontal scrolling at 1024px: ${JSON.stringify(result)}`);
  }
  if (result.stageLinks.some(link => !link || link.width < 44)) {
    throw new Error(`P23 stage actions are not visibly targetable at 1024px: ${JSON.stringify(result)}`);
  }
  return result;
}
