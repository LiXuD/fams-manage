import { mount } from "@vue/test-utils";
import { defineComponent, h, shallowRef } from "vue";
import { describe, expect, it } from "vitest";
import { useConfirmedSubmit, type ConfirmedSubmit } from "./useConfirmedSubmit";

function withSetup<T>(composable: () => T): { result: T } {
  let captured!: T;
  const Probe = defineComponent({
    setup() {
      captured = composable();
      return () => h("div");
    },
  });
  mount(Probe);
  return { result: captured };
}

interface Mutation { path: string; version: number }
interface Failed extends Error { status: number }

const deterministic = (cause: unknown): boolean => (cause as Failed).status >= 400 && (cause as Failed).status < 500;
const failed = (status: number): Failed => Object.assign(new Error(`HTTP ${status}`), { status });

describe("useConfirmedSubmit", () => {
  it("重试复用同一个幂等键，确定性失败清键后重新起草换新键", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>({ failure: deterministic }));
    const first = submit.prepare(() => ({ path: "/1/accept", version: 3 }));
    const sent = submit.confirm();
    expect(sent).toBe(first);
    expect(submit.pending.value).toBe(first);
    // 非确定性失败（如网络丢失）：在途意图原样保留，重试仍用同一个 key。
    expect(submit.fail(failed(500))).toBe(false);
    expect(submit.pending.value?.key).toBe(first.key);
    // 确定性失败（4xx）：清键允许重新起草；重起草生成全新的幂等键。
    expect(submit.fail(failed(409))).toBe(true);
    expect(submit.pending.value).toBeNull();
    const redrafted = submit.prepare(() => ({ path: "/1/accept", version: 4 }));
    expect(redrafted.key).not.toBe(first.key);
  });

  it("prepare 后重复调用不换键", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>());
    const first = submit.prepare(() => ({ path: "/1/return", version: 2 }));
    const second = submit.prepare(() => ({ path: "/1/return", version: 9 }));
    expect(second.key).toBe(first.key);
    expect(second.payload).toEqual({ path: "/1/return", version: 9 });
    expect(submit.prepared.value).toBe(second);
  });

  it("settle 清空两个槽，abandon 只清在途意图", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>());
    submit.prepare(() => ({ path: "/1/a", version: 1 }));
    submit.abandon();
    expect(submit.prepared.value).not.toBeNull();
    submit.settle();
    expect(submit.prepared.value).toBeNull();
    expect(submit.pending.value).toBeNull();
    submit.prepare(() => ({ path: "/1/b", version: 1 }));
    const sent = submit.confirm();
    submit.abandon();
    expect(submit.pending.value).toBeNull();
    expect(sent?.key).toBeTruthy();
  });

  it("confirm 只移交草稿；无草稿时返回 null 且不触碰在途意图", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>());
    expect(submit.confirm()).toBeNull();
    const sent = submit.prepare(() => ({ path: "/1/c", version: 1 }));
    expect(submit.confirm()).toBe(sent);
    expect(submit.prepared.value).toBeNull();
    expect(submit.confirm()).toBeNull();
    expect(submit.pending.value).toBe(sent);
  });

  it("cancel 撤销草稿但不影响在途意图", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>());
    const sent = submit.prepare(() => ({ path: "/1/d", version: 1 }));
    submit.confirm();
    submit.prepare(() => ({ path: "/1/e", version: 1 }));
    submit.cancel();
    expect(submit.prepared.value).toBeNull();
    expect(submit.pending.value).toBe(sent);
  });

  it("identity 变化同步作废两个槽", () => {
    const account = shallowRef("1");
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>({ identity: () => account.value }));
    submit.prepare(() => ({ path: "/1/f", version: 1 }));
    submit.confirm();
    account.value = "2";
    expect(submit.prepared.value).toBeNull();
    expect(submit.pending.value).toBeNull();
  });

  it("卸载后在途意图作废", () => {
    let captured!: ConfirmedSubmit<Mutation>;
    const Probe = defineComponent({
      setup() {
        captured = useConfirmedSubmit<Mutation>();
        return () => h("div");
      },
    });
    const wrapper = mount(Probe);
    captured.prepare(() => ({ path: "/1/g", version: 1 }));
    captured.confirm();
    wrapper.unmount();
    expect(captured.pending.value).toBeNull();
    expect(captured.prepared.value).toBeNull();
  });

  it("不传 failure 时 fail 判非确定性并保留在途意图；不传 identity 时不监听变化", () => {
    const { result: submit } = withSetup(() => useConfirmedSubmit<Mutation>());
    submit.prepare(() => ({ path: "/1/h", version: 1 }));
    submit.confirm();
    expect(submit.fail(failed(400))).toBe(false);
    expect(submit.pending.value).not.toBeNull();
    submit.abandon();
    expect(submit.pending.value).toBeNull();
  });
});
