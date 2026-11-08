// Typed wrappers around the Rust commands and events (src-tauri/src/lib.rs, events.rs).

import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { ConnectionStatus, LoopbackRequest, RequestEvent, Settings } from "./types";

export const backend = {
  getSettings: () => invoke<Settings | null>("get_settings"),
  saveSettings: (url: string, appKey: string) => invoke<void>("save_settings", { url, appKey }),
  connectionStatus: () => invoke<ConnectionStatus>("connection_status"),
  reconnect: () => invoke<void>("reconnect"),
  listRequests: (status: string, limit = 500) => invoke<LoopbackRequest[]>("list_requests", { status, limit }),
  getRequest: (id: string) => invoke<LoopbackRequest>("get_request", { id }),
  answer: (id: string, selected: string[], text: string | null) =>
    invoke<LoopbackRequest>("answer_request", { id, selected, text }),
  markRead: (id: string) => invoke<LoopbackRequest>("mark_read", { id }),
  setPendingCount: (count: number) => invoke<void>("set_pending_count", { count }),
  quit: () => invoke<void>("quit"),
};

export const on = {
  status: (cb: (s: ConnectionStatus) => void): Promise<UnlistenFn> =>
    listen<ConnectionStatus>("connection-status", (e) => cb(e.payload)),
  /** Stream (re)connected: refetch, events may have been missed. */
  resync: (cb: () => void): Promise<UnlistenFn> => listen("resync", () => cb()),
  request: (cb: (e: RequestEvent) => void): Promise<UnlistenFn> =>
    listen<RequestEvent>("request-event", (e) => cb(e.payload)),
  /** A desktop notification was clicked. */
  openRequest: (cb: (id: string) => void): Promise<UnlistenFn> => listen<string>("open-request", (e) => cb(e.payload)),
  toast: (cb: (message: string) => void): Promise<UnlistenFn> => listen<string>("toast", (e) => cb(e.payload)),
};

/** Tauri command errors arrive as plain strings. */
export function errorText(err: unknown): string {
  if (typeof err === "string") return err;
  if (err instanceof Error) return err.message;
  return String(err);
}
