async page => {
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));const results=[];
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:1000});await page.goto('http://127.0.0.1:8785/figma-native/index.html');
  const check=await page.evaluate(async()=>{const imgs=[...document.images];imgs.forEach(i=>i.loading='eager');await Promise.all(imgs.map(i=>i.decode()));return {images:imgs.length,loaded:imgs.filter(i=>i.naturalWidth>0).length,overflow:document.documentElement.scrollWidth>innerWidth,figmaLinks:[...document.querySelectorAll('a[href*="figma.com/design/"]')].length};});
  if(check.images!==40||check.loaded!==40||check.overflow)throw Error('Native gallery failed: '+JSON.stringify(check));results.push({page:'screens',width,...check});
  await page.goto('http://127.0.0.1:8785/figma-native/final-compositions/index.html');
  const c=await page.evaluate(async()=>{const imgs=[...document.images];await Promise.all(imgs.map(i=>i.decode()));return {images:imgs.length,loaded:imgs.filter(i=>i.naturalWidth>0).length,overflow:document.documentElement.scrollWidth>innerWidth};});
  if(c.images!==10||c.loaded!==10||c.overflow)throw Error('Composition gallery failed: '+JSON.stringify(c));results.push({page:'compositions',width,...c});
 }
 if(errors.length)throw Error(errors.join('\n'));
 await page.setViewportSize({width:1440,height:1000});await page.goto('http://127.0.0.1:8785/figma-native/index.html');
 return {results,pageErrors:errors};
}
