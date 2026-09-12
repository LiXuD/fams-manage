async (page) => {
const errors=[];page.on("pageerror",e=>errors.push(String(e)));
const report=[];const shots=new Set(["P02","P03","P12","P16","M02","M09","M11","M12"]);
for(const prefix of ["P","M"])for(let i=1;i<=(prefix==="P"?26:14);i++){const id=prefix+String(i).padStart(2,"0");await page.setViewportSize({width:prefix==="P"?1440:390,height:1000});await page.goto("http://127.0.0.1:8784/prototype.html?ui=d03-final&page="+id);
const data=await page.evaluate(()=>({title:document.title,overflow:document.documentElement.scrollWidth>innerWidth,canvasWidth:document.querySelector("#canvas").getBoundingClientRect().width,textLength:document.querySelector("#canvas").innerText.length,smallButtons:[...document.querySelectorAll(".mobile button")].filter(e=>{const b=e.getBoundingClientRect();return b.width>0&&(b.width<44||b.height<44)}).map(e=>({text:e.innerText,width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height}))}));report.push({id,width:prefix==="P"?1440:390,...data});
if(shots.has(id))await page.screenshot({path:"output/playwright/fams-ui/"+id+".png",fullPage:true});
if(prefix==="M"){await page.setViewportSize({width:320,height:900});report.push({id,width:320,...await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth,canvasWidth:document.querySelector("#canvas").getBoundingClientRect().width,textLength:document.querySelector("#canvas").innerText.length}))});}}
await page.setViewportSize({width:1440,height:1100});await page.goto("http://127.0.0.1:8784/index.html?ui=d03-final");await page.screenshot({path:"output/playwright/fams-ui/design-system.png",fullPage:true});await page.screenshot({path:"output/playwright/fams-ui/design-system-cover.png"});
return {report,errors};}
