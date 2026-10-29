import {mount,flushPromises} from "@vue/test-utils";
import {reactive} from "vue";
import {expect,it,vi,afterEach} from "vitest";
import LossView from "./LossView.vue";
import LossProgressPanel from "./LossProgressPanel.vue";
const state=vi.hoisted(()=>({session:{user:{id:"1",roles:["WAREHOUSE_OPERATOR"]},csrfToken:"csrf"}}));
vi.mock("@/auth/sessionStore",()=>({useSessionStore:()=>state.session}));
vi.mock("vue-router",()=>({useRoute:()=>({params:{id:"9"}})}));
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
const response=(body:unknown,status=200)=>({ok:status===200,status,json:async()=>structuredClone(body)}) as Response;
it("actual parent freezes committed confirmation through readonly refresh and retains original summary and headers",async()=>{
 state.session=reactive({user:{id:"1",roles:["WAREHOUSE_OPERATOR"]},csrfToken:"csrf"});
 const row={id:"9",assetId:"9007199254740993",source:{id:"9007199254740993",assetCode:"L-1",name:"原责任资产",version:2,lifecycle:"BORROWED"},version:8,assetVersion:2,ownershipVersion:3,lockVersion:4,status:"SEARCHING",actions:{confirmLost:true,followUp:false},photos:[],history:[]};const writes:RequestInit[]=[];let lost=true;
 vi.stubGlobal("fetch",vi.fn(async(url:string,input:RequestInit)=>{if(input.method==="POST"){writes.push(input);if(lost){lost=false;row.version=90;row.status="CONFIRMED_LOST";row.actions={confirmLost:false,followUp:true};throw Error("committed reply lost");}return response(row);}return response(url.endsWith("/9")?row:{items:[row],total:1});}));
 const wrapper=mount(LossView,{global:{stubs:{AppChrome:{template:"<main><slot/></main>"}}}});await flushPromises();const panel=wrapper.findComponent(LossProgressPanel);
 for(const area of panel.findAll("textarea"))await area.setValue("已核实原责任及交接维修盘点线索，仍无法找到原实物");for(const input of panel.findAll("input"))await input.setValue("2026-10-29T10:21:00");await panel.find("button").trigger("click");
 const button=(text:string)=>wrapper.findAll("button").find(x=>x.text()===text)!;
 expect(wrapper.find('[role="dialog"]').text()).toContain("原责任和借用保留");await button("确认提交").trigger("click");await flushPromises();await button("只读核对最新结果").trigger("click");await flushPromises();expect(wrapper.text()).toContain("默认30日跟进");await button("使用原请求重试").trigger("click");await flushPromises();expect(writes).toHaveLength(2);expect(writes[1]).toEqual(writes[0]);expect((writes[0]!.headers as Record<string,string>)["If-Match-Version"]).toBe("8");expect(JSON.parse(writes[0]!.body as string)).toMatchObject({assetVersion:2,ownershipVersion:3,lockVersion:4});wrapper.unmount();
});
