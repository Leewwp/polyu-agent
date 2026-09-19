/**
 * 外链安全闸（评审 L30）：href/src/window.open 直灌后端下发的字符串属纵深缺口，
 * 仅放行 http/https 绝对地址——javascript:/data: 及畸形值一律判不安全，
 * 调用方拿到 false 时退纯文本或站内兜底，不再生成可点击外链。
 * 类型谓词形式：true 分支自动收窄为 string。
 */
export function isSafeUrl(value: string | null | undefined): value is string {
  if (!value) return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  } catch {
    return false;
  }
}
