/** A choice the agent offers the human. Mirrors Claude Code's AskUserQuestion options. */
export interface RequestOption {
  label: string;
  description?: string;
}

export type RequestStatus = "pending" | "answered" | "cancelled";

/** What the human sent back. */
export interface Answer {
  /** Labels of the chosen options (empty if the user only typed a free-text answer). */
  selected: string[];
  /** Free-text "my answer", if the user typed one. */
  text?: string;
  answeredAt: string;
}

/** Body accepted by `POST /api/requests`. */
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

export interface LoopbackRequest {
  id: string;
  createdAt: string;
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
