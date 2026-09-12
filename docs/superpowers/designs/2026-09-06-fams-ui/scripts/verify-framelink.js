async (page) => {
 const base='http://127.0.0.1:8785/',stamp=Date.now(),errors=[],requests=[];
 page.on('pageerror',e=>errors.push(String(e)));page.on('requestfailed',r=>requests.push(r.url()));
 const reference=[];
 for(const width of [1440,390,320]){
  await page.setViewportSize({width,height:1000});await page.goto(base+'figma-reference/index.html?qa='+stamp);
  await page.waitForFunction(()=>[...document.images].every(n=>n.complete&&n.naturalWidth>0));
  reference.push({width,...await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth,images:[...document.images].map(n=>({width:n.naturalWidth,height:n.naturalHeight}))}))});
  if(width===1440)await page.screenshot({path:'output/playwright/fams-ui/framelink-reference.png',fullPage:true});
 }
 await page.setViewportSize({width:1440,height:1100});await page.goto(base+'component-library.html?qa='+stamp);
 const components=await page.evaluate(()=>{
  const style=selector=>{const n=document.querySelector(selector),s=getComputedStyle(n);return {height:n.getBoundingClientRect().height,background:s.backgroundColor,color:s.color,disabled:n.disabled,busy:n.getAttribute('aria-busy')};};
  return {field:style('[data-variant="field:Input/Default"] input'),loading:style('[data-variant="button:PC/Primary/Loading"] button'),disabled:style('[data-variant="button:PC/Primary/Disabled"] button'),mobile:style('[data-variant="button:Mobile/Primary/Default"] button')};
 });
 await page.locator('#button').screenshot({path:'output/playwright/fams-ui/framelink-buttons.png'});
 await page.goto(base+'prototype.html?present=1&page=P02&qa='+stamp);
 const sectionWeight=await page.locator('.panel h2').first().evaluate(n=>getComputedStyle(n).fontWeight);
 await page.goto(base+'prototype.html?present=1&page=M03&qa='+stamp);await page.setViewportSize({width:390,height:1000});
 const mobileInputHeight=await page.locator('.mobilecontent .field input,.mobilecontent .field select').first().evaluate(n=>n.getBoundingClientRect().height);
 return {reference,components,sectionWeight,mobileInputHeight,errors,requests};
}
