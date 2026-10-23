export function formatProxyTime(value: string) {
  if (!value) return "未记录";
  const time = new Date(/[zZ]|[+-][0-9]{2}:[0-9]{2}$/.test(value) ? value : value + "Z");
  return Number.isFinite(time.getTime()) ? time.toLocaleString() : "时间无效";
}
