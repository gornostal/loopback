/** A choice the agent offers the human. Mirrors Claude Code's AskUserQuestion options. */
export interface RequestOption {
  label: string;
  description?: string;
}

/**
 * `ask` wants an answer and starts out `pending`. `notify` is one-way: the agent tells the human
 * something, the server pushes it and stores it as `notified` so it shows up in the inbox history.
 */
export type RequestKind = "ask" | "notify";

export type RequestStatus = "pending" | "answered" | "cancelled" | "notified";

/** What the human sent back. */
export interface Answer {
  /** Labels of the chosen options (empty if the user only typed a free-text answer). */
  selected: string[];
  /** Free-text "my answer", if the user typed one. */
  text?: string;
  answeredAt: string;
}

/** Body accepted by `POST /api/agent/requests`. */
export interface CreateRequestBody {
  /** Short question or headline. Shown as the push title. */
  title: string;
  /** Longer explanation, markdown allowed. */
  context?: string;
  /** Choices to present. May be empty when only a free-text answer is wanted. */
  options?: RequestOption[];
  /** Allow picking several options. Default false. */
  multiSelect?: boolean;
  /** Show the free-text "my answer" field. Default true. */
  allowFreeText?: boolean;
  /** Who is asking, e.g. "claude-code", "deploy-bot". Shown in the inbox. */
  source?: string;
}

/** Body accepted by `POST /api/agent/notifications`. No options, no answer expected. */
export interface CreateNotificationBody {
  /** Headline. Shown as the push title. */
  title: string;
  /** The message itself, markdown allowed. */
  context?: string;
  /** Who is notifying, e.g. "claude-code". Shown in the inbox. */
  source?: string;
}

export interface LoopbackRequest {
  id: string;
  createdAt: string;
  kind: RequestKind;
  status: RequestStatus;
  title: string;
  context: string | null;
  options: RequestOption[];
  multiSelect: boolean;
  allowFreeText: boolean;
  source: string | null;
  answer: Answer | null;
}

export interface Device {
  token: string;
  platform: string;
  name: string | null;
  createdAt: string;
}
