// Mirrors the server's JSON (LoopbackServer/src/types.ts). Keep in sync with src-tauri/src/models.rs.

export interface RequestOption {
  label: string;
  description?: string;
}

export interface Answer {
  selected: string[];
  text?: string;
  answeredAt: string;
}

export interface LoopbackRequest {
  id: string;
  createdAt: string;
  kind: "ask" | "notify";
  status: "pending" | "answered" | "cancelled" | "notified";
  title: string;
  context: string | null;
  options: RequestOption[];
  multiSelect: boolean;
  allowFreeText: boolean;
  source: string | null;
  answer: Answer | null;
  readAt: string | null;
}

export interface Settings {
  url: string;
  appKey: string;
}

export type ConnectionStatus =
  | { state: "unconfigured" }
  | { state: "connecting" }
  | { state: "connected" }
  | { state: "error"; message: string };

export type EventType = "request" | "notification" | "answered" | "cancelled" | "read";

export interface RequestEvent {
  type: EventType;
  request: LoopbackRequest;
}

/** Needs the human: a question still pending, or a notification not opened yet. */
export function needsAttention(r: LoopbackRequest): boolean {
  return r.status === "pending" || (r.kind === "notify" && !r.readAt);
}
