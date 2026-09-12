import {connect,read,save} from './local-figma-client.mjs';
import {journal,renderer} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'screens-journal.json'),{step,state,persist}=j;
try{
 const captured=read('layout-spec.json'),map=read('component-map.json');
 if(Object.keys(map.components||{}).length!==85)throw Error('Expected verified map of 85 components');
 const {render}=renderer(api,j,map.components);
 state.screens??={};
 const filter=process.argv.slice(2);
 for(const [i,s] of captured.screens.entries()){
  if(filter.length&&!filter.includes(s.id))continue;
  const mobile=s.id.startsWith('M'),index=mobile?i-26:i;
  const x=80+(index%(mobile?7:4))*(mobile?510:1600),y=mobile?12500+Math.floor(index/7)*1600:400+Math.floor(index/4)*1640;
  const id=await render({...s.tree,name:s.title},'3:5',{x:-x,y:-y},s.id);
  state.screens[s.id]={nodeId:id,title:s.title,width:s.tree.width,height:s.tree.height,x,y};persist();
  const verified=await api.call('get_node_info',{nodeId:id});save('screen-'+s.id+'.json',verified);
  const exportResult=await api.call('export_node_as_image',{nodeId:id,format:'PNG',scale:mobile?1:0.75});
  state.screens[s.id].exports=exportResult.images;persist();
  console.log(JSON.stringify({screen:s.id,nodeId:id,export:exportResult.images}));
 }
 save('screens-map.json',state.screens);
}finally{await api.close();}
