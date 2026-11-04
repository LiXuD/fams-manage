import {mount,flushPromises} from "@vue/test-utils";
import {afterEach,expect,it,vi} from "vitest";
import LossRecoveryPanel from "./LossRecoveryPanel.vue";
import type {LossReport,LossIntent} from "@/features/loss/api";
const state=vi.hoisted(()=>({csrfToken:"csrf"}));
vi.mock("@/auth/sessionStore",()=>({useSessionStore:()=>state}));
afterEach(()=>{vi.unstubAllGlobals();});
const row=()=>({id:"9",version:12,assetVersion:3,ownershipVersion:4,lockVersion:5,actions:{recoverOriginal:true},recoveryAsset:{assetCode:"ORIGINAL",version:3,serialNumber:"SERIAL",locationId:"99",locationVersion:0},recoveries:[]} as unknown as LossReport);
async function fill(w:ReturnType<typeof mount>){await w.findAll('input')[0]!.setValue("ORIGINAL");await w.findAll('input')[1]!.setValue("SERIAL");await w.find('input[type="datetime-local"]').setValue("2026-10-31T10:20:30");await w.find('textarea').setValue("原机由新保管人移交在新的实际位置，现场确认完好并需要退库。");}
it.each([false,true])("return recovery uses actual location and independent purpose, closed=%s",async closed=>{
 const report=row();if(closed)report.actions={requestRecovery:true};
 vi.stubGlobal("fetch",vi.fn(async()=>({ok:true,json:async()=>({items:[{id:"9007199254740993",name:"新实际位置",version:7}],total:1})})));
 const w=mount(LossRecoveryPanel,{props:{report,locked:false}});await fill(w);
 const panel=w.find('[aria-label="找回关联归还退库"]');
 const button=(text:string)=>panel.findAll("button").find(b=>b.text()===text)!;
 const action=closed?"新建归还退库找回申请":"找回并申请归还退库";
 await button(action).trigger("click");expect(w.emitted("prepare")).toBeUndefined();
 await button("加载实际位置").trigger("click");await flushPromises();
 await panel.find('select').setValue("9007199254740993");await panel.find('input').setValue(true);
 await button(action).trigger("click");
 const intent=w.emitted("prepare")![0]![0] as LossIntent;
 expect(intent.path).toBe(closed?"/9/return-recovery-requests":"/9/recover-to-return");
 expect(intent.body).toMatchObject({assetVersion:3,facts:{condition:"INTACT",stillOriginalCustodian:false,locationId:"9007199254740993",locationVersion:7}});
 expect(intent.summary).toContain("原责任和借用保留至退库审批及实物验收");
 expect(Object.isFrozen(intent)).toBe(true);
 if(closed){await w.setProps({report:{...report,actions:{},recoveries:[{id:"44",version:2,assetVersion:3,ownershipVersion:8,status:"VERIFYING",creatorAccountId:"2",processorAccountId:"4",actions:{execute:true},request:{destination:"ASSET_RETURN",facts:{condition:"INTACT"}}}] as LossReport["recoveries"]}});expect(w.find('article').text()).not.toContain("核实原机并完成找回");await w.find('article button').trigger('click');expect(w.emitted('prepare')![1]![0]).toMatchObject({path:'/recoveries/44/execute-to-return',version:2,body:{ownershipVersion:8,lockVersion:5}});}
 w.unmount();
});
it("refreshed return link survives without editable recovery form",()=>{const w=mount(LossRecoveryPanel,{props:{report:{...row(),actions:{},recoveryAsset:null,recoveredAt:"2026-10-31T02:00:00Z",returnId:"9007199254740993"},locked:false}});expect(w.text()).toContain("已关联正式归还/退库 9007199254740993");w.unmount();});

it("PC recovery transfer separates observed location from the approved transfer target", async () => {
 const report = row();
 report.source = {id:"2",assetCode:"ORIGINAL",name:"原机",version:3,lifecycle:"IN_USE"};
 vi.stubGlobal("fetch",vi.fn(async (path:string) => {
  const items = path.includes("/recovery-locations") ? [{id:"9007199254740993",name:"实际位置",version:7}]
   : path.includes("/catalog/location") ? [{id:"9007199254740994",name:"目标位置",version:8,locationType:"ROOM"}]
   : path.includes("/catalog/organization") ? [{id:"4",name:"目标部门",version:5}]
   : [{id:"9007199254740995",name:"目标责任人",version:6}];
  return {ok:true,json:async()=>({items,total:items.length})};
 }));
 const w = mount(LossRecoveryPanel,{props:{report,locked:false}});
 await fill(w);
 const panel = w.get('[aria-label="找回关联调拨"]');
 const button = (label:string) => panel.findAll("button").find(item=>item.text()===label)!;
 await button("加载实物实际位置").trigger("click");
 await button("加载调拨目标目录").trigger("click");
 await flushPromises();
 const selects = panel.findAll("select");
 await selects[0]!.setValue("9007199254740993");
 await selects[2]!.setValue("4");
 await flushPromises();
 await selects[4]!.setValue("9007199254740995");
 await selects[5]!.setValue("9007199254740994");
 await panel.find('input').setValue(true);
 await panel.find('textarea').setValue("找回后移交新责任人");
 await button("找回并提交调拨").trigger("click");
 expect(w.emitted("prepare")?.[0]?.[0]).toMatchObject({
  path:"/9/recover-to-transfer",version:12,body:{
   recovery:{assetVersion:3,ownershipVersion:4,lockVersion:5,facts:{locationId:"9007199254740993",locationVersion:7}},
   target:{employeeId:"9007199254740995",locationId:"9007199254740994",referenceVersions:{usingOrganization:5,employee:6,location:8}},
  },
 });
 w.unmount();
});
