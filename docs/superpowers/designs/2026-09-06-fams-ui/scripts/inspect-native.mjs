import {connect,save,color,read} from './local-figma-client.mjs';
const api=await connect();
try{
 const f=await api.call('create_frame',{parentId:'3:4',x:4900,y:80,width:650,height:190,name:'Typography / rendering check',fillColor:color('#FFFFFF')});save('font-test.json',f);
 for(const [i,size] of [12,13,14,16,26,32].entries()){
  const t=await api.call('create_text',{parentId:f.id,x:20,y:10+i*27,text:'周宁 · 资产管理员；按任务直接处理。86 / 312',fontSize:size,fontWeight:i===4?700:400,fontColor:color('#18263D')});
  await api.call('set_text_content',{nodeId:t.id,text:'周宁 · 资产管理员；按任务直接处理。86 / 312'});
 }
 console.log(await api.call('export_node_as_image',{nodeId:f.id,format:'PNG',scale:1}));
 save('components-page-info.json',await api.call('get_node_info',{nodeId:'3:4'}));
}finally{await api.close();}
