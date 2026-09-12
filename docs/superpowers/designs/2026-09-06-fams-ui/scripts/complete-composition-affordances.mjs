import fs from 'node:fs';
import path from 'node:path';
import {connect,read,save,color,out} from './local-figma-client.mjs';
import {journal} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'composition-affordances.json'),{step}=j;
try{
 const map=read('composition-map.json'),components=read('component-map.json').components;
 for(const [key,kind] of [['打开接收确认','Primary'],['打开处置确认','Danger']]){
  const root=await step(key+'/before','get_node_info',{nodeId:map[key].nodeId}),actions=root.children.find(n=>n.name==='fams-dialog-actions'),old=actions.children.at(-1),b=old.absoluteBoundingBox,p=actions.absoluteBoundingBox;
  const n=await step(key+'/action','create_component_instance',{componentId:components['button:PC/'+kind+'/Default'],parentId:actions.id,x:b.x-p.x,y:b.y-p.y});
  const info=await step(key+'/action-info','get_node_info',{nodeId:n.id});await step(key+'/action-text','set_text_content',{nodeId:info.children[0].id,text:kind==='Primary'?'确认接收':'确认处置'});
  await step(key+'/action-size','resize_node',{nodeId:n.id,width:b.width,height:b.height});await step(key+'/action-padding','set_padding',{nodeId:n.id,paddingLeft:8,paddingRight:8,paddingTop:0,paddingBottom:0});
  await step(key+'/old-action','delete_node',{nodeId:old.id});
 }
 for(const [key,ratio] of [['composition-4',.7],['composition-5',.35]]){
  const root=await step(key+'/before','get_node_info',{nodeId:map[key].nodeId}),track=root.children[1];
  await step(key+'/track','set_fill_color',{nodeId:track.id,...color('#F0F3F8')});
  const bar=await step(key+'/bar','create_frame',{parentId:track.id,x:key==='composition-5'?track.absoluteBoundingBox.width*.2:0,y:0,width:track.absoluteBoundingBox.width*ratio,height:10,name:key==='composition-4'?'已接受 28 / 40':'读取中 / 不表示已盘数量',fillColor:color('#2457D6')});
  await step(key+'/bar-round','set_corner_radius',{nodeId:bar.id,radius:5});
 }
 const check=await step('checkbox-before','get_node_info',{nodeId:map['composition-0'].nodeId});
 await step('checkbox-width','resize_node',{nodeId:check.id,width:260,height:check.absoluteBoundingBox.height});
 await step('mixed-mark','create_frame',{parentId:check.children[3].children[0].id,x:5,y:9,width:10,height:2,name:'部分选中',fillColor:color('#2457D6')});
 await step('checkbox-disabled','set_fill_color',{nodeId:check.children[2].children[0].id,...color('#F0F3F8')});
 const radio=await step('radio-before','get_node_info',{nodeId:map['composition-1'].nodeId}),selected=radio.children[1].children[0];
 await step('radio-width','resize_node',{nodeId:radio.id,width:240,height:radio.absoluteBoundingBox.height});
 for(const c of selected.children)await step('radio-remove/'+c.id,'delete_node',{nodeId:c.id});
 const dot=await step('radio-dot','create_frame',{parentId:selected.id,x:6,y:6,width:8,height:8,name:'已选中',fillColor:color('#2457D6')});await step('radio-dot-round','set_corner_radius',{nodeId:dot.id,radius:4});
 await step('radio-disabled','set_fill_color',{nodeId:radio.children[2].children[0].id,...color('#F0F3F8')});
 const timeline=await step('timeline-before','get_node_info',{nodeId:map['composition-3'].nodeId});
 await step('timeline-width','resize_node',{nodeId:timeline.id,width:220,height:timeline.absoluteBoundingBox.height});
 for(const [i,n] of timeline.children.entries()){
  await step(n.id+'/width','resize_node',{nodeId:n.id,width:208,height:n.absoluteBoundingBox.height});
  for(const child of n.children.filter(c=>c.type==='FRAME'&&c.children?.some(t=>t.type==='TEXT')))await step(child.id+'/width','resize_node',{nodeId:child.id,width:180,height:Math.max(child.absoluteBoundingBox.height,26)});
  const d=await step(n.id+'/marker','create_frame',{parentId:timeline.id,x:7,y:n.absoluteBoundingBox.y-timeline.absoluteBoundingBox.y+6,width:10,height:10,name:['已发生','当前步骤','待执行'][i],fillColor:color(i===0?'#206A45':'#FFFFFF'),strokeColor:color(i===0?'#206A45':i===1?'#2457D6':'#53657D'),strokeWeight:2});
  await step(n.id+'/marker-radius','set_corner_radius',{nodeId:d.id,radius:5});
 }
 fs.mkdirSync(path.join(out,'final-compositions'),{recursive:true});
 for(const [key,c] of Object.entries(map)){
  save('composition-'+c.nodeId.replace(':','-')+'.json',await api.call('get_node_info',{nodeId:c.nodeId}));
  const e=await api.call('export_node_as_image',{nodeId:c.nodeId,format:'PNG',scale:1});
  const dest=path.join(out,'final-compositions',key.startsWith('打开')?(key==='打开接收确认'?'dialog-confirm.png':'dialog-danger.png'):key+'.png');fs.copyFileSync(e.images[0],dest);map[key].exports=[dest];
 }
 save('composition-map.json',map);save('native-components-page.json',await api.call('get_node_info',{nodeId:'3:4'}));
 console.log(JSON.stringify({compositionAffordances:'complete',exports:10}));
}finally{await api.close();}
