import fs from 'node:fs';
import {connect,read,save,root,color} from './local-figma-client.mjs';
import {journal,renderer} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'finish-journal.json'),{step,state,persist}=j;
try{
 const map=read('component-map.json'),capture=read('layout-spec.json'),manifest=JSON.parse(fs.readFileSync(root+'/library/manifest.json','utf8'));
 const {render}=renderer(api,j,map.components);
 // Preserve the auto-width status labels so instance overrides remain elastic.
 for(const v of capture.variants.filter(v=>!['button','field','status'].includes(v.id.split(':')[0]))){
  const id=map.components[v.id],info=await step(v.id+'/before','get_node_info',{nodeId:id});
  for(const c of info.children||[])await step(v.id+'/clear/'+c.id,'delete_node',{nodeId:c.id});
  await render(v.tree,map.sets[v.id.split(':')[0]],{x:0,y:0},'refine/'+v.id,id,false);
 }
 await step('button-doc','set_text_content',{nodeId:'6:5',text:'36 变体 · PC 40 / Mobile 48 · Primary / Secondary / Danger × Default / Hover / Pressed / Focus / Disabled / Loading。加载防重；危险操作说明对象和后果。'});
 await step('field-doc','set_text_content',{nodeId:'7:5',text:'15 变体 · Input / Select / Textarea × Default / Focus / Error / Disabled / ReadOnly。标签常驻，错误贴近字段；只读展示既有值，禁用说明原因。'});
 const label=(text,x,y,size=18)=>({key:'title',type:'text',text,x,y,width:1800,height:size*1.6,fontSize:size,fontWeight:500,lineHeight:size*1.6,color:color('#18263D')});
 for(const [fi,f] of manifest.families.slice(2).entries()){
  const x=80+(fi%2)*2400,y=1420+Math.floor(fi/2)*1900;
  await render(label(f.name+' · '+Object.keys(map.components).filter(k=>k.startsWith(f.id+':')).length+' 变体',x,y),'3:4',{x:0,y:0},'header/'+f.id);
  await render(label(f.rule,x,y+34,12),'3:4',{x:0,y:0},'rule/'+f.id);
 }
 const compositions=read('composition-layout.json');state.compositions??={};
 await render(label('组合模式 / Selection · Steps · Dialog · Progress · Timeline',80,6920,24),'3:4',{x:0,y:0},'composition-heading');
 for(const [i,c] of compositions.entries()){
  const x=80+(i%3)*660,y=7040+Math.floor(i/3)*520;
  await render(label(c.title.replace('打开',''),x,y-36,18),'3:4',{x:0,y:0},'composition-title/'+c.id);
  const id=await render({...c.tree,name:c.title},'3:4',{x:-x,y:-y},'composition/'+c.id);
  state.compositions[c.id]={nodeId:id,title:c.title};persist();
 }
 save('composition-map.json',state.compositions);
 const screenHeader=await step('screen-cover','create_frame',{parentId:'3:5',name:'交付说明 / 40 页原生视觉稿',x:80,y:80,width:6200,height:240,fillColor:color('#FFFFFF')});
 await render(label('FAMS D0.3 / 40 页原生视觉稿',40,30,36),screenHeader.id,{x:0,y:0},'screen-cover-title');
 await render(label('PC P01–P26 · 小程序 M01–M14 · 既有浅色深蓝风格 · PRD V1.5 / D0.2 业务基线',40,106,18),screenHeader.id,{x:0,y:0},'screen-cover-subtitle');
 await render(label('可编辑文字与图层，按钮 / 状态使用原生组件实例。此页是静态视觉设计；可操作情境与交互验收见本地 prototype.html。',40,158,14),screenHeader.id,{x:0,y:0},'screen-cover-note');
 await render(label('待交接保持为业务进度与业务锁；正式责任、实物阶段、证据回执分别展示。新图层采用既有令牌色值，未新增变量绑定。',40,192,14),screenHeader.id,{x:0,y:0},'screen-cover-boundary');
 const verified=await api.call('get_local_components');save('native-components-verified.json',verified);
 const trees=await api.call('get_nodes_info',{nodeIds:Object.values(map.sets)});save('native-sets-verified.json',trees);
 for(const [name,id] of Object.entries(map.sets)){
  const e=await api.call('export_node_as_image',{nodeId:id,format:'PNG',scale:name==='table'?0.75:1});console.log(JSON.stringify({componentSet:name,nodeId:id,export:e.images}));
 }
 const screens=read('screens-map.json');
 save('native-page-structure.json',await api.call('get_node_info',{nodeId:'3:5'}));
 // Deep links in the delivery index handle navigation across Figma pages.
 console.log(JSON.stringify({components:verified.count,sets:trees.length,screens:Object.keys(screens).length,compositions:compositions.length}));
}finally{await api.close();}
