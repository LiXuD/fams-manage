import {connect,read,save} from './local-figma-client.mjs';
import {journal} from './native-renderer.mjs';
const api=await connect(),j=journal(api,'container-refinement.json'),{step,state,persist}=j;
try{
 const capture=read('layout-spec.json'),sj=read('screens-journal.json'),screens=read('screens-map.json');
 state.screens??={};
 for(const s of capture.screens){
  const source=read('screen-'+s.id+'.json'),live=new Map();
  const index=n=>{live.set(n.id,n);(n.children||[]).forEach(index);};index(source);
  const root=source.absoluteBoundingBox;let fixes=0;
  async function walk(c){
   for(const child of c.children||[])await walk(child);
   const rec=sj.steps[s.id+'/'+c.key],n=rec?.id?live.get(rec.id):null;
   if(!n||n.type!=='FRAME'||c.type!=='frame')return;
   // CSS inline siblings must remain sequential after Chinese font calibration.
   const inline=c.children||[];
   if(inline.some(t=>t.type==='text')&&inline.length>1&&inline.every(t=>Math.abs(t.y-inline[0].y)<12)){
    let right=-Infinity;
    for(const child of inline){const rec=sj.steps[s.id+'/'+child.key],node=rec?.id?live.get(rec.id):null;if(!node)continue;
     const b=node.absoluteBoundingBox;
     if(b.x<right&&right+b.width<root.x+root.width-8){const delta=right-b.x;await step(node.id+'/flow','move_node',{nodeId:node.id,x:b.x-n.absoluteBoundingBox.x+delta,y:b.y-n.absoluteBoundingBox.y});
      const shift=t=>{t.absoluteBoundingBox.x+=delta;(t.children||[]).forEach(shift);};shift(node);fixes++;
     }right=b.x+b.width+2;
    }
   }
   // DOM inline and structural wrappers are overflow-visible. The MCP creates
   // clipping Frames, so enlarge only transparent wrappers to their children.
   if(c.background?.a>0||(c.borders||[]).some(b=>b.width>0)||c.key===s.tree.key)return;
   const b=n.absoluteBoundingBox;
   let right=b.x+b.width,bottom=b.y+b.height;
   const bounds=t=>{right=Math.max(right,t.absoluteBoundingBox.x+t.absoluteBoundingBox.width);bottom=Math.max(bottom,t.absoluteBoundingBox.y+t.absoluteBoundingBox.height);(t.children||[]).forEach(bounds);};(n.children||[]).forEach(bounds);
   const width=Math.min(root.x+root.width-b.x,Math.max(b.width,right-b.x+1)),height=Math.min(root.y+root.height-b.y,Math.max(b.height,bottom-b.y+1));
   if(width>b.width+.5||height>b.height+.5){await step(n.id+'/fit','resize_node',{nodeId:n.id,width,height});b.width=width;b.height=height;fixes++;}
  }
  await walk(s.tree);
  if(fixes||state.screens[s.id]){
   save('screen-'+s.id+'.json',await api.call('get_node_info',{nodeId:screens[s.id].nodeId}));
   await api.call('export_node_as_image',{nodeId:screens[s.id].nodeId,format:'PNG',scale:s.id.startsWith('M')?1:.75});
  }
  state.screens[s.id]={fixes};persist();console.log(JSON.stringify({screen:s.id,containerFixes:fixes}));
 }
 save('native-page-structure.json',await api.call('get_node_info',{nodeId:'3:5'}));
}finally{await api.close();}
