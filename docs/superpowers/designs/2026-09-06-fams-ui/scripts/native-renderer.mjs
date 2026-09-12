import {read,save,decode,color} from './local-figma-client.mjs';
export function journal(api,file){
 const state=read(file,{steps:{},nodes:{}});
 async function step(key,name,args){if(Object.hasOwn(state.steps,key))return decode(state.steps[key]);const r=await api.call(name,args);state.steps[key]=r;save(file,state);return r;}
 return {state,step,persist:()=>save(file,state)};
}
const transparent={r:1,g:1,b:1,a:1e-9};
const paint=c=>!c||c.a===0?transparent:c;
export function renderer(api,j,components={}){
 const {step}=j;
 async function render(n,parent,origin={x:0,y:0},prefix='',existing=null,instances=true){
  const k=prefix+'/'+n.key,x=n.x-origin.x,y=n.y-origin.y;
  if(n.type==='text'){
   // Preserve the source library's Chinese font and reusable type styles.
   const size=n.fontSize,weight=n.fontWeight;
   const source=size>=34?['4:4',48]:size>=23?['4:16',36]:size>=18?['4:8',28]:size>=16?['4:54',26]:size>=14?(weight>=500?['6:7',22]:['7:9',22]):['4:3',20];
   const numeric=/^[\d\s,.%+−—…\-/]+$/.test(n.text)&&size>=24;
   const t=numeric?await step(k,'create_text',{parentId:parent,x,y,text:n.text,fontSize:size,fontWeight:weight,fontColor:paint(n.color),name:n.text}):await step(k,'clone_node',{nodeId:source[0]});
   await step(k+'/content','set_text_content',{nodeId:t.id,text:n.text});
   await step(k+'/color','set_fill_color',{nodeId:t.id,...paint(n.color)});
   await step(k+'/name','rename_node',{nodeId:t.id,name:n.text.slice(0,80)});
   const info=await step(k+'/metrics','get_node_info',{nodeId:t.id});
   const b=info.absoluteBoundingBox;
   const textWidth=n.multiline?Math.max(12,n.width+4):Math.max(n.width+6,b.width);
   const textHeight=n.multiline?Math.max(source[1]*n.multiline,Math.ceil(b.width/textWidth)*source[1]):b.height;
   await step(k+'/textsize','resize_node',{nodeId:t.id,width:textWidth,height:textHeight});
   // Text lines are captured separately. Keep their natural width to avoid
   // artificial line breaks; center their actual line box on the DOM glyph box.
   await step(k+'/parent','set_parent',{nodeId:t.id,parentId:parent,x,y:y+(Math.min(n.height,n.lineHeight||n.height)-b.height)/2});
   return t.id;
  }
  const isButton=instances&&n.component==='button'&&n.borders?.some(b=>b.width>0)&&n.children?.length===1&&n.children[0].type==='text';
  const tone=n.tone?String(n.tone[0]).toUpperCase()+n.tone.slice(1):'Neutral';
  const cid=isButton?components[`button:${n.size}/${n.kind}/${n.state}`]:instances&&n.component==='status'?components[`status:${tone}`]:null;
  if(cid){
   const t=await step(k,'create_component_instance',{componentId:cid,parentId:parent,x,y});
   await step(k+'/name','rename_node',{nodeId:t.id,name:(isButton?'操作 / ':'状态 / ')+n.label});
   const info=await step(k+'/info','get_node_info',{nodeId:t.id});
   const texts=[];const walk=v=>{if(v.type==='TEXT')texts.push(v);(v.children||[]).forEach(walk);};walk(info);
   if(texts[0])await step(k+'/label','set_text_content',{nodeId:texts[0].id,text:n.label});
   await step(k+'/size','resize_node',{nodeId:t.id,width:n.width,height:n.height});
   if(isButton)await step(k+'/padding','set_padding',{nodeId:t.id,paddingLeft:6,paddingRight:6,paddingTop:0,paddingBottom:0});
   return t.id;
  }
  const bs=n.borders||[],uniform=bs.length===4&&bs.every(b=>b.width===bs[0].width&&JSON.stringify(b.color)===JSON.stringify(bs[0].color));
  const inlineExtra=['SPAN','STRONG','SMALL','B','LABEL'].includes(n.tag)&&!n.background?.a&&!bs.some(b=>b.width)?24:0;
  const t=existing?{id:existing}:await step(k,'create_frame',{parentId:parent,name:n.name||n.tag||'内容',x,y,width:Math.max(0.1,n.width+inlineExtra),height:Math.max(0.1,n.height),layoutMode:'NONE',fillColor:paint(n.background),...(uniform&&bs[0].width?{strokeColor:paint(bs[0].color),strokeWeight:bs[0].width}:{})});
  if(existing){
   await step(k+'/layout','set_layout_mode',{nodeId:t.id,layoutMode:'NONE'});
   await step(k+'/size','resize_node',{nodeId:t.id,width:Math.max(.1,n.width),height:Math.max(.1,n.height)});
   await step(k+'/fill','set_fill_color',{nodeId:t.id,...paint(n.background)});
   await step(k+'/stroke','set_stroke_color',{nodeId:t.id,...paint(uniform&&bs[0]?.width?bs[0].color:transparent),weight:uniform&&bs[0]?.width?bs[0].width:1});
  }
  if(n.radius)await step(k+'/radius','set_corner_radius',{nodeId:t.id,radius:n.radius});
  if(n.name==='active'&&n.tag==='BUTTON')await step(k+'/active-indicator','create_frame',{parentId:t.id,name:'当前页面指示',x:0,y:4,width:3,height:Math.max(8,n.height-8),fillColor:color('#2457D6')});
  if(!uniform)for(const [i,b] of bs.entries())if(b.width>0)await step(k+'/border'+i,'create_frame',{parentId:t.id,name:['上','右','下','左'][i]+'分隔线',x:i===1?n.width-b.width:0,y:i===2?n.height-b.width:0,width:i%2?b.width:n.width,height:i%2?n.height:b.width,fillColor:paint(b.color)});
  const children=[];
  for(const c of n.children||[]){const previous=children.at(-1);if(c.type==='text'&&previous?.type==='text'&&Math.abs(c.x-previous.x)<2&&c.fontSize===previous.fontSize&&c.fontWeight===previous.fontWeight&&Math.abs(c.y-(previous.lastY??previous.y)-c.lineHeight)<3){previous.text+=c.text;previous.width=Math.max(previous.width,c.width);previous.multiline=(previous.multiline||1)+1;previous.lastY=c.y;}else children.push({...c});}
  for(const c of children)await render(c,t.id,n,prefix,undefined,instances);
  return t.id;
 }
 return {render};
}
export function coreMap(){
 const out={},core=read('core-components.json');
 ['PC','Mobile'].forEach((size,si)=>['Primary','Secondary'].forEach((kind,ki)=>['Default','Hover','Pressed','Focus','Disabled','Loading'].forEach((s,i)=>{out[`button:${size}/${kind}/${s}`]=`6:${6+si*24+ki*12+i*2}`;})));
 for(const v of core.danger)out[`button:${v.size}/Danger/${v.state}`]=v.id;
 ['Default','Focus','Error','Disabled','ReadOnly'].forEach((s,i)=>{out[`field:Input/${s}`]=`7:${6+i*5}`;});
 for(const v of core.fields)out[`field:${v.control}/${v.state}`]=v.id;
 return out;
}
