import {connect,read,save} from './local-figma-client.mjs';
const api=await connect();
try{
 const j=read('screens-journal.json');
 for(const id of ['P02','M02'])if(j.screens?.[id]){await api.call('delete_node',{nodeId:j.screens[id].nodeId});delete j.screens[id];for(const k of Object.keys(j.steps))if(k.startsWith(id+'/'))delete j.steps[k];save('screens-journal.json',j);save('screens-map.json',j.screens);}
 const f=read('font-test.json');if(f.id&&!f.deleted){await api.call('delete_node',{nodeId:f.id});save('font-test.json',{...f,deleted:true});}
}finally{await api.close();}
