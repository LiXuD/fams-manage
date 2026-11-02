import { onBeforeUnmount, shallowRef } from "vue";

/**
 * 原件预览生命周期：object URL 的生成与回收、在途下载的取消句柄、清空与卸载收尾。
 *
 * 只拥有预览簿记本身。blob 校验、busy/error 槽位、失败文案与迟到抑制仍留在调用方，
 * 因为各文件的证据校验形状不同（大小上限、允许的 mime、比对字段均有差异）。
 * url/name/mime 以只读 getter 暴露：模板只经 `preview.url` 等读取，写入只走 show/close。
 */
export interface ObjectUrlPreview {
  readonly url: string;
  readonly name: string;
  readonly mime: string;
  /** 替换当前预览并回收上一个 object URL；blob.type 记入 mime，filename 缺省为空。 */
  show(blob: Blob, filename?: string): void;
  /** 取消在途下载 + 回收当前 URL + 清空 url/name/mime 三值。 */
  close(): void;
  /** 为一次下载取可取消句柄；重复调用会取消前一次。 */
  beginDownload(): AbortSignal;
}

export function useObjectUrlPreview(): ObjectUrlPreview {
  const url = shallowRef(""), name = shallowRef(""), mime = shallowRef("");
  let objectUrl = "";
  let controller: AbortController | null = null;

  function show(blob: Blob, filename = ""): void {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = URL.createObjectURL(blob);
    url.value = objectUrl;
    name.value = filename;
    mime.value = blob.type;
  }

  function close(): void {
    controller?.abort();
    controller = null;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = "";
    url.value = "";
    name.value = "";
    mime.value = "";
  }

  function beginDownload(): AbortSignal {
    controller?.abort();
    controller = new AbortController();
    return controller.signal;
  }

  onBeforeUnmount(close);

  return {
    get url() { return url.value; },
    get name() { return name.value; },
    get mime() { return mime.value; },
    show,
    close,
    beginDownload,
  };
}
