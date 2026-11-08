import "./styles.css";
import { backend, errorText, on } from "./backend";
import { absoluteTime, previewLine, relativeTime, sourceColor, sourceInitial } from "./format";
import { handleLinkClicks, renderMarkdown } from "./markdown";
import { needsAttention, type ConnectionStatus, type LoopbackRequest, type Settings } from "./types";

type Tab = "inbox" | "history";
type View = { kind: "empty" } | { kind: "request"; id: string } | { kind: "settings" };
type Draft = { selected: Set<string>; text: string };

const state = {
  requests: new Map<string, LoopbackRequest>(),
  tab: "inbox" as Tab,
  view: { kind: "empty" } as View,
  status: { state: "connecting" } as ConnectionStatus,
  settings: null as Settings | null,
  loaded: false,
  loadError: null as string | null,
  /** Unsent selections and text per request, so switching away and back keeps them. */
  drafts: new Map<string, Draft>(),
  submitting: false,
  pendingCount: -1,
};

const $ = <T extends HTMLElement>(sel: string) => document.querySelector<T>(sel)!;
const listEl = $<HTMLUListElement>("#list");
const detailEl = $<HTMLElement>("#detail");
const connEl = $<HTMLElement>("#conn");
const toastEl = $<HTMLElement>("#toast");

// ---------------------------------------------------------------------------
// DOM helper
// ---------------------------------------------------------------------------

type Child = Node | string | null | undefined | false;
type Props = Record<string, unknown> & { class?: string };

function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") el.className = String(value);
    else if (key.startsWith("on") && typeof value === "function") {
      el.addEventListener(key.slice(2).toLowerCase(), value as EventListener);
    } else if (key in el) (el as unknown as Record<string, unknown>)[key] = value;
    else el.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children) if (child) el.append(child);
  return el;
}

function avatar(source: string | null, size: "sm" | "lg" = "sm") {
  const el = h("span", { class: `avatar ${size}`, "aria-hidden": "true" }, sourceInitial(source));
  // Through CSSOM rather than a style attribute, which the CSP would block.
  el.style.background = sourceColor(source);
  return el;
}

function statusPill(r: LoopbackRequest) {
  const [label, cls] =
    r.kind === "notify"
      ? ["Notification", "notify"]
      : r.status === "answered"
        ? ["Answered", "answered"]
        : r.status === "cancelled"
          ? ["Cancelled", "cancelled"]
          : ["Pending", "pending"];
  return h("span", { class: `pill ${cls}` }, label);
}

const icons = {
  gear: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`,
  refresh: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>`,
};

function iconButton(svg: string, title: string, onClick: () => void) {
  const b = h("button", { class: "icon-btn", title, "aria-label": title, onClick });
  b.innerHTML = svg;
  return b;
}

let toastTimer = 0;
function toast(message: string, kind: "info" | "error" = "info") {
  toastEl.textContent = message;
  toastEl.className = `show ${kind}`;
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (toastEl.className = ""), 4000);
}

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

function sorted(): LoopbackRequest[] {
  return [...state.requests.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

function visible(): LoopbackRequest[] {
  return sorted().filter((r) => (state.tab === "inbox" ? needsAttention(r) : !needsAttention(r)));
}

function openId(): string | null {
  return state.view.kind === "request" ? state.view.id : null;
}

async function refresh() {
  if (!state.settings) return;
  try {
    const all = await backend.listRequests("all", 500);
    state.requests = new Map(all.map((r) => [r.id, r]));
    state.loadError = null;
    // Keep the open request even if the server pruned it from the list.
    const id = openId();
    if (id && !state.requests.has(id)) {
      try {
        upsert(await backend.getRequest(id));
      } catch {
        state.view = { kind: "empty" };
      }
    }
  } catch (e) {
    state.loadError = errorText(e);
  }
  state.loaded = true;
  renderList();
  renderDetail();
}

function upsert(r: LoopbackRequest) {
  state.requests.set(r.id, r);
  if (r.status !== "pending") state.drafts.delete(r.id);
}

function syncPendingCount() {
  const count = sorted().filter(needsAttention).length;
  if (count === state.pendingCount) return;
  state.pendingCount = count;
  void backend.setPendingCount(count);
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

function renderConnection() {
  const s = state.status;
  connEl.replaceChildren();
  connEl.className = `conn ${s.state}`;
  if (s.state === "connected") {
    connEl.append(h("span", { class: "dot" }), "Live");
  } else if (s.state === "connecting") {
    connEl.append(h("span", { class: "dot" }), "Connecting…");
  } else if (s.state === "unconfigured") {
    connEl.append(
      h("span", { class: "dot" }),
      "Not set up · ",
      h("button", { class: "link", onClick: () => show({ kind: "settings" }) }, "Open settings"),
    );
  } else {
    connEl.append(
      h("span", { class: "dot" }),
      h("span", { class: "conn-msg", title: s.message }, s.message),
      h("button", { class: "link", onClick: () => void backend.reconnect() }, "Retry"),
    );
  }
}

function renderTabs() {
  const inboxCount = sorted().filter(needsAttention).length;
  for (const btn of document.querySelectorAll<HTMLButtonElement>(".tabs button")) {
    const tab = btn.dataset.tab as Tab;
    btn.classList.toggle("active", tab === state.tab);
    btn.setAttribute("aria-selected", String(tab === state.tab));
    const badge = btn.querySelector(".count");
    if (badge) badge.textContent = tab === "inbox" && inboxCount > 0 ? String(inboxCount) : "";
  }
}

function renderList() {
  renderTabs();
  syncPendingCount();
  const items = visible();
  const selected = openId();
  listEl.replaceChildren();

  if (!state.settings) {
    listEl.append(h("li", { class: "list-empty" }, "Connect to your Loopback server to see requests."));
    return;
  }
  if (state.loadError) {
    listEl.append(
      h(
        "li",
        { class: "list-empty error" },
        h("div", {}, state.loadError),
        h("button", { class: "link", onClick: () => void refresh() }, "Try again"),
      ),
    );
    return;
  }
  if (!state.loaded) {
    listEl.append(h("li", { class: "list-empty" }, "Loading…"));
    return;
  }
  if (items.length === 0) {
    listEl.append(
      h(
        "li",
        { class: "list-empty" },
        state.tab === "inbox"
          ? h("div", {}, h("strong", {}, "All caught up"), h("div", {}, "Questions and notifications from agents land here."))
          : "Nothing answered yet.",
      ),
    );
    return;
  }

  for (const r of items) {
    const unread = r.kind === "notify" && !r.readAt;
    const preview = previewLine(r.context);
    listEl.append(
      h(
        "li",
        {},
        h(
          "button",
          {
            class: `item${r.id === selected ? " selected" : ""}${unread ? " unread" : ""}`,
            "data-id": r.id,
            onClick: () => show({ kind: "request", id: r.id }),
          },
          avatar(r.source),
          h(
            "span",
            { class: "item-body" },
            h(
              "span",
              { class: "item-meta" },
              h("span", { class: "item-source" }, r.source || "Agent"),
              h("span", { class: "item-time", title: absoluteTime(r.createdAt) }, relativeTime(r.createdAt)),
            ),
            h("span", { class: "item-title" }, r.title),
            preview && h("span", { class: "item-preview" }, preview),
            (state.tab === "history" || r.kind === "notify") && h("span", { class: "item-pills" }, statusPill(r)),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Detail pane
// ---------------------------------------------------------------------------

function renderDetail() {
  detailEl.replaceChildren();
  detailEl.scrollTop = 0;
  const view = state.view;
  if (view.kind === "settings") return renderSettings();
  if (view.kind === "request") {
    const r = state.requests.get(view.id);
    if (r) return renderRequest(r);
  }
  const waiting = sorted().filter((r) => r.status === "pending").length;
  detailEl.append(
    h(
      "div",
      { class: "empty-detail" },
      h("img", { src: "/logo.png", alt: "", class: "empty-logo" }),
      h(
        "p",
        {},
        !state.settings
          ? "Set up the server to start."
          : waiting > 0
            ? `${waiting} question${waiting === 1 ? "" : "s"} waiting for you.`
            : "Nothing needs you right now.",
      ),
    ),
  );
}

function draftFor(id: string): Draft {
  let d = state.drafts.get(id);
  if (!d) state.drafts.set(id, (d = { selected: new Set(), text: "" }));
  return d;
}

function renderRequest(r: LoopbackRequest) {
  const isNotify = r.kind === "notify";
  const readOnly = r.status !== "pending";
  const draft = readOnly ? null : draftFor(r.id);

  const header = h(
    "header",
    { class: "detail-header" },
    avatar(r.source, "lg"),
    h(
      "div",
      { class: "detail-who" },
      h("div", { class: "detail-source" }, r.source || "Agent"),
      h("div", { class: "detail-time", title: absoluteTime(r.createdAt) }, `${relativeTime(r.createdAt)} · ${absoluteTime(r.createdAt)}`),
    ),
    (readOnly || isNotify) && statusPill(r),
  );

  const body = h("article", { class: "detail-body" }, header, h("h1", {}, r.title));
  if (r.context?.trim()) {
    const md = renderMarkdown(r.context);
    handleLinkClicks(md);
    body.append(md);
  }
  if (isNotify) {
    detailEl.append(body);
    return;
  }

  // Options
  let submitBtn: HTMLButtonElement | null = null;
  const updateSubmit = () => {
    if (submitBtn && draft) submitBtn.disabled = state.submitting || (draft.selected.size === 0 && !draft.text.trim());
  };

  if (r.options.length > 0) {
    const picked = new Set(readOnly ? (r.answer?.selected ?? []) : draft!.selected);
    const group = h("div", {
      class: "options",
      role: r.multiSelect ? "group" : "radiogroup",
      "aria-label": "Options",
    });
    r.options.forEach((opt, i) => {
      const input = h("input", {
        type: r.multiSelect ? "checkbox" : "radio",
        name: `opt-${r.id}`,
        value: opt.label,
        checked: picked.has(opt.label),
        disabled: readOnly,
        onChange: () => {
          if (!draft) return;
          if (r.multiSelect) {
            if (input.checked) draft.selected.add(opt.label);
            else draft.selected.delete(opt.label);
          } else {
            draft.selected = new Set([opt.label]);
          }
          syncOptionClasses(group, draft.selected);
          updateSubmit();
        },
      });
      group.append(
        h(
          "label",
          { class: `option${picked.has(opt.label) ? " checked" : ""}${readOnly ? " readonly" : ""}` },
          input,
          h(
            "span",
            { class: "option-text" },
            h("span", { class: "option-label" }, opt.label),
            opt.description && h("span", { class: "option-desc" }, opt.description),
          ),
          !readOnly && i < 9 && h("kbd", {}, String(i + 1)),
        ),
      );
    });
    body.append(
      h("div", { class: "section-label" }, r.multiSelect ? "Pick any" : "Pick one"),
      group,
    );
  }

  if (readOnly) {
    if (r.answer?.text) {
      body.append(h("div", { class: "section-label" }, "Your answer"), h("blockquote", { class: "answer-text" }, r.answer.text));
    }
    body.append(
      h(
        "p",
        { class: "resolution" },
        r.status === "answered" && r.answer
          ? `Answered ${relativeTime(r.answer.answeredAt)}.`
          : "The agent withdrew this request.",
      ),
    );
    detailEl.append(body);
    return;
  }

  const d = draft!;
  if (r.allowFreeText) {
    const textarea = h("textarea", {
      id: "answer-text",
      rows: 3,
      placeholder: r.options.length ? "Add a comment or answer in your own words" : "Type your answer",
      value: d.text,
      onInput: () => {
        d.text = textarea.value;
        updateSubmit();
      },
    });
    body.append(h("label", { class: "section-label", for: "answer-text" }, "My answer"), textarea);
  }

  submitBtn = h("button", { class: "primary", type: "button", onClick: () => void submit(r) }, state.submitting ? "Sending…" : "Send answer");
  updateSubmit();
  body.append(h("div", { class: "actions" }, submitBtn, h("span", { class: "hint" }, h("kbd", {}, "Ctrl"), " + ", h("kbd", {}, "Enter"))));
  detailEl.append(body);
}

function syncOptionClasses(group: HTMLElement, selected: Set<string>) {
  for (const label of group.querySelectorAll<HTMLLabelElement>("label.option")) {
    const input = label.querySelector("input")!;
    label.classList.toggle("checked", selected.has(input.value));
  }
}

async function submit(r: LoopbackRequest) {
  const d = state.drafts.get(r.id);
  if (!d || state.submitting) return;
  const text = d.text.trim();
  if (d.selected.size === 0 && !text) return;
  state.submitting = true;
  renderDetail();
  try {
    // Server wants labels in option order.
    const selected = r.options.map((o) => o.label).filter((l) => d.selected.has(l));
    upsert(await backend.answer(r.id, selected, text || null));
    toast("Answer sent");
  } catch (e) {
    toast(errorText(e), "error");
    // It may have been answered or cancelled elsewhere meanwhile.
    try {
      upsert(await backend.getRequest(r.id));
    } catch {}
  } finally {
    state.submitting = false;
    renderList();
    renderDetail();
  }
}

function renderSettings() {
  const s = state.settings;
  const url = h("input", { id: "set-url", type: "url", placeholder: "http://192.168.1.10:8787", value: s?.url ?? "", spellcheck: false });
  const key = h("input", { id: "set-key", type: "password", placeholder: "LOOPBACK_APP_KEY", value: s?.appKey ?? "", spellcheck: false, autocomplete: "off" });
  const reveal = h("button", { type: "button", class: "ghost", onClick: () => {
    key.type = key.type === "password" ? "text" : "password";
    reveal.textContent = key.type === "password" ? "Show" : "Hide";
  } }, "Show");
  const error = h("p", { class: "form-error", role: "alert" });
  const save = h("button", { class: "primary", type: "submit" }, "Save & connect");

  const form = h(
    "form",
    {
      class: "settings-form",
      onSubmit: async (e: Event) => {
        e.preventDefault();
        error.textContent = "";
        save.disabled = true;
        save.textContent = "Connecting…";
        try {
          await backend.saveSettings(url.value, key.value);
          state.settings = await backend.getSettings();
          toast("Connected");
          state.view = { kind: "empty" };
          await refresh();
        } catch (err) {
          error.textContent = errorText(err);
        } finally {
          save.disabled = false;
          save.textContent = "Save & connect";
        }
      },
    },
    h("label", { for: "set-url" }, "Server URL"),
    url,
    h("label", { for: "set-key" }, "App key"),
    h("div", { class: "row" }, key, reveal),
    h("p", { class: "help" }, "The server's LOOPBACK_APP_KEY, the same key the Android app uses. It's stored in your user config dir, readable only by you."),
    error,
    h("div", { class: "actions" }, save, s && h("button", { type: "button", class: "ghost", onClick: () => show({ kind: "empty" }) }, "Cancel")),
  );

  detailEl.append(
    h(
      "article",
      { class: "detail-body settings" },
      h("h1", {}, "Settings"),
      form,
      h("h2", {}, "Notifications"),
      h(
        "p",
        { class: "help" },
        "New questions and notifications pop up as desktop notifications while this window isn't focused. Closing the window keeps Loopback running in the tray, so they keep coming.",
      ),
      h("p", { class: "help" }, "To start at login without opening the window, add ", h("code", {}, "loopback-desktop --hidden"), " to your startup applications."),
      h("div", { class: "actions" }, h("button", { type: "button", class: "ghost danger", onClick: () => void backend.quit() }, "Quit Loopback")),
    ),
  );
  (s ? key : url).focus();
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

function show(view: View) {
  state.view = view;
  if (view.kind === "request") {
    const r = state.requests.get(view.id);
    if (r) {
      const tab: Tab = needsAttention(r) ? "inbox" : "history";
      if (tab !== state.tab) state.tab = tab;
      if (r.kind === "notify" && !r.readAt) void markRead(r.id);
    }
  }
  renderList();
  renderDetail();
  listEl.querySelector(".item.selected")?.scrollIntoView({ block: "nearest" });
}

async function markRead(id: string) {
  try {
    upsert(await backend.markRead(id));
    // The server's `read` event re-renders the list, moving it to History.
    renderTabs();
    syncPendingCount();
  } catch (e) {
    toast(errorText(e), "error");
  }
}

async function openRequest(id: string) {
  if (!state.requests.has(id)) {
    try {
      upsert(await backend.getRequest(id));
    } catch (e) {
      toast(errorText(e), "error");
      return;
    }
  }
  show({ kind: "request", id });
}

function moveSelection(delta: number) {
  const items = visible();
  if (items.length === 0) return;
  const i = items.findIndex((r) => r.id === openId());
  const next = items[Math.max(0, Math.min(items.length - 1, i < 0 ? 0 : i + delta))];
  show({ kind: "request", id: next.id });
}

function isTyping(target: EventTarget | null) {
  const el = target as HTMLElement | null;
  return !!el && (el.tagName === "TEXTAREA" || (el.tagName === "INPUT" && !["radio", "checkbox"].includes((el as HTMLInputElement).type)));
}

document.addEventListener("keydown", (e) => {
  const id = openId();
  const r = id ? state.requests.get(id) : undefined;
  if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && r?.status === "pending") {
    e.preventDefault();
    void submit(r);
    return;
  }
  if (isTyping(e.target) || e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key === "ArrowDown" || e.key === "j") {
    e.preventDefault();
    moveSelection(1);
  } else if (e.key === "ArrowUp" || e.key === "k") {
    e.preventDefault();
    moveSelection(-1);
  } else if (/^[1-9]$/.test(e.key) && r?.status === "pending" && r.kind === "ask") {
    const input = detailEl.querySelectorAll<HTMLInputElement>(".options input")[Number(e.key) - 1];
    if (input) {
      input.checked = r.multiSelect ? !input.checked : true;
      input.dispatchEvent(new Event("change"));
      input.focus();
    }
  }
});

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

async function init() {
  $<HTMLElement>("#toolbar").append(
    iconButton(icons.refresh, "Refresh", () => void refresh()),
    iconButton(icons.gear, "Settings", () => show({ kind: "settings" })),
  );
  for (const btn of document.querySelectorAll<HTMLButtonElement>(".tabs button")) {
    btn.addEventListener("click", () => {
      state.tab = btn.dataset.tab as Tab;
      renderList();
    });
  }

  await Promise.all([
    on.status((s) => {
      state.status = s;
      renderConnection();
    }),
    on.resync(() => void refresh()),
    on.request(({ type, request }) => {
      const wasOpen = openId() === request.id;
      const before = state.requests.get(request.id);
      upsert(request);
      renderList();
      // Re-render the open request only when its state changed, so a half-typed answer isn't reset.
      if (wasOpen && before?.status !== request.status) {
        renderDetail();
        if (type === "answered" || type === "cancelled") {
          toast(type === "answered" ? "Answered on another device" : "The agent withdrew this request");
        }
      } else if (state.view.kind === "empty") {
        renderDetail();
      }
    }),
    on.openRequest((id) => void openRequest(id)),
    on.toast((m) => toast(m, "error")),
  ]);

  state.settings = await backend.getSettings();
  state.status = await backend.connectionStatus();
  renderConnection();
  if (!state.settings) {
    state.view = { kind: "settings" };
    renderList();
    renderDetail();
  } else {
    renderList();
    renderDetail();
    await refresh();
  }
  // Keep "5m ago" labels honest.
  setInterval(renderList, 30_000);
}

void init();
