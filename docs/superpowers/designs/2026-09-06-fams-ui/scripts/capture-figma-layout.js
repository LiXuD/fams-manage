async (page) => {
 const base='http://127.0.0.1:8785/';
 const capture=async selector=>page.locator(selector).evaluate(root=>{
  const origin=root.getBoundingClientRect();let serial=0;
  const rgb=s=>{const m=s?.match(/[\d.]+/g);return m&&m.length>=3?{r:+m[0]/255,g:+m[1]/255,b:+m[2]/255,a:m[3]===undefined?1:+m[3]}:null;};
  const round=n=>Math.round(n*100)/100;
  const rect=r=>({x:round(r.x-origin.x),y:round(r.y-origin.y),width:round(r.width),height:round(r.height)});
  const textStyle=s=>({fontSize:parseFloat(s.fontSize),fontWeight:parseInt(s.fontWeight)||400,lineHeight:parseFloat(s.lineHeight)||parseFloat(s.fontSize)*1.6,color:rgb(s.color)});
  function lineNodes(node,parentStyle){
   const range=document.createRange(),lines=[];let current=null;
   for(let i=0;i<node.textContent.length;i++){
    range.setStart(node,i);range.setEnd(node,i+1);const r=range.getBoundingClientRect(),ch=node.textContent[i];
    if(!r.width||!r.height)continue;
    if(!current||Math.abs(r.y-current.y)>2){current={x:r.x,y:r.y,right:r.right,height:r.height,text:ch};lines.push(current);}else{current.text+=ch;current.right=Math.max(current.right,r.right);}
   }
   return lines.filter(l=>l.text.trim()).map(l=>({key:'n'+(++serial),type:'text',text:l.text,...rect({x:l.x,y:l.y,width:l.right-l.x,height:l.height}),...textStyle(parentStyle)}));
  }
  function walk(e){
   const s=getComputedStyle(e),r=e.getBoundingClientRect();
   if(['SCRIPT','STYLE','OPTION'].includes(e.tagName)||s.display==='none'||s.visibility==='hidden'||r.width<=0||r.height<=0)return null;
   const bg=rgb(s.backgroundColor),borders=['Top','Right','Bottom','Left'].map(side=>({width:parseFloat(s['border'+side+'Width'])||0,color:rgb(s['border'+side+'Color'])}));
   const n={key:'n'+(++serial),type:'frame',tag:e.tagName,name:(e.getAttribute('aria-label')||e.id||e.className||e.tagName).toString().slice(0,100),...rect(r),background:bg,radius:s.borderTopLeftRadius.includes('%')?Math.min(r.width,r.height)*parseFloat(s.borderTopLeftRadius)/100:parseFloat(s.borderTopLeftRadius)||0,borders,children:[]};
   if(e.tagName==='BUTTON'){n.component='button';n.label=e.textContent.trim();n.kind=e.classList.contains('primary')?'Primary':e.classList.contains('danger')?'Danger':'Secondary';n.size=e.closest('.mobile')?'Mobile':'PC';n.state=e.disabled?'Disabled':'Default';}
   if(e.classList.contains('badge')){n.component='status';n.label=e.textContent.trim();n.tone=e.dataset.tone||'neutral';}
   if(e.matches('input,select,textarea')){
    if(e.matches('[type=checkbox],[type=radio]')){n.background={r:1,g:1,b:1,a:1};n.borders=borders.map(()=>({width:1,color:rgb('rgb(83,101,125)')}));n.radius=e.type==='radio'?r.width/2:2;if(e.checked)n.children.push({key:'n'+(++serial),type:'text',text:'✓',x:n.x+2,y:n.y,width:n.width-4,height:n.height,...textStyle(s)});}
    else {const value=e.tagName==='SELECT'?e.selectedOptions[0]?.textContent:e.type==='password'?'••••••••':e.value||e.placeholder;if(value)n.children.push({key:'n'+(++serial),type:'text',text:value,x:n.x+(parseFloat(s.paddingLeft)||8),y:n.y+(parseFloat(s.paddingTop)||8),width:Math.max(20,n.width-24),height:Math.max(20,n.height-16),...textStyle(s)});if(e.tagName==='SELECT')n.children.push({key:'n'+(++serial),type:'text',text:'⌄',x:n.x+n.width-22,y:n.y+10,width:14,height:20,...textStyle(s)});}
    return n;
   }
   for(const child of e.childNodes){if(child.nodeType===3)n.children.push(...lineNodes(child,s));else if(child.nodeType===1){const c=walk(child);if(c)n.children.push(c);}}
   if(e.matches('.appbar strong')){n.children.unshift({key:'n'+(++serial),type:'frame',name:'FAMS 标记',x:n.x,y:n.y+(n.height-30)/2,width:30,height:30,background:rgb('rgb(36,87,214)'),radius:8,borders:[],children:[{key:'n'+(++serial),type:'text',text:'F',x:n.x+10,y:n.y+(n.height-30)/2+4,width:18,height:24,fontSize:16,fontWeight:700,lineHeight:24,color:rgb('rgb(255,255,255)')}]});}
   if(e.classList.contains('scanbox')){n.children.unshift({key:'n'+(++serial),type:'frame',name:'扫码取景框',x:n.x+56,y:n.y+28,width:n.width-112,height:n.height-88,background:rgb('rgba(0,0,0,0)'),radius:8,borders:Array.from({length:4},()=>({width:2,color:rgb('rgb(255,255,255)')})),children:[]},{key:'n'+(++serial),type:'frame',name:'识别线',x:n.x+60,y:n.y+n.height*.44,width:n.width-120,height:2,background:rgb('rgb(113,153,255)'),radius:0,borders:[],children:[]});}
   return n;
  }
  return walk(root);
 });
 const result={screens:[],variants:[]};
 await page.setViewportSize({width:1440,height:1100});await page.goto(base+'component-library.html?export=1');
 const keys=await page.locator('[data-variant]').evaluateAll(a=>a.map(n=>n.dataset.variant));
 for(const key of keys)result.variants.push({id:key,tree:await capture('[data-variant="'+key+'"] .kit-sample > :first-child')});
 for(const prefix of ['P','M'])for(let i=1;i<=(prefix==='P'?26:14);i++){
  const id=prefix+String(i).padStart(2,'0');await page.setViewportSize({width:prefix==='P'?1440:390,height:1000});await page.goto(base+'prototype.html?present=1&export=1&page='+id);
  const tree=await capture('#canvas .frame');result.screens.push({id,title:await page.title(),tree});
 }
 return result;
}
