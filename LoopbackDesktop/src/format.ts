const dateFormat = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** "just now", "5m ago", "3h ago", "2d ago", or an absolute date for older items. Same as the Android app. */
export function relativeTime(iso: string, now = Date.now()): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const minutes = Math.floor((now - t) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return dateFormat.format(t);
}

export function absoluteTime(iso: string): string {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? "" : dateFormat.format(t);
}

/** Flattens inline markdown for one-line previews: strips emphasis markers, keeps link text. */
export function stripInlineMarkdown(text: string): string {
  return text
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/^\s*(#{1,6}|[-*+>]|\d+\.)\s+/gm, "")
    .replace(/(\*\*|__|~~|`|\*)/g, "")
    .trim();
}

export function previewLine(context: string | null): string {
  const line = (context ?? "").split("\n").find((l) => l.trim()) ?? "";
  return stripInlineMarkdown(line);
}

/** Muted avatar tints, the same list and hashing as the Android app, so a source keeps its colour. */
const SOURCE_PALETTE = [
  "#7FC8DD", // sky
  "#A48FE0", // lavender
  "#DBA3D8", // orchid
  "#85CFAE", // sage
  "#E0B280", // sand
  "#E39C9C", // rose
  "#8FB6E6", // periwinkle
  "#C9C77E", // olive
  "#86D2CB", // teal
  "#D6A1B8", // mauve
];

/** Java's String.hashCode over UTF-16 units, so the colour matches the phone. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return h;
}

export function sourceColor(source: string | null): string {
  const key = (source ?? "").trim().toLowerCase();
  if (!key) return "#9AA3C8";
  const n = SOURCE_PALETTE.length;
  return SOURCE_PALETTE[((javaHash(key) % n) + n) % n];
}

export function sourceInitial(source: string | null): string {
  const ch = [...(source ?? "").trim()].find((c) => /[\p{L}\p{N}]/u.test(c));
  return ch ? ch.toUpperCase() : "?";
}
