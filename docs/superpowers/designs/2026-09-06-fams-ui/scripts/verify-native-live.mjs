import {connect,read,save} from './local-figma-client.mjs';
const api=await connect();
try{
 const channel=process.env.FAMS_FIGMA_CHANNEL||'pmnn5fxj',maps=read('component-map.json'),screens=read('screens-map.json'),compositions=read('composition-map.json');
 const pages=await api.call('get_nodes_info',{nodeIds:['3:4','3:5','4:2']});
 const componentPage=pages.find(n=>n.id==='3:4'),screenPage=pages.find(n=>n.id==='3:5');
 if(!componentPage||!screenPage||!pages.find(n=>n.id==='4:2'))throw Error('Existing FAMS page identities were not found');
 const sets=Object.values(maps.sets).map(id=>componentPage.children.find(n=>n.id===id));
 if(sets.some(n=>n?.type!=='COMPONENT_SET'))throw Error('A mapped native component set is missing');
 const components=sets.flatMap(n=>n.children.filter(c=>c.type==='COMPONENT'));
 if(components.length!==85)throw Error('Expected 85 native component variants');
 const changed=[];
 for(const [id,s] of Object.entries(screens)){
  const node=screenPage.children.find(n=>n.id===s.nodeId);if(!node||node.type!=='FRAME')throw Error('Missing screen '+id);
  const prior=read('screen-'+id+'.json');if(JSON.stringify(prior)!==JSON.stringify(node))changed.push(id);
  save('screen-'+id+'.json',node);
 }
 for(const c of Object.values(compositions)){const node=componentPage.children.find(n=>n.id===c.nodeId);if(!node||node.type!=='FRAME')throw Error('Missing composition '+c.title);save('composition-'+c.nodeId.replace(':','-')+'.json',node);}
 save('native-components-page.json',componentPage);save('native-page-structure.json',screenPage);save('native-sets-verified.json',sets);
 const all=await api.call('get_local_components');save('native-components-verified.json',all);
 const result={verifiedAt:new Date().toISOString(),channel,componentSets:sets.length,components:components.length,totalLocalComponents:all.count,screens:Object.keys(screens).length,compositions:Object.keys(compositions).length,changedScreensSinceExport:changed};
 save('live-verification.json',result);console.log(JSON.stringify(result));
}finally{await api.close();}
