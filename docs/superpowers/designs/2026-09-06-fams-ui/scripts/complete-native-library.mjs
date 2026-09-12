import {connect,read,save,color} from './local-figma-client.mjs';
import {journal,renderer,coreMap} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'library-journal.json'),{step,state,persist}=j;
try{
 const core=read('core-components.json');
 for(const v of core.fields){
  const info=await step(v.id+'/fixinfo','get_node_info',{nodeId:v.id}),input=info.children.find(n=>n.name==='Input');
  if(v.control==='Textarea')await step(v.id+'/topalign','set_axis_align',{nodeId:input.id,counterAxisAlignItems:'MIN'});
  else {const arrow=input.children.find(n=>n.name==='展开选项');if(arrow)await step(v.id+'/arrowfix','set_text_content',{nodeId:arrow.id,text:'▾'});}
 }
 const captured=read('layout-spec.json'),families=['status','navigation','table','notice','quantity','asset'];
 state.components??=coreMap();state.sets??={button:'6:54',field:'7:31'};persist();
 const {render}=renderer(api,j,state.components);
 for(const [fi,family] of families.entries()){
  const variants=captured.variants.filter(v=>v.id.startsWith(family+':'));
  const set=await step(family+'/set','clone_node',{nodeId:'7:31',x:80+(fi%2)*2400,y:1500+Math.floor(fi/2)*1900});
  state.sets[family]=set.id;persist();
  await step(family+'/name','rename_node',{nodeId:set.id,name:'FAMS/'+({status:'Status',navigation:'Navigation',table:'TableRow',notice:'Notice',quantity:'QuantityCard',asset:'AssetCard'}[family])});
  const info=await step(family+'/info','get_node_info',{nodeId:set.id});
  const seed=info.children[0];
  for(const child of info.children.slice(1))await step(family+'/delete/'+child.id,'delete_node',{nodeId:child.id});
  for(const child of seed.children||[])await step(family+'/clear/'+child.id,'delete_node',{nodeId:child.id});
  const colWidth=Math.max(...variants.map(v=>v.tree.width))+48,rowHeight=Math.max(...variants.map(v=>v.tree.height))+56;
  const columns=family==='table'?2:3;
  await step(family+'/setlayout','set_layout_mode',{nodeId:set.id,layoutMode:'NONE'});
  await step(family+'/setsize','resize_node',{nodeId:set.id,width:columns*colWidth+48,height:Math.ceil(variants.length/columns)*rowHeight+48});
  for(const [i,v] of variants.entries()){
   const comp=i===0?seed:await step(v.id+'/clone','clone_node',{nodeId:seed.id});
   if(i>0){const cloned=await step(v.id+'/cloneinfo','get_node_info',{nodeId:comp.id});for(const child of cloned.children||[])await step(v.id+'/clear/'+child.id,'delete_node',{nodeId:child.id});}
   const axes=v.id.split(':')[1].split('/');
   await step(v.id+'/name','rename_node',{nodeId:comp.id,name:axes.length===2?`Kind=${axes[0]}, State=${axes[1]}`:`${family==='status'?'Tone':'State'}=${axes[0]}`});
   await render(v.tree,set.id,{x:0,y:0},v.id,comp.id,false);
   if(family==='status'){
    await step(v.id+'/autolayout','set_layout_mode',{nodeId:comp.id,layoutMode:'HORIZONTAL'});
    await step(v.id+'/padding','set_padding',{nodeId:comp.id,paddingLeft:8,paddingRight:8,paddingTop:3,paddingBottom:3});
    await step(v.id+'/align','set_axis_align',{nodeId:comp.id,primaryAxisAlignItems:'CENTER',counterAxisAlignItems:'CENTER'});
   }
   await step(v.id+'/position','move_node',{nodeId:comp.id,x:24+(i%columns)*colWidth,y:24+Math.floor(i/columns)*rowHeight});
   state.components[v.id]=comp.id;persist();
  }
  console.log(JSON.stringify({family,setId:set.id,variants:variants.length}));
 }
 const result=await api.call('get_local_components');save('native-components-verified.json',result);save('component-map.json',{sets:state.sets,components:state.components});console.log(JSON.stringify({nativeComponents:result.count}));
}finally{await api.close();}
