import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { expect,it } from "vitest";
import { createRouter,createMemoryHistory } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
function view(warehouse=false){const pinia=createPinia(),router=createRouter({history:createMemoryHistory(),routes:[]}),session=useSessionStore(pinia);session.state="authenticated";session.user={id:"1",username:"tech",status:"ENABLED",roles:warehouse?["TECH_ASSISTANT","WAREHOUSE_OPERATOR"]:["TECH_ASSISTANT"],requiresInitialPasswordChange:false};session.permissions={userId:"1",permissions:warehouse?["ASSET_LEDGER_READ"]:[],dataScopes:[],fieldPermissions:[],authorizationVersion:1};return mount(AppChrome,{global:{plugins:[pinia,router]}});}
it("pure invited-technology navigation does not expose general business or enterprise ledger",()=>{const wrapper=view();expect(wrapper.text()).toContain("我协助的报修");expect(wrapper.text()).not.toContain("报修与内部维修");expect(wrapper.text()).not.toContain("资产台账");expect(wrapper.text()).not.toContain("领用申请与分配");wrapper.unmount();});
it("dual-role navigation preserves independently granted warehouse work",()=>{const wrapper=view(true);expect(wrapper.text()).toContain("我协助的报修");expect(wrapper.text()).toContain("报修与内部维修");expect(wrapper.text()).toContain("资产台账");wrapper.unmount();});
