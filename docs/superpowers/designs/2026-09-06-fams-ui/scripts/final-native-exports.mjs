import {connect,read,save,color} from './local-figma-client.mjs';
import {journal} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'final-export-journal.json'),{step,state,persist}=j;
try{
 const screens=read('screens-map.json');
 for(const [id,s] of Object.entries(screens).filter(([id])=>id.startsWith('M'))){
  const info=read('screen-'+id+'.json');let target,parent;
  function walk(n,p){if(n.type==='TEXT'&&n.characters==='···'){target=n;parent=p;}(n.children||[]).forEach(c=>walk(c,n));}walk(info);
  const b=target.absoluteBoundingBox,p=parent.absoluteBoundingBox;
  const t=await step(id+'/dots','create_text',{parentId:parent.id,x:b.x-p.x,y:b.y-p.y,text:'···',fontSize:14,fontWeight:400,fontColor:color('#18263D'),name:'更多选项'});
  await step(id+'/content','set_text_content',{nodeId:t.id,text:'···'});
  await step(id+'/delete-old','delete_node',{nodeId:target.id});
  save('screen-'+id+'.json',await api.call('get_node_info',{nodeId:s.nodeId}));
  await api.call('export_node_as_image',{nodeId:s.nodeId,format:'PNG',scale:1});
 }
 const compositions=read('composition-map.json');
 for(const [id,c] of Object.entries(compositions)){
  const info=await api.call('get_node_info',{nodeId:c.nodeId});save('composition-'+c.nodeId.replace(':','-')+'.json',info);
  const e=await api.call('export_node_as_image',{nodeId:c.nodeId,format:'PNG',scale:1});
  compositions[id].exports=e.images;
 }
 save('composition-map.json',compositions);
 save('native-page-structure.json',await api.call('get_node_info',{nodeId:'3:5'}));
 save('native-components-page.json',await api.call('get_node_info',{nodeId:'3:4'}));
 save('native-components-verified.json',await api.call('get_local_components'));
 console.log(JSON.stringify({mobileExports:14,compositionExports:Object.keys(compositions).length}));
}finally{await api.close();}
