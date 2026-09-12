import fs from 'node:fs';
import path from 'node:path';
import {read,save,root,out} from './local-figma-client.mjs';
const map=read('component-map.json'),screens=read('screens-map.json'),captured=read('d04-layout-spec.json',read('layout-spec.json')),sets=read('native-sets-verified.json',[]),page=read('native-page-structure.json');
const assert=(b,msg)=>{if(!b)throw Error(msg);};
const flatten=n=>[n,...(n.children||[]).flatMap(flatten)];
const normalized=s=>s.replace(/\s/g,'');
const expectedText=n=>n.type==='text'?n.text:(n.children||[]).map(expectedText).join('');
assert(Object.keys(screens).length===40,'40 screens required');
assert(Object.keys(map.components).length===85,'85 mapped components required');
assert(sets.length===8&&sets.every(s=>s.type==='COMPONENT_SET'),'8 native component sets required');
const nativeComponents=sets.flatMap(s=>s.children||[]).filter(s=>s.type==='COMPONENT');
assert(nativeComponents.length===85,'85 native components required');
assert(Object.values(map.components).every(id=>nativeComponents.some(n=>n.id===id)),'Component IDs must resolve');
const result={date:new Date().toISOString(),fileKey:'N4BgxhOTtSFpsoxcN7t4XD',channel:read('live-verification.json',{}).channel||'pmnn5fxj',sets:8,components:85,screens:[],totalTextNodes:0,totalInstances:0,imageFills:0,screenOverlap:[],textMismatches:[],overflowCandidates:[]};
for(const s of captured.screens){
 const record=screens[s.id],evidenceFile=fs.existsSync(path.join(out,'d04-screen-'+s.id+'.json'))?`d04-screen-${s.id}.json`:`screen-${s.id}.json`,n=JSON.parse(fs.readFileSync(path.join(out,evidenceFile),'utf8'));
 assert(n.id===record.nodeId&&n.type==='FRAME',s.id+' must resolve to its native Frame');
 assert(page.children.some(c=>c.id===n.id),s.id+' must be on Screens & Handoff');
 const b=n.absoluteBoundingBox,all=flatten(n),texts=all.filter(n=>n.type==='TEXT'),instances=all.filter(n=>n.type==='INSTANCE');
 assert(Math.abs(b.width-s.tree.width)<.1&&Math.abs(b.height-s.tree.height)<.1,s.id+' dimensions changed');
 assert(texts.length>0,s.id+' has no editable text');
 const actual=normalized(texts.map(n=>n.characters||'').join('')),expected=normalized(expectedText(s.tree));
 if(actual!==expected){let i=0;while(i<actual.length&&actual[i]===expected[i])i++;result.textMismatches.push({screen:s.id,index:i,expected:expected.slice(Math.max(0,i-15),i+60),actual:actual.slice(Math.max(0,i-15),i+60)});}
 const images=all.flatMap(n=>n.fills||[]).filter(f=>f.type==='IMAGE').length;result.imageFills+=images;
 const exportFile=fs.existsSync(path.join(out,`d04-live-${s.id}.png`))?path.join(out,`d04-live-${s.id}.png`):record.exports[0];
 const png=fs.readFileSync(exportFile);assert(png.subarray(1,4).toString()==='PNG',s.id+' export is not PNG');
 for(const t of texts){const r=t.absoluteBoundingBox;if(r.x<b.x-2||r.y<b.y-2||r.x+r.width>b.x+b.width+8||r.y+r.height>b.y+b.height+8)result.overflowCandidates.push({screen:s.id,nodeId:t.id,text:t.characters,box:r});}
 result.totalTextNodes+=texts.length;result.totalInstances+=instances.length;
 result.screens.push({id:s.id,nodeId:n.id,title:s.title,textNodes:texts.length,instances:instances.length,width:b.width,height:b.height,png:path.basename(exportFile),imageFills:images});
}
const boxes=Object.entries(screens);
for(let i=0;i<boxes.length;i++)for(let k=i+1;k<boxes.length;k++){const [a,p]=boxes[i],[b,q]=boxes[k];if(p.x<q.x+q.width&&p.x+p.width>q.x&&p.y<q.y+q.height&&p.y+p.height>q.y)result.screenOverlap.push([a,b]);}
assert(result.screenOverlap.length===0,'Screen frames overlap');
assert(result.imageFills===0,'Screens must not be screenshot fills');
save('native-audit.json',result);
assert(result.textMismatches.length===0,'Page text differs from captured source');
assert(result.overflowCandidates.length===0,'Text extends outside a page frame');
const esc=s=>s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const url=id=>'https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id='+id.replace(':','-');
const compositions=read('composition-map.json');
assert(Object.keys(compositions).length===10,'10 composition examples required');
const compositionLinks=Object.values(compositions).map(c=>`<a href="${url(c.nodeId)}">${esc(c.title.replace('打开',''))} ↗</a>`).join('');
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FAMS / Figma 原生设计交付</title><style>*{box-sizing:border-box}body{margin:0;background:#f7f9fc;color:#18263d;font:14px/1.65 system-ui,-apple-system,sans-serif}header{padding:44px 4vw;background:white;border-bottom:1px solid #e3e8f0}h1{font-size:32px;margin:8px 0}p{color:#53657d;max-width:850px}a{color:#2457d6;text-decoration:none}nav{display:flex;gap:20px;flex-wrap:wrap}main{padding:32px 4vw}h2{font-size:22px;margin:32px 0 16px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}.mobile{grid-template-columns:repeat(4,minmax(0,1fr))}article{background:white;border:1px solid #e3e8f0;border-radius:12px;overflow:hidden}article img{display:block;width:100%;height:auto;border-bottom:1px solid #e3e8f0}article footer{padding:16px}small{color:#53657d}.components{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.components a{padding:16px;background:white;border:1px solid #e3e8f0;border-radius:8px}@media(max-width:700px){.grid,.mobile{grid-template-columns:1fr}.components{grid-template-columns:repeat(2,1fr)}}@media(min-width:1800px){.grid{grid-template-columns:repeat(3,minmax(0,1fr))}.mobile{grid-template-columns:repeat(7,minmax(0,1fr))}}</style><header><nav><a href="../index.html">← 设计系统</a><a href="${url('3:4')}">Figma 组件库 ↗</a><a href="${url('3:5')}">Figma 40 页 ↗</a><a href="../prototype.html?page=P02">本地可点击原型 ↗</a></nav><h1>FAMS / 原生设计已入稿</h1><p>8 个原生组件集 · 85 个变体 · PC 26 页 + 小程序 14 页。下图来自实际 Figma 节点导出；点击页面标题可定位原稿。</p><p>页面保留可编辑文字、图层与按钮 / 状态实例。静态视觉稿以固定视口布局交付，交互情境见本地原型。新图层使用既有令牌色值；未新增变量绑定。</p></header><main><h2>组件库</h2><div class="components">${Object.entries(map.sets).map(([name,id])=>`<a href="${url(id)}">${esc(name)} · ${Object.keys(map.components).filter(k=>k.startsWith(name+':')).length} 变体 ↗</a>`).join('')}</div>${['P','M'].map(prefix=>`<h2>${prefix==='P'?'PC / 26 页':'小程序 / 14 页'}</h2><div class="grid ${prefix==='M'?'mobile':''}">${result.screens.filter(s=>s.id.startsWith(prefix)).map(s=>`<article><a href="${esc(s.png)}"><img src="${esc(s.png)}" alt="${esc(s.title)}" loading="lazy"></a><footer><a href="${url(s.nodeId)}">${esc(s.title.replace(' · FAMS D0.3 视觉稿',''))} ↗</a><br><small>${s.width} × ${Math.round(s.height)} · ${s.textNodes} 文字图层 · ${s.instances} 组件实例</small></footer></article>`).join('')}</div>`).join('')}<p><a href="native-audit.json">结构与文字核对记录</a> · <a href="../NATIVE-FIGMA-VERIFICATION.md">验证范围与边界</a></p></main></html>`;
fs.writeFileSync(path.join(out,'index.html'),html.replace('<h2>PC / 26 页</h2>',`<h2>组合模式 / 10 个示例</h2><div class="components">${compositionLinks}</div><p><a href="final-compositions/index.html">查看实际导出图 →</a></p><h2>PC / 26 页</h2>`));
fs.mkdirSync(path.join(out,'final-compositions'),{recursive:true});
fs.writeFileSync(path.join(out,'final-compositions/index.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FAMS / 组合模式</title><style>body{margin:24px;background:#f7f9fc;color:#18263d;font:14px/1.6 system-ui}a{color:#2457d6}main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}article{background:white;padding:24px;border:1px solid #e3e8f0;border-radius:12px}img{display:block;max-width:100%;height:auto}@media(max-width:700px){main{grid-template-columns:1fr}}</style><a href="../index.html">← 返回 40 页设计</a><h1>组合模式 / 10 个示例</h1><main>${Object.values(compositions).map(c=>`<article><h2><a href="${url(c.nodeId)}">${esc(c.title.replace('打开',''))} ↗</a></h2><img src="${esc(path.basename(c.exports[0]))}" alt="${esc(c.title)}"></article>`).join('')}</main></html>`);
console.log(JSON.stringify({screens:result.screens.length,components:result.components,sets:result.sets,textNodes:result.totalTextNodes,instances:result.totalInstances,imageFills:result.imageFills,textMismatches:result.textMismatches,overflowCandidates:result.overflowCandidates,screenOverlap:result.screenOverlap},null,2));
