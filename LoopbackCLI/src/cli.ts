#!/usr/bin/env bun
/**
 * Loopback CLI.
 *
 *   loopback install [--claude] [--codex] [--project] [--url <u> --key <k>]   install the skill
 *   loopback uninstall [--claude] [--codex] [--project]
 *   loopback config --url <u> --key <k>                                      write ~/.config/loopback/config.json
 *   loopback ask|wait|status …                                               same as the skill script
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, chmodSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { parseArgs } from "node:util";
// The skill's own script doubles as our client library so there is one implementation.
import { CONFIG_PATH, main as scriptMain } from "../skill/scripts/loopback.mjs";
// The skill files are inlined at build time (Bun macro) so `bun build --compile` is self-contained.
import { embedSkillFile } from "./embed.ts" with { type: "macro" };

const skillTemplate = embedSkillFile("SKILL.md");
const skillScript = embedSkillFile("scripts/loopback.mjs");

const SKILL_NAME = "loopback";

interface Target {
  agent: "claude" | "codex";
  dir: string;
}

function targets(opts: { claude: boolean; codex: boolean; project: boolean }): Target[] {
  const base = opts.project ? process.cwd() : homedir();
  const out: Target[] = [];
  if (opts.claude) out.push({ agent: "claude", dir: join(base, ".claude", "skills", SKILL_NAME) });
  if (opts.codex) out.push({ agent: "codex", dir: join(base, ".codex", "skills", SKILL_NAME) });
  return out;
}

function installSkill(target: Target) {
  mkdirSync(join(target.dir, "scripts"), { recursive: true });
  writeFileSync(join(target.dir, "SKILL.md"), skillTemplate.replaceAll("{{SKILL_DIR}}", target.dir));
  const scriptPath = join(target.dir, "scripts", "loopback.mjs");
  writeFileSync(scriptPath, skillScript);
  chmodSync(scriptPath, 0o755);
  console.log(`✓ ${target.agent.padEnd(6)} ${target.dir}`);
}

function uninstallSkill(target: Target) {
  if (!existsSync(target.dir)) {
    console.log(`– ${target.agent.padEnd(6)} not installed at ${target.dir}`);
    return;
  }
  rmSync(target.dir, { recursive: true, force: true });
  console.log(`✓ ${target.agent.padEnd(6)} removed ${target.dir}`);
}

function writeConfig(url: string, key: string) {
  mkdirSync(dirname(CONFIG_PATH), { recursive: true });
  writeFileSync(CONFIG_PATH, JSON.stringify({ url: url.replace(/\/+$/, ""), apiKey: key }, null, 2) + "\n", {
    mode: 0o600,
  });
  chmodSync(CONFIG_PATH, 0o600);
  console.log(`✓ config  ${CONFIG_PATH}`);
}

function hasConfig(): boolean {
  if (process.env.LOOPBACK_URL && process.env.LOOPBACK_API_KEY) return true;
  try {
    const c = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
    return Boolean(c.url && c.apiKey);
  } catch {
    return false;
  }
}

const USAGE = `Loopback CLI

  loopback install [--claude] [--codex] [--project] [--url <url> --key <api-key>]
      Install the "loopback" skill (SKILL.md + scripts/loopback.mjs).
      Default: both agents, user-level (~/.claude/skills, ~/.codex/skills).
      --project installs into ./.claude/skills and ./.codex/skills instead.
      --url/--key also write ~/.config/loopback/config.json.

  loopback uninstall [--claude] [--codex] [--project]
  loopback config --url <url> --key <api-key>

  loopback ask "<title>" [--context ..] [--option "Label :: desc"]... [--multi] [--no-text]
                         [--source ..] [--timeout <s>] [--no-wait]
  loopback wait <id> [--timeout <s>]
  loopback status <id>
`;

async function run(argv: string[]): Promise<number> {
  const command = argv[0];
  if (!command || command === "--help" || command === "-h" || command === "help") {
    console.log(USAGE);
    return 0;
  }

  if (command === "ask" || command === "wait" || command === "status") {
    return scriptMain(argv);
  }

  if (command === "install" || command === "uninstall") {
    const { values } = parseArgs({
      args: argv.slice(1),
      options: {
        claude: { type: "boolean", default: false },
        codex: { type: "boolean", default: false },
        project: { type: "boolean", default: false },
        url: { type: "string" },
        key: { type: "string" },
      },
    });
    const both = !values.claude && !values.codex;
    const list = targets({ claude: both || values.claude, codex: both || values.codex, project: values.project });

    if (command === "uninstall") {
      list.forEach(uninstallSkill);
      return 0;
    }

    if ((values.url && !values.key) || (!values.url && values.key)) {
      throw new Error("--url and --key go together");
    }
    if (values.url && values.key) writeConfig(values.url, values.key);

    list.forEach(installSkill);
    if (!hasConfig()) {
      console.log(
        `\n! No server config found. Run: loopback config --url https://your-server --key <api-key>\n  (or export LOOPBACK_URL and LOOPBACK_API_KEY).`,
      );
    }
    console.log(`\nAgents will pick the skill up on their next session. Try: loopback ask "Ping?" --option Pong`);
    return 0;
  }

  if (command === "config") {
    const { values } = parseArgs({
      args: argv.slice(1),
      options: { url: { type: "string" }, key: { type: "string" } },
    });
    if (!values.url || !values.key) throw new Error("config needs --url and --key");
    writeConfig(values.url, values.key);
    return 0;
  }

  throw new Error(`Unknown command "${command}"\n\n${USAGE}`);
}

run(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (err: Error) => {
    console.error(`Error: ${err.message}`);
    process.exit(1);
  },
);
