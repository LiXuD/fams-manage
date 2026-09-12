import {connect,read,save,color} from './local-figma-client.mjs';
import {journal,renderer} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'composition-refinement.json'),{step}=j;
try{
 const map=read('composition-map.json'),components=read('component-map.json').components,capture=read('composition-layout.json'),{render}=renderer(api,j,components);
 for(const c of capture.filter(c=>c.id.startsWith('打开'))){
  const id=map[c.id].nodeId,info=await step(c.id+'/before','get_node_info',{nodeId:id});
  for(const n of info.children||[])await step(c.id+'/delete/'+n.id,'delete_node',{nodeId:n.id});
  await render(c.tree,'3:4',{x:0,y:0},'stable-dialog/'+c.id,id);
 }
 for(const [id,c] of Object.entries(map)){
  await step(id+'/white-canvas','set_fill_color',{nodeId:c.nodeId,...color('#FFFFFF')});
  const n=await api.call('get_node_info',{nodeId:c.nodeId});save('composition-'+c.nodeId.replace(':','-')+'.json',n);
  const e=await api.call('export_node_as_image',{nodeId:c.nodeId,format:'PNG',scale:1});map[id].exports=e.images;
 }
 save('composition-map.json',map);save('native-components-page.json',await api.call('get_node_info',{nodeId:'3:4'}));
 console.log(JSON.stringify({stableDialogs:2,compositionExports:10}));
}finally{await api.close();}
