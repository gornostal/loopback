import { Database } from "bun:sqlite";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import type { Answer, CreateRequestBody, Device, LoopbackRequest, RequestStatus } from "./types.ts";

interface RequestRow {
  id: string;
  created_at: string;
  status: RequestStatus;
  title: string;
  context: string | null;
  options: string;
  multi_select: number;
  allow_free_text: number;
  source: string | null;
  answer: string | null;
}

interface DeviceRow {
  token: string;
  platform: string;
  name: string | null;
  created_at: string;
}

export class Store {
  private db: Database;

  constructor(dataDir: string) {
    mkdirSync(dataDir, { recursive: true });
    this.db = new Database(join(dataDir, "loopback.db"), { create: true });
    this.db.exec("PRAGMA journal_mode = WAL;");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS requests (
        id TEXT PRIMARY KEY,
        created_at TEXT NOT NULL,
        status TEXT NOT NULL,
        title TEXT NOT NULL,
        context TEXT,
        options TEXT NOT NULL,
        multi_select INTEGER NOT NULL DEFAULT 0,
        allow_free_text INTEGER NOT NULL DEFAULT 1,
        source TEXT,
        answer TEXT
      );
      CREATE INDEX IF NOT EXISTS requests_status_created ON requests(status, created_at DESC);
      CREATE TABLE IF NOT EXISTS devices (
        token TEXT PRIMARY KEY,
        platform TEXT NOT NULL,
        name TEXT,
        created_at TEXT NOT NULL
      );
    `);
  }

  createRequest(body: CreateRequestBody): LoopbackRequest {
    const req: LoopbackRequest = {
      id: newId(),
      createdAt: new Date().toISOString(),
      status: "pending",
      title: body.title,
      context: body.context ?? null,
      options: body.options ?? [],
      multiSelect: body.multiSelect ?? false,
      allowFreeText: body.allowFreeText ?? true,
      source: body.source ?? null,
      answer: null,
    };
    this.db
      .query(
        `INSERT INTO requests (id, created_at, status, title, context, options, multi_select, allow_free_text, source, answer)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
      )
      .run(
        req.id,
        req.createdAt,
        req.status,
        req.title,
        req.context,
        JSON.stringify(req.options),
        req.multiSelect ? 1 : 0,
        req.allowFreeText ? 1 : 0,
        req.source,
      );
    return req;
  }

  getRequest(id: string): LoopbackRequest | null {
    const row = this.db.query<RequestRow, [string]>("SELECT * FROM requests WHERE id = ?").get(id);
    return row ? toRequest(row) : null;
  }

  listRequests(status: RequestStatus | "all", limit = 100): LoopbackRequest[] {
    const rows =
      status === "all"
        ? this.db.query<RequestRow, [number]>("SELECT * FROM requests ORDER BY created_at DESC LIMIT ?").all(limit)
        : this.db
            .query<RequestRow, [string, number]>("SELECT * FROM requests WHERE status = ? ORDER BY created_at DESC LIMIT ?")
            .all(status, limit);
    return rows.map(toRequest);
  }

  /** Records an answer. Returns null if the request doesn't exist or is no longer pending. */
  answerRequest(id: string, answer: Omit<Answer, "answeredAt">): LoopbackRequest | null {
    const full: Answer = { ...answer, answeredAt: new Date().toISOString() };
    const result = this.db
      .query("UPDATE requests SET status = 'answered', answer = ? WHERE id = ? AND status = 'pending'")
      .run(JSON.stringify(full), id);
    if (result.changes === 0) return null;
    return this.getRequest(id);
  }

  cancelRequest(id: string): LoopbackRequest | null {
    const result = this.db
      .query("UPDATE requests SET status = 'cancelled' WHERE id = ? AND status = 'pending'")
      .run(id);
    if (result.changes === 0) return null;
    return this.getRequest(id);
  }

  /**
   * Deletes resolved (answered/cancelled) requests that are older than `maxAgeMs` or outside the
   * most recent `keep`. Pending requests are never touched: agents may still be waiting on them.
   * Returns the number of rows removed.
   */
  pruneHistory(maxAgeMs: number, keep: number): number {
    const cutoff = new Date(Date.now() - maxAgeMs).toISOString();
    const result = this.db
      .query(
        `DELETE FROM requests
         WHERE status != 'pending'
           AND (
             created_at < ?
             OR id NOT IN (
               SELECT id FROM requests WHERE status != 'pending' ORDER BY created_at DESC LIMIT ?
             )
           )`,
      )
      .run(cutoff, keep);
    return result.changes;
  }

  upsertDevice(token: string, platform: string, name: string | null): Device {
    const createdAt = new Date().toISOString();
    this.db
      .query(
        `INSERT INTO devices (token, platform, name, created_at) VALUES (?, ?, ?, ?)
         ON CONFLICT(token) DO UPDATE SET platform = excluded.platform, name = excluded.name`,
      )
      .run(token, platform, name, createdAt);
    return { token, platform, name, createdAt };
  }

  listDevices(): Device[] {
    return this.db
      .query<DeviceRow, []>("SELECT * FROM devices")
      .all()
      .map((r) => ({ token: r.token, platform: r.platform, name: r.name, createdAt: r.created_at }));
  }

  deleteDevice(token: string): void {
    this.db.query("DELETE FROM devices WHERE token = ?").run(token);
  }
}

function toRequest(row: RequestRow): LoopbackRequest {
  return {
    id: row.id,
    createdAt: row.created_at,
    status: row.status,
    title: row.title,
    context: row.context,
    options: JSON.parse(row.options),
    multiSelect: row.multi_select === 1,
    allowFreeText: row.allow_free_text === 1,
    source: row.source,
    answer: row.answer ? JSON.parse(row.answer) : null,
  };
}

const BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

/**
 * 128 random bits, base58-encoded (~22 chars). Agents can't list requests, so knowing an id is what
 * lets an agent read its own request; it must be unguessable.
 */
function newId(): string {
  let n = 0n;
  for (const byte of crypto.getRandomValues(new Uint8Array(16))) n = (n << 8n) | BigInt(byte);
  let out = "";
  while (n > 0n) {
    out = BASE58[Number(n % 58n)] + out;
    n /= 58n;
  }
  return out;
}
