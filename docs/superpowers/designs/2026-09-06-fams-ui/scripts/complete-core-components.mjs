import {connect,read,save,color,decode} from './local-figma-client.mjs';
const api=await connect(),state=read('core-components.json',{steps:{},danger:[],fields:[]});
async function step(key,name,args){if(state.steps[key])return decode(state.steps[key]);const r=await api.call(name,args);state.steps[key]=r;save('core-components.json',state);return r;}
const C={ink:'#18263D',muted:'#53657D',blue:'#2457D6',white:'#FFFFFF',line:'#E3E8F0',subtle:'#F0F3F8',red:'#B4232E',redBg:'#FFF0EF'};
try{
 const nodes=await api.call('get_nodes_info',{nodeIds:['6:54','7:31','3:5']});save('before-core.json',nodes);
 await step('button-size','resize_node',{nodeId:'6:54',width:1032,height:456});
 for(const [si,size] of ['PC','Mobile'].entries())for(const [i,label] of ['Default','Hover','Pressed','Focus','Disabled','Loading'].entries()){
  const source=(si?42:18)+i*2,k=`danger-${size}-${label}`;
  const n=await step(k,'clone_node',{nodeId:'6:'+source,x:24+i*168,y:312+si*72});
  await step(k+'-name','rename_node',{nodeId:n.id,name:`Size=${size}, Kind=Danger, State=${label}`});
  const info=await step(k+'-info','get_node_info',{nodeId:n.id});const tx=info.children[0].id;
  const bg=label==='Pressed'?C.red:label==='Hover'?C.redBg:label==='Disabled'?C.subtle:C.white;
  const fg=label==='Pressed'?C.white:label==='Disabled'?C.muted:C.red;
  await step(k+'-bg','set_fill_color',{nodeId:n.id,color:color(bg)});
  await step(k+'-stroke','set_stroke_color',{nodeId:n.id,color:color(label==='Focus'?C.blue:label==='Disabled'?C.line:C.red),weight:label==='Focus'?3:1});
  await step(k+'-label','set_text_content',{nodeId:tx,text:label==='Loading'?'处理中…':label==='Disabled'?'暂不可处置':'确认处置'});
  await step(k+'-fg','set_fill_color',{nodeId:tx,color:color(fg)});
  if(!state.danger.some(x=>x.id===n.id)){state.danger.push({id:n.id,size,state:label});save('core-components.json',state);}
 }
 for(const [i,label] of ['Default','Focus','Error','Disabled','ReadOnly'].entries()){
  const id='7:'+(6+i*5);await step('input-name-'+i,'rename_node',{nodeId:id,name:`Control=Input, State=${label}`});
  await step('input-place-'+i,'move_node',{nodeId:id,x:24,y:24+i*188});
  const inputId='7:'+(8+i*5);
  if(!['Focus','Error'].includes(label))await step('input-border-'+i,'set_stroke_color',{nodeId:inputId,color:color(C.muted),weight:1});
  for(const [j,control] of ['Select','Textarea'].entries()){
   const key=`field-${control}-${label}`,n=await step(key,'clone_node',{nodeId:id,x:368+j*344,y:24+i*188});
   await step(key+'-name','rename_node',{nodeId:n.id,name:`Control=${control}, State=${label}`});
   const info=await step(key+'-info','get_node_info',{nodeId:n.id}),input=info.children.find(x=>x.name==='Input');
   if(control==='Textarea'){
    await step(key+'-control-size','resize_node',{nodeId:input.id,width:320,height:88});
    await step(key+'-size','resize_node',{nodeId:n.id,width:320,height:160});
   } else if(label!=='ReadOnly') {
    await step(key+'-layout','set_layout_mode',{nodeId:input.id,layoutMode:'NONE'});
    await step(key+'-arrow','create_text',{parentId:input.id,x:292,y:10,text:'⌄',fontSize:16,fontColor:color(C.muted),name:'展开选项'});
   }
   if(!state.fields.some(x=>x.id===n.id)){state.fields.push({id:n.id,control,state:label});save('core-components.json',state);}
  }
 }
 await step('field-set-size','resize_node',{nodeId:'7:31',width:1056,height:976});
 for(const id of ['6:20','6:44'])await step(id+'-hover','set_fill_color',{nodeId:id,color:color(C.subtle)});
 for(const id of ['6:22','6:46'])await step(id+'-pressed','set_fill_color',{nodeId:id,color:color('#EAF0FF')});
 const after=await api.call('get_local_components');save('after-core-components.json',after);console.log(JSON.stringify({danger:state.danger.length,extraFields:state.fields.length,nativeComponentCount:after.count}));
}finally{await api.close();}
