// Shared Figma Plugin API helpers. Inject after selecting a single target page.
await figma.loadFontAsync({family:'Noto Sans SC',style:'Regular'});
await figma.loadFontAsync({family:'Noto Sans SC',style:'Medium'});
await figma.loadFontAsync({family:'Noto Sans SC',style:'Bold'});
const vv=await figma.variables.getLocalVariablesAsync();
const V=Object.fromEntries(vv.map(v=>[v.name,v]));
const ss=await figma.getLocalTextStylesAsync();
const S=Object.fromEntries(ss.map(s=>[s.name.replace('FAMS/',''),s]));
const created=[];
function track(n){created.push(n.id);return n;}
function paint(key){return figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',V[key]);}
function fill(n,key){n.fills=[paint(key)];}
function border(n,key='border/default'){n.strokes=[paint(key)];n.strokeWeight=1;}
function gap(n,x){n.setBoundVariable('itemSpacing',V['space/'+x]);}
function pad(n,x){for(const k of ['paddingTop','paddingBottom','paddingLeft','paddingRight'])n.setBoundVariable(k,V['space/'+x]);}
function radius(n,x){for(const k of ['topLeftRadius','topRightRadius','bottomLeftRadius','bottomRightRadius'])n.setBoundVariable(k,V['radius/'+x]);}
function T(str,style='Body',color='text/primary',w){const n=track(figma.createText());n.name=str.slice(0,40);n.fontName=S[style].fontName;n.textStyleId=S[style].id;n.characters=str;fill(n,color);n.textAutoResize='WIDTH_AND_HEIGHT';if(w){n.resize(w,n.height);n.textAutoResize='HEIGHT';}return n;}
function A(name,dir='VERTICAL',width=600,bg='bg/surface',padding=24,spacing=16){const n=track(figma.createAutoLayout());n.name=name;n.layoutMode=dir;n.resize(width,100);n.primaryAxisSizingMode=dir==='VERTICAL'?'AUTO':'FIXED';n.counterAxisSizingMode=dir==='VERTICAL'?'FIXED':'AUTO';if(bg)fill(n,bg);else n.fills=[];pad(n,padding);gap(n,spacing);return n;}
function put(p,n,stretch=false){p.appendChild(n);if(stretch)n.layoutSizingHorizontal='FILL';return n;}
function row(p,name,width=1000,spacing=16){return put(p,A(name,'HORIZONTAL',width,null,0,spacing),true);}
function doc(title,sub,width=1120){const n=A(title,'VERTICAL',width,'bg/canvas',48,24);figma.currentPage.appendChild(n);n.x=80;n.y=80;put(n,T('FAMS  /  D0.3','Label','action/primary'));put(n,T(title,'Display'));put(n,T(sub,'Body','text/secondary',width-96));return n;}
function component(name,w,h,bg='bg/surface',padding=16,spacing=8){const c=track(figma.createComponent());c.name=name;c.layoutMode='VERTICAL';c.primaryAxisSizingMode='FIXED';c.counterAxisSizingMode='FIXED';c.resize(w,h);fill(c,bg);pad(c,padding);gap(c,spacing);radius(c,8);return c;}
function textProp(c,node,name,value){const key=c.addComponentProperty(name,'TEXT',value);node.componentPropertyReferences={characters:key};return key;}
function setVariants(name,variants,cols=3){const s=track(figma.combineAsVariants(variants,figma.currentPage));s.name='FAMS/'+name;s.description='FAMS D0.3 / '+name+' · Edit text through component properties. Preserve business state semantics.';let maxw=Math.max(...variants.map(v=>v.width)),maxh=Math.max(...variants.map(v=>v.height));variants.forEach((v,i)=>{v.x=24+(i%cols)*(maxw+24);v.y=24+Math.floor(i/cols)*(maxh+24);});s.resize(48+cols*maxw+(cols-1)*24,48+Math.ceil(variants.length/cols)*maxh+(Math.ceil(variants.length/cols)-1)*24);s.x=80;s.y=360;return s;}
function finish(extra={}){return {createdNodeIds:created,...extra};}
