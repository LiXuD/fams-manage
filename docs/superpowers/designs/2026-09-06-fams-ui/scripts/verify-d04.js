async page => {
  const errors=[];
  page.on('pageerror',error=>errors.push(String(error)));
  page.on('console',message=>{if(message.type()==='error')errors.push(`console: ${message.text()}`);});
  const base='http://127.0.0.1:8785/d04-states.html?qa='+Date.now();
  const viewports=[1440,390,320];
  const layouts=[];
  for(const width of viewports){
    await page.setViewportSize({width,height:1000});
    await page.goto(base);
    await page.waitForTimeout(80);
    const overflowNodes=await page.evaluate(()=>[...document.querySelectorAll('body *')].map(node=>{const r=node.getBoundingClientRect();return {tag:node.tagName,cls:node.className||'',left:Math.round(r.left),right:Math.round(r.right),width:Math.round(r.width)};}).filter(v=>v.left<0||v.right>innerWidth).sort((a,b)=>b.right-a.right).slice(0,8));
    layouts.push({
      width,
      overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),
      examples:await page.locator('[data-d04-example]').count(),
      iframes:await page.locator('iframe').count(),
      overflowNodes,
      smallButtons:await page.locator('button').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();return {text:node.textContent.trim(),width:Math.round(r.width),height:Math.round(r.height)};}).filter(v=>v.width>0&&(v.width<44||v.height<44)))
    });
    await page.screenshot({path:`output/playwright/fams-ui/d04-states-${width}.png`,fullPage:true});
  }
  await page.setViewportSize({width:390,height:1000});
  await page.goto(base);
  const trigger=page.getByRole('button',{name:'打开确认弹窗',exact:true});
  await trigger.click();
  const focusOpen=await page.getByRole('button',{name:'取消',exact:true}).evaluate(node=>node===document.activeElement);
  await page.keyboard.press('Tab');
  const focusTab=await page.getByRole('button',{name:'确认接收',exact:true}).evaluate(node=>node===document.activeElement);
  await page.keyboard.press('Escape');
  const focusClose=await trigger.evaluate(node=>node===document.activeElement);
  const scan=page.getByRole('button',{name:'开始',exact:true});
  await scan.click();
  const scanning=await page.locator('[data-state="scanning"]').count();
  await page.getByRole('button',{name:'停止',exact:true}).click();
  const stopped=await page.getByText('扫描已停止；未产生结果',{exact:true}).count();
  return {layouts,focusOpen,focusTab,focusClose,scanning,stopped,errors};
}
