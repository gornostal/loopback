import type { BunRequest } from "bun";
import { Store } from "./db.ts";
import { Fcm } from "./fcm.ts";
import type { CreateRequestBody, LoopbackRequest, RequestStatus } from "./types.ts";

const PORT = Number(process.env.PORT ?? 8787);
const API_KEY = process.env.LOOPBACK_API_KEY;
const DATA_DIR = process.env.DATA_DIR ?? "./data";
const SERVICE_ACCOUNT = process.env.FIREBASE_SERVICE_ACCOUNT;
const SERVICE_ACCOUNT_B64 = process.env.FIREBASE_SERVICE_ACCOUNT_B64;
const MAX_WAIT_SECONDS = 600;

if (!API_KEY) {
  console.error("LOOPBACK_API_KEY is not set. Copy .env.example to .env and set a secret.");
  process.exit(1);
}

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

function requireAuth(req: Request) {
  const header = req.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
  if (token !== API_KEY) throw new HttpError(401, "Unauthorized");
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

/** Wraps a handler with auth + error → JSON conversion. */
function api<P extends string>(handler: Handler<P>): Handler<P> {
  return async (req) => {
    try {
      requireAuth(req);
      return await handler(req);
    } catch (err) {
      if (err instanceof HttpError) return json({ error: err.message }, err.status);
      console.error(err);
      return json({ error: "Internal error" }, 500);
    }
  };
}

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
          "POST   /api/requests[?wait=N]      create a request (optionally wait N s for the answer)",
          "GET    /api/requests?status=pending list requests (pending|answered|cancelled|all)",
          "GET    /api/requests/:id           fetch one",
          "GET    /api/requests/:id/wait?timeout=N  long-poll until answered/cancelled",
          "POST   /api/requests/:id/answer    { selected: string[], text?: string }",
          "DELETE /api/requests/:id           cancel",
          "POST   /api/devices                { token, platform, name? } register push token",
          "",
          "All /api routes need `Authorization: Bearer <LOOPBACK_API_KEY>`.",
        ].join("\n"),
        { headers: { "Content-Type": "text/plain" } },
      ),
    "/health": () => json({ ok: true, fcm: Boolean(fcm) }),

    "/api/requests": {
      GET: api((req) => {
        const url = new URL(req.url);
        const status = (url.searchParams.get("status") ?? "pending") as RequestStatus | "all";
        if (!["pending", "answered", "cancelled", "all"].includes(status)) {
          throw new HttpError(400, "status must be pending|answered|cancelled|all");
        }
        const limit = Math.min(Number(url.searchParams.get("limit") ?? 100) || 100, 500);
        return json({ requests: store.listRequests(status, limit) });
      }),
      POST: api(async (req) => {
        const body = validateCreate(await readJson(req));
        const created = store.createRequest(body);
        console.log(`New request ${created.id} from ${created.source ?? "unknown"}: ${created.title}`);
        pushForNewRequest(created);
        const wait = clampWait(new URL(req.url).searchParams.get("wait"));
        if (wait > 0) return json(await waitForResolution(created, wait));
        return json(created, 201);
      }),
    },

    "/api/requests/:id": {
      GET: api((req) => json(getOr404(req.params.id))),
      DELETE: api((req) => {
        getOr404(req.params.id);
        const cancelled = store.cancelRequest(req.params.id);
        if (!cancelled) throw new HttpError(409, "Request is no longer pending");
        notifyWaiters(cancelled);
        pushForCancelled(cancelled);
        return json(cancelled);
      }),
    },

    "/api/requests/:id/wait": {
      GET: api(async (req) => {
        const current = getOr404(req.params.id);
        const timeout = clampWait(new URL(req.url).searchParams.get("timeout")) || 60;
        return json(await waitForResolution(current, timeout));
      }),
    },

    "/api/requests/:id/answer": {
      POST: api(async (req) => {
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

    "/api/devices": {
      GET: api(() =>
        json({
          devices: store.listDevices().map((d) => ({ ...d, token: `${d.token.slice(0, 12)}…` })),
        }),
      ),
      POST: api(async (req) => {
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
