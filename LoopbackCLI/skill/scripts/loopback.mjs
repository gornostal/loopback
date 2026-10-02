#!/usr/bin/env node
// Loopback: ask a human a question over HTTP and wait for (or poll for) the answer.
// Plain Node ≥ 18, no dependencies. Also importable: the Loopback CLI reuses these functions.
//
//   loopback.mjs ask "<title>" [--context "<md>"] [--option "Label :: description"]... [--multi] [--no-text]
//                              [--source <name>] [--timeout <seconds>] [--no-wait]
//   loopback.mjs wait <id> [--timeout <seconds>]
//   loopback.mjs status <id>
//
// Config: LOOPBACK_URL + LOOPBACK_API_KEY env vars, else ~/.config/loopback/config.json {"url","apiKey"}.
// Exit codes: 0 answered (or created with --no-wait) · 2 cancelled · 3 still pending after timeout · 1 error.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const CONFIG_PATH = join(homedir(), ".config", "loopback", "config.json");

/** Longest single HTTP long-poll; the server caps at 600 s and we stay under proxies' idle limits. */
const POLL_CHUNK_SECONDS = 120;

export function loadConfig() {
  let fileConfig = {};
  try {
    fileConfig = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
  } catch {
    // no config file; env vars may still be set
  }
  const url = (process.env.LOOPBACK_URL || fileConfig.url || "").replace(/\/+$/, "");
  const apiKey = process.env.LOOPBACK_API_KEY || fileConfig.apiKey || "";
  if (!url || !apiKey) {
    throw new Error(
      `Loopback is not configured. Set LOOPBACK_URL and LOOPBACK_API_KEY, or create ${CONFIG_PATH} with {"url": "...", "apiKey": "..."}.`,
    );
  }
  return { url, apiKey };
}

async function call(config, method, path, body) {
  const res = await fetch(config.url + path, {
    method,
    headers: { Authorization: `Bearer ${config.apiKey}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) {
    let message = text;
    try {
      message = JSON.parse(text).error ?? text;
    } catch {
      // not JSON
    }
    throw new Error(`${method} ${path} → ${res.status}: ${message}`);
  }
  return JSON.parse(text);
}

/** Creates a request; returns the server's request object (status "pending"). */
export function createRequest(config, { title, context, options = [], multiSelect = false, allowFreeText = true, source }) {
  return call(config, "POST", "/api/requests", { title, context, options, multiSelect, allowFreeText, source });
}

export function getRequest(config, id) {
  return call(config, "GET", `/api/requests/${encodeURIComponent(id)}`);
}

/**
 * Long-polls until the request is answered/cancelled or `timeoutSeconds` elapse.
 * Returns the request object; check `.status`.
 */
export async function waitForAnswer(config, id, timeoutSeconds) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let request = await getRequest(config, id);
  while (request.status === "pending") {
    const remaining = Math.ceil((deadline - Date.now()) / 1000);
    if (remaining <= 0) break;
    const chunk = Math.min(POLL_CHUNK_SECONDS, remaining);
    request = await call(config, "GET", `/api/requests/${encodeURIComponent(id)}/wait?timeout=${chunk}`);
  }
  return request;
}

/** "Label :: description" → { label, description }. */
export function parseOption(raw) {
  const [label, ...rest] = raw.split("::");
  const description = rest.join("::").trim();
  return description ? { label: label.trim(), description } : { label: label.trim() };
}

/** What the agent needs to see, nothing else. */
export function summarize(request) {
  return {
    id: request.id,
    status: request.status,
    title: request.title,
    ...(request.answer ? { answer: { selected: request.answer.selected, text: request.answer.text ?? null } } : {}),
  };
}

export function exitCodeFor(request) {
  if (request.status === "answered") return 0;
  if (request.status === "cancelled") return 2;
  return 3;
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const positional = [];
  const flags = { option: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) {
      positional.push(arg);
      continue;
    }
    const name = arg.slice(2);
    if (["multi", "no-text", "no-wait", "help"].includes(name)) {
      flags[name] = true;
      continue;
    }
    const value = argv[++i];
    if (value === undefined) throw new Error(`--${name} needs a value`);
    if (name === "option") flags.option.push(value);
    else flags[name] = value;
  }
  return { positional, flags };
}

const USAGE = `Usage:
  loopback.mjs ask "<title>" [--context "<markdown>"] [--option "Label :: description"]...
                 [--multi] [--no-text] [--source <name>] [--timeout <seconds>] [--no-wait]
  loopback.mjs wait <id> [--timeout <seconds>]
  loopback.mjs status <id>

Prints JSON {id, status, title, answer?} on stdout.
Exit codes: 0 answered · 2 cancelled · 3 still pending (re-run "wait <id>") · 1 error`;

export async function main(argv) {
  const { positional, flags } = parseArgs(argv);
  const [command, ...rest] = positional;
  if (!command || flags.help) {
    console.log(USAGE);
    return 0;
  }
  const config = loadConfig();

  if (command === "ask") {
    const title = rest[0];
    if (!title) throw new Error("ask needs a title");
    const created = await createRequest(config, {
      title,
      context: flags.context,
      options: flags.option.map(parseOption),
      multiSelect: Boolean(flags.multi),
      allowFreeText: !flags["no-text"],
      source: flags.source ?? "agent",
    });
    if (flags["no-wait"]) {
      console.log(JSON.stringify(summarize(created), null, 2));
      return 0;
    }
    const timeout = Number(flags.timeout ?? 300);
    console.error(`Loopback: asked "${title}" (id ${created.id}); waiting up to ${timeout}s…`);
    const result = await waitForAnswer(config, created.id, timeout);
    console.log(JSON.stringify(summarize(result), null, 2));
    return exitCodeFor(result);
  }

  if (command === "wait") {
    const id = rest[0];
    if (!id) throw new Error("wait needs a request id");
    const timeout = Number(flags.timeout ?? 300);
    const result = await waitForAnswer(config, id, timeout);
    console.log(JSON.stringify(summarize(result), null, 2));
    return exitCodeFor(result);
  }

  if (command === "status") {
    const id = rest[0];
    if (!id) throw new Error("status needs a request id");
    const result = await getRequest(config, id);
    console.log(JSON.stringify(summarize(result), null, 2));
    return exitCodeFor(result);
  }

  throw new Error(`Unknown command "${command}"\n\n${USAGE}`);
}

// Only run as a CLI when invoked as `node loopback.mjs …`, not when imported (e.g. bundled into the Loopback CLI).
const isDirectRun = Boolean(process.argv[1]) && /loopback\.mjs$/.test(process.argv[1]) &&
  fileURLToPath(import.meta.url) === process.argv[1];
if (isDirectRun) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (err) => {
      console.error(`Loopback error: ${err.message}`);
      process.exit(1);
    },
  );
}
