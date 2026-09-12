import {Client} from '/Users/lixd/IdeaProjects/Git/cursor-talk-to-figma-mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '/Users/lixd/IdeaProjects/Git/cursor-talk-to-figma-mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import fs from 'node:fs';
import path from 'node:path';
export const root=path.resolve('docs/superpowers/designs/2026-09-06-fams-ui');
export const out=path.join(root,'figma-native');fs.mkdirSync(out,{recursive:true});
const logPath=path.join(out,'operations.jsonl');
export async function connect(){
 const transport=new StdioClientTransport({command:'/opt/homebrew/bin/bun',args:['/Users/lixd/IdeaProjects/Git/cursor-talk-to-figma-mcp/src/talk_to_figma_mcp/server.ts'],stderr:'pipe'});
 const client=new Client({name:'fams-design-delivery',version:'1.0.0'});
 const stderr=fs.createWriteStream(path.join(out,'mcp-client.log'),{flags:'a'});transport.stderr?.pipe(stderr);
 await client.connect(transport);
 // Allow this MCP server's websocket open handshake to finish before joining.
 // The MCP stdio process can be ready before its Figma websocket is open.
 // Retry only the transient join failure so the caller never mutates a page
 // through a client that has not actually joined the requested TalkToFigma
 // channel.
 await new Promise(r=>setTimeout(r,500));
 async function call(name,args={}){
  if(['set_fill_color','set_stroke_color'].includes(name)&&args.color){const {color:c,...rest}=args;args={...rest,...c};}
  const result=await client.callTool({name,arguments:args});
  const text=(result.content||[]).filter(c=>c.type==='text').map(c=>c.text).join('\n');
  fs.appendFileSync(logPath,JSON.stringify({at:new Date().toISOString(),tool:name,args,result:result.isError?'ERROR':text})+'\n');
  if(result.isError||/^Error\b/.test(text))throw new Error(name+': '+text);
  const images=(result.content||[]).filter(c=>c.type==='image').map((c,i)=>{
   const ext={
    'image/jpeg':'jpg',
    'image/png':'png',
    'image/svg+xml':'svg',
    'application/pdf':'pdf',
   }[c.mimeType]||'bin';
   const p=path.join(out,`${args.nodeId.replaceAll(':','-')}-${i}.${ext}`);
   fs.writeFileSync(p,Buffer.from(c.data,'base64'));
   return p;
  });
  if(images.length)return {images,text};
  return decode(text);
 }
 const channel=process.env.FAMS_FIGMA_CHANNEL||'pmnn5fxj';
 let joined=false,lastError;
 for(let attempt=1;attempt<=20&&!joined;attempt++){
  try{
   await call('join_channel',{channel});
   joined=true;
  }catch(error){
   lastError=error;
   if(!/Not connected to Figma/i.test(String(error))||attempt===20)throw error;
   await new Promise(r=>setTimeout(r,250));
  }
 }
 if(!joined)throw lastError||new Error(`Unable to join TalkToFigma channel ${channel}`);
 return {call,close:()=>client.close()};
}
export function save(name,value){fs.writeFileSync(path.join(out,name),JSON.stringify(value,null,2)+'\n');}
export function read(name,fallback={}){const p=path.join(out,name);return fs.existsSync(p)?JSON.parse(fs.readFileSync(p,'utf8')):fallback;}
export function color(hex){return {r:parseInt(hex.slice(1,3),16)/255,g:parseInt(hex.slice(3,5),16)/255,b:parseInt(hex.slice(5,7),16)/255,a:1};}
export function decode(text){if(typeof text!=='string')return text;try{return JSON.parse(text);}catch{const id=text.match(/\b(?:new )?ID:\s*([A-Za-z0-9:;-]+)/i);return id?{id:id[1],message:text}:text;}}
if(process.argv[1]===new URL(import.meta.url).pathname){const api=await connect();try{const doc=await api.call('get_document_info');save('connection.json',doc);console.log(JSON.stringify(doc));}finally{await api.close();}}
