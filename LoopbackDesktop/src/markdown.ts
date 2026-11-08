import DOMPurify from "dompurify";
import { marked } from "marked";
import { openUrl } from "@tauri-apps/plugin-opener";

marked.setOptions({ gfm: true, breaks: false });

// Context comes from agents: sanitize it, and never let it run script or reach the IPC bridge.
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("rel", "noopener noreferrer");
  }
  if (node.tagName === "INPUT") {
    // GFM task-list checkboxes are display-only.
    node.setAttribute("disabled", "");
  }
});

export function renderMarkdown(markdown: string): HTMLElement {
  const el = document.createElement("div");
  el.className = "markdown";
  const html = marked.parse(markdown, { async: false });
  el.innerHTML = DOMPurify.sanitize(html, {
    FORBID_TAGS: ["style", "form", "iframe", "object", "embed"],
    FORBID_ATTR: ["style"],
  });
  for (const table of el.querySelectorAll("table")) {
    const wrap = document.createElement("div");
    wrap.className = "table-wrap";
    table.replaceWith(wrap);
    wrap.append(table);
  }
  return el;
}

/** Links inside rendered markdown open in the system browser, not the app window. */
export function handleLinkClicks(root: HTMLElement) {
  root.addEventListener("click", (e) => {
    const a = (e.target as HTMLElement).closest("a");
    if (!a || !root.contains(a)) return;
    e.preventDefault();
    const href = a.getAttribute("href") ?? "";
    if (/^(https?:|mailto:)/i.test(href)) void openUrl(href);
  });
}
