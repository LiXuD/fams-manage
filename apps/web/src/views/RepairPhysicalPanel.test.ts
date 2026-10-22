import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import RepairPhysicalPanel from "./RepairPhysicalPanel.vue";
import type { Repair } from "@/repair/api";

const state=vi.hoisted(()=>({session:{user:{id:"1",roles:["WAREHOUSE_OPERATOR"]},csrfToken:"csrf"}}));
vi.mock("@/auth/sessionStore",()=>({useSessionStore:()=>state.session}));
const warehouse="9007199254740993",location="9007199254740995";
const row=():Repair=>({id:"19",assetId:"20",creatorAccountId:"2",source:{id:"20",assetCode:"PHYSICAL-1",name:"实物设备",version:0,lifecycle:"BORROWED",custodyType:"PERSONAL",locationId:"21",locationName:"原地点"},fault:"实际故障",impact:"HIGH",occurredAt:"2026-10-22T00:00:00Z",status:"WAREHOUSE_PROCESSING",version:3,assetVersion:1,ownershipVersion:1,loanVersion:7,processorAccountId:"1",acceptedAt:"2026-10-22T01:00:00Z",completedAt:null,lockVersion:0,actions:{approve:false,claim:false,acceptInPlace:false,completeInPlace:false,addFaultPhoto:false,cancel:false,acceptAway:true,closeInbound:true}});
const response=(body:unknown,status=200)=>({ok:status===200,status,json:async()=>structuredClone(body)}) as Response;
const page=()=>mount(RepairPhysicalPanel,{props:{repair:row()}});
const button=(wrapper:ReturnType<typeof page>,name:string)=>wrapper.findAll("button").find(item=>item.text()===name)!;
function wire(write:(input:RequestInit)=>Promise<Response>){vi.stubGlobal("fetch",vi.fn(async(url:string,input:RequestInit)=>{
 if(input.method==="POST")return await write(input);
 if(url.includes("physical-catalog/warehouse"))return response({items:[{id:warehouse,name:"实际仓库",version:9}],total:1});
 if(url.includes("physical-catalog/location"))return response({items:[{id:location,name:"实际库位",version:11,warehouse_id:warehouse,location_type:"BIN"},{id:"23",name:"另一仓库位置",version:0,warehouse_id:"24",location_type:"BIN"},{id:"25",name:"工作房间",version:0,warehouse_id:null,location_type:"ROOM"}],total:3});
 return response(row());
}));}
async function fill(wrapper:ReturnType<typeof page>,inbound=false){await button(wrapper,inbound?"核对已修复并验收入库":"记录离开原保管人的实际接修").trigger("click");
 if(inbound){await button(wrapper,"查询回库仓库").trigger("click");await flushPromises();await wrapper.findAll("select")[0]!.setValue(warehouse);}
 await button(wrapper,"查询实物位置").trigger("click");await flushPromises();await wrapper.findAll("select").at(-1)!.setValue(location);
 await wrapper.find("input[maxlength='2048']").setValue("PHYSICAL-1");await wrapper.find("input[type='datetime-local']").setValue("2026-10-22T08:00:00.000");await wrapper.find("textarea").setValue("已核对实物资产编码及实际接收位置，记录真实修复与接收依据。");await button(wrapper,"核对本次实物办理").trigger("click");}
beforeEach(()=>{state.session=reactive({user:{id:"1",roles:["WAREHOUSE_OPERATOR"]},csrfToken:"csrf"});});afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
it("actual departure waits for inline confirmation and freezes physical and loan versions",async()=>{const writes:RequestInit[]=[];wire(async input=>{writes.push(input);return response({id:"19"});});const wrapper=page();await fill(wrapper);expect(writes).toHaveLength(0);await button(wrapper,"确认保存实物办理").trigger("click");await flushPromises();expect(writes).toHaveLength(1);expect(JSON.parse(writes[0]!.body as string)).toMatchObject({actualLocationId:location,locationVersion:11,assetVersion:1,ownershipVersion:1,lockVersion:0,loanVersion:7,observedCode:"PHYSICAL-1"});expect(wrapper.emitted("changed")).toHaveLength(1);wrapper.unmount();});
it("inbound selects a real warehouse BIN and preserves large target ids and catalog versions",async()=>{const writes:RequestInit[]=[];wire(async input=>{writes.push(input);return response({id:"19"});});const wrapper=page();await fill(wrapper,true);expect(wrapper.findAll("select").at(-1)!.text()).not.toContain("另一仓库位置");expect(wrapper.findAll("select").at(-1)!.text()).not.toContain("工作房间");await button(wrapper,"确认保存实物办理").trigger("click");await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toMatchObject({warehouseId:warehouse,warehouseVersion:9,locationId:location,locationVersion:11});wrapper.unmount();});
it("unknown committed reply keeps the exact original operation after parent version refresh",async()=>{const writes:RequestInit[]=[];let lost=true;wire(async input=>{writes.push(input);if(lost){lost=false;throw new Error("committed reply lost");}return response({id:"19"});});const wrapper=page();await fill(wrapper,true);await button(wrapper,"确认保存实物办理").trigger("click");await flushPromises();await wrapper.setProps({repair:{...row(),version:9,assetVersion:2}});await button(wrapper,"重试原实物办理").trigger("click");await flushPromises();expect(writes).toHaveLength(2);expect(writes[1]).toEqual(writes[0]);wrapper.unmount();});
it("identity change discards prepared private physical data before sending",async()=>{const writes:RequestInit[]=[];wire(async input=>{writes.push(input);return response({id:"19"});});const wrapper=page();await fill(wrapper);state.session.user.id="9";await flushPromises();expect(writes).toHaveLength(0);expect(wrapper.findAll("button").some(item=>item.text()==="确认保存实物办理")).toBe(false);expect(wrapper.find("textarea").element.value).toBe("");wrapper.unmount();});
it("lost current authority clears pending operation and invalidates parent detail",async()=>{wire(async()=>response({code:"REPAIR_NOT_PROCESSOR"},403));const wrapper=page();await fill(wrapper,true);await button(wrapper,"确认保存实物办理").trigger("click");await flushPromises();expect(wrapper.emitted("invalidated")).toHaveLength(1);expect(wrapper.text()).not.toContain("重试原实物办理");wrapper.unmount();});
