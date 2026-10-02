import type { BunRequest } from "bun";
import { Store } from "./db.ts";
import { Fcm } from "./fcm.ts";
import type { CreateRequestBody, LoopbackRequest, RequestStatus } from "./types.ts";

const PORT = Number(process.env.PORT ?? 8787);
const APP_KEY = process.env.LOOPBACK_APP_KEY ?? process.env.LOOPBACK_API_KEY;
const AGENT_KEY = process.env.LOOPBACK_AGENT_KEY;
const DATA_DIR = process.env.DATA_DIR ?? "./data";
const SERVICE_ACCOUNT = process.env.FIREBASE_SERVICE_ACCOUNT;
const SERVICE_ACCOUNT_B64 = process.env.FIREBASE_SERVICE_ACCOUNT_B64;
const MAX_WAIT_SECONDS = 600;
const HISTORY_MAX_AGE_MS = 60 * 24 * 60 * 60 * 1000; // ~2 months
const HISTORY_KEEP = 50;
const PRUNE_INTERVAL_MS = 60 * 1000;

if (!APP_KEY || !AGENT_KEY) {
  console.error("LOOPBACK_APP_KEY and LOOPBACK_AGENT_KEY must be set. Copy .env.example to .env and set two secrets.");
  process.exit(1);
}
if (APP_KEY === AGENT_KEY) {
  console.error("LOOPBACK_APP_KEY and LOOPBACK_AGENT_KEY must differ, or agents could read the whole inbox.");
  process.exit(1);
}
if (!process.env.LOOPBACK_APP_KEY) console.warn("LOOPBACK_API_KEY is deprecated; rename it to LOOPBACK_APP_KEY.");

const store = new Store(DATA_DIR);
const fcm = await loadFcm();
if (fcm) console.log(`FCM enabled for project ${fcm.projectId}`);
else {
  console.warn(
    "FCM disabled: set FIREBASE_SERVICE_ACCOUNT_B64 (base64 JSON) or FIREBASE_SERVICE_ACCOUNT (file path). Requests will not push.",
  );
}

async function loadFcm(): Promise<Fcm | null> {
  if (SERVICE_ACCOUNT_B64) return Fcm.fromBase64(SERVICE_ACCOUNT_B64);
  if (SERVICE_ACCOUNT && (await Bun.file(SERVICE_ACCOUNT).exists())) return Fcm.fromFile(SERVICE_ACCOUNT);
  return null;
}

// ---------------------------------------------------------------------------
// History retention: every minute, drop answered/cancelled requests older than
// HISTORY_MAX_AGE_MS and keep at most HISTORY_KEEP of the rest.
// ---------------------------------------------------------------------------

function pruneHistory() {
  try {
    const removed = store.pruneHistory(HISTORY_MAX_AGE_MS, HISTORY_KEEP);
    if (removed > 0) console.log(`Pruned ${removed} old request(s) from history`);
  } catch (err) {
    console.error("History prune failed:", err);
  }
}

pruneHistory();
setInterval(pruneHistory, PRUNE_INTERVAL_MS).unref();

// ---------------------------------------------------------------------------
// Long-poll waiters: request id -> resolvers waiting for a terminal state.
// ---------------------------------------------------------------------------

const waiters = new Map<string, Set<(r: LoopbackRequest) => void>>();

function notifyWaiters(req: LoopbackRequest) {
  const set = waiters.get(req.id);
  if (!set) return;
  waiters.delete(req.id);
  for (const resolve of set) resolve(req);
}

function waitForResolution(req: LoopbackRequest, timeoutSeconds: number): Promise<LoopbackRequest> {
  if (req.status !== "pending") return Promise.resolve(req);
  return new Promise((resolve) => {
    let set = waiters.get(req.id);
    if (!set) waiters.set(req.id, (set = new Set()));
    const timer = setTimeout(() => {
      set!.delete(done);
      if (set!.size === 0) waiters.delete(req.id);
      resolve(store.getRequest(req.id) ?? req);
    }, timeoutSeconds * 1000);
    const done = (r: LoopbackRequest) => {
      clearTimeout(timer);
      resolve(r);
    };
    set.add(done);
  });
}

// ---------------------------------------------------------------------------
// Push
// ---------------------------------------------------------------------------

async function pushToAllDevices(data: Record<string, string>) {
  if (!fcm) return;
  const devices = store.listDevices();
  if (devices.length === 0) {
    console.warn("No devices registered; nothing to push.");
    return;
  }
  await Promise.all(
    devices.map(async (device) => {
      try {
        const result = await fcm.sendData(device.token, data);
        if (!result.ok) {
          console.error(`Push to ${device.name ?? device.token.slice(0, 12)} failed: ${result.error}`);
          if (result.unregistered) store.deleteDevice(device.token);
        }
      } catch (err) {
        console.error("Push error:", err);
      }
    }),
  );
}

function pushForNewRequest(req: LoopbackRequest) {
  const summary = (req.context ?? "").split("\n").find((l) => l.trim())?.trim() ?? "";
  void pushToAllDevices({
    type: "request",
    requestId: req.id,
    title: req.title,
    body: summary.length > 200 ? `${summary.slice(0, 197)}…` : summary,
    source: req.source ?? "",
    createdAt: req.createdAt,
  });
}

function pushForCancelled(req: LoopbackRequest) {
  void pushToAllDevices({ type: "cancelled", requestId: req.id });
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status });
}

function requireKey(req: Request, key: string) {
  const header = req.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
  if (token !== key) throw new HttpError(401, "Unauthorized");
}

async function readJson<T>(req: Request): Promise<T> {
  try {
    return (await req.json()) as T;
  } catch {
    throw new HttpError(400, "Body must be JSON");
  }
}

function clampWait(value: string | null): number {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.min(n, MAX_WAIT_SECONDS);
}

type Handler<P extends string> = (req: BunRequest<P>) => Response | Promise<Response>;

/** Wraps a handler with auth against `key` + error → JSON conversion. */
function withKey<P extends string>(key: string, handler: Handler<P>): Handler<P> {
  return async (req) => {
    try {
      requireKey(req, key);
      return await handler(req);
    } catch (err) {
      if (err instanceof HttpError) return json({ error: err.message }, err.status);
      console.error(err);
      return json({ error: "Internal error" }, 500);
    }
  };
}

/** `/api/agent/*`: create requests and follow the ones you know the id of. */
const agent = <P extends string>(handler: Handler<P>) => withKey(AGENT_KEY, handler);
/** `/api/app/*`: the phone. Lists, answers, registers devices. */
const app = <P extends string>(handler: Handler<P>) => withKey(APP_KEY, handler);

function validateCreate(body: unknown): CreateRequestBody {
  if (!body || typeof body !== "object") throw new HttpError(400, "Body must be an object");
  const b = body as Record<string, unknown>;
  if (typeof b.title !== "string" || !b.title.trim()) throw new HttpError(400, "`title` is required");
  if (b.context !== undefined && typeof b.context !== "string") throw new HttpError(400, "`context` must be a string");
  if (b.source !== undefined && typeof b.source !== "string") throw new HttpError(400, "`source` must be a string");
  const options = b.options === undefined ? [] : b.options;
  if (!Array.isArray(options)) throw new HttpError(400, "`options` must be an array");
  const parsed = options.map((o, i) => {
    if (typeof o === "string") return { label: o };
    if (!o || typeof o !== "object" || typeof (o as any).label !== "string" || !(o as any).label.trim()) {
      throw new HttpError(400, `options[${i}] needs a string \`label\``);
    }
    const d = (o as any).description;
    return { label: (o as any).label, ...(typeof d === "string" && d ? { description: d } : {}) };
  });
  const allowFreeText = b.allowFreeText === undefined ? true : Boolean(b.allowFreeText);
  if (parsed.length === 0 && !allowFreeText) {
    throw new HttpError(400, "A request needs options, free text, or both");
  }
  return {
    title: b.title.trim(),
    context: b.context as string | undefined,
    options: parsed,
    multiSelect: Boolean(b.multiSelect),
    allowFreeText,
    source: b.source as string | undefined,
  };
}

function getOr404(id: string): LoopbackRequest {
  const req = store.getRequest(id);
  if (!req) throw new HttpError(404, "Request not found");
  return req;
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

const server = Bun.serve({
  port: PORT,
  idleTimeout: 255, // seconds; long-polls are capped below this per chunk by clients
  routes: {
    "/": () =>
      new Response(
        [
          "Loopback server",
          "",
          "Agent API — Authorization: Bearer <LOOPBACK_AGENT_KEY>",
          "  POST   /api/agent/requests[?wait=N]          create a request (optionally wait N s for the answer)",
          "  GET    /api/agent/requests/:id               fetch one",
          "  GET    /api/agent/requests/:id/wait?timeout=N  long-poll until answered/cancelled",
          "  DELETE /api/agent/requests/:id               cancel",
          "",
          "App API — Authorization: Bearer <LOOPBACK_APP_KEY>",
          "  GET    /api/app/requests?status=pending      list (pending|answered|cancelled|all)",
          "  GET    /api/app/requests/:id                 fetch one",
          "  POST   /api/app/requests/:id/answer          { selected: string[], text?: string }",
          "  GET    /api/app/devices                      list registered devices",
          "  POST   /api/app/devices                      { token, platform, name? } register push token",
        ].join("\n"),
        { headers: { "Content-Type": "text/plain" } },
      ),
    "/health": () => json({ ok: true, fcm: Boolean(fcm) }),

    // --- Agent API --------------------------------------------------------

    "/api/agent/requests": {
      POST: agent(async (req) => {
        const body = validateCreate(await readJson(req));
        const created = store.createRequest(body);
        console.log(`New request ${created.id} from ${created.source ?? "unknown"}: ${created.title}`);
        pushForNewRequest(created);
        const wait = clampWait(new URL(req.url).searchParams.get("wait"));
        if (wait > 0) return json(await waitForResolution(created, wait));
        return json(created, 201);
      }),
    },

    "/api/agent/requests/:id": {
      GET: agent((req) => json(getOr404(req.params.id))),
      DELETE: agent((req) => {
        getOr404(req.params.id);
        const cancelled = store.cancelRequest(req.params.id);
        if (!cancelled) throw new HttpError(409, "Request is no longer pending");
        notifyWaiters(cancelled);
        pushForCancelled(cancelled);
        return json(cancelled);
      }),
    },

    "/api/agent/requests/:id/wait": {
      GET: agent(async (req) => {
        const current = getOr404(req.params.id);
        const timeout = clampWait(new URL(req.url).searchParams.get("timeout")) || 60;
        return json(await waitForResolution(current, timeout));
      }),
    },

    // --- App API ----------------------------------------------------------

    "/api/app/requests": {
      GET: app((req) => {
        const url = new URL(req.url);
        const status = (url.searchParams.get("status") ?? "pending") as RequestStatus | "all";
        if (!["pending", "answered", "cancelled", "all"].includes(status)) {
          throw new HttpError(400, "status must be pending|answered|cancelled|all");
        }
        const limit = Math.min(Number(url.searchParams.get("limit") ?? 100) || 100, 500);
        return json({ requests: store.listRequests(status, limit) });
      }),
    },

    "/api/app/requests/:id": {
      GET: app((req) => json(getOr404(req.params.id))),
    },

    "/api/app/requests/:id/answer": {
      POST: app(async (req) => {
        getOr404(req.params.id);
        const body = await readJson<{ selected?: unknown; text?: unknown }>(req);
        const selected = Array.isArray(body.selected) ? body.selected.filter((s) => typeof s === "string") : [];
        const text = typeof body.text === "string" && body.text.trim() ? body.text.trim() : undefined;
        if (selected.length === 0 && !text) throw new HttpError(400, "Pick an option or type an answer");
        const answered = store.answerRequest(req.params.id, { selected, text });
        if (!answered) throw new HttpError(409, "Request is no longer pending");
        console.log(`Request ${answered.id} answered: ${JSON.stringify(answered.answer)}`);
        notifyWaiters(answered);
        return json(answered);
      }),
    },

    "/api/app/devices": {
      GET: app(() =>
        json({
          devices: store.listDevices().map((d) => ({ ...d, token: `${d.token.slice(0, 12)}…` })),
        }),
      ),
      POST: app(async (req) => {
        const body = await readJson<{ token?: unknown; platform?: unknown; name?: unknown }>(req);
        if (typeof body.token !== "string" || !body.token) throw new HttpError(400, "`token` is required");
        const platform = typeof body.platform === "string" ? body.platform : "android";
        const name = typeof body.name === "string" ? body.name : null;
        store.upsertDevice(body.token, platform, name);
        console.log(`Device registered: ${name ?? "unnamed"} (${platform})`);
        return json({ ok: true });
      }),
    },
  },
  fetch() {
    return json({ error: "Not found" }, 404);
  },
});

console.log(`Loopback listening on http://localhost:${server.port}`);
