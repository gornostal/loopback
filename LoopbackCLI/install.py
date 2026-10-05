#!/usr/bin/env python3
"""Install the Loopback skills into Claude Code and/or Codex.

Interactive: asks for the server URL, the agent key, which skills (loopback = ask a question,
loopback-notify = one-way notification), which agents, and the scope (global or this project).
Writes ~/.config/loopback/config.json and copies each chosen skill's SKILL.md plus the shared
loopback.py into <base>/.claude/skills/<skill> and/or <base>/.codex/skills/<skill>.

Plain Python >= 3.8, standard library only.
"""

import getpass
import json
import os
import shutil
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SKILL_SRC = os.path.join(HERE, "skill")
SCRIPT = "loopback.py"
CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".config", "loopback", "config.json")

# (skill dir name under skill/ and under <base>/<agent>/skills/, what it's for, example command)
SKILLS = [
    ("loopback", "ask the user a question and wait for the answer", 'ask "Ping?" --option Pong'),
    ("loopback-notify", "send a one-way notification, no answer expected", 'notify "Ping"'),
]
AGENTS = [("Claude", ".claude"), ("Codex", ".codex")]
SCOPES = [("Global", os.path.expanduser("~")), ("This project", os.getcwd())]


def read_config():
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def ask_text(prompt, default="", secret=False):
    suffix = " [%s]" % ("keep existing" if secret else default) if default else ""
    while True:
        read = getpass.getpass if secret else input
        value = read("%s%s: " % (prompt, suffix)).strip() or default
        if value:
            return value
        print("  required")


def ask_choice(prompt, choices, multi, default):
    """Numbered menu. `default` is a list of 1-based indexes."""
    print(prompt)
    for i, label in enumerate(choices, 1):
        print("  %d) %s" % (i, label))
    hint = "numbers, comma-separated" if multi else "number"
    while True:
        raw = input("Choose (%s) [%s]: " % (hint, ",".join(map(str, default)))).strip()
        picked = default if not raw else []
        try:
            for part in raw.replace(" ", ",").split(","):
                if part:
                    n = int(part)
                    if not 1 <= n <= len(choices):
                        raise ValueError
                    if n not in picked:
                        picked.append(n)
        except ValueError:
            picked = []
        if picked and (multi or len(picked) == 1):
            return [n - 1 for n in picked]
        print("  pick %s between 1 and %d" % ("one or more" if multi else "one", len(choices)))


def normalize_url(url):
    """'loopback.example.com/' -> 'https://loopback.example.com'."""
    url = url.strip().rstrip("/")
    if "://" not in url:
        url = "https://" + url
    return url


def check_server(url, agent_key):
    """Warn (don't fail) if the server is unreachable or rejects the key."""
    probe = urllib.request.Request(
        url + "/api/agent/requests/install-check", headers={"Authorization": "Bearer " + agent_key}
    )
    try:
        urllib.request.urlopen(probe, timeout=10)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("✓ server  %s accepts the agent key" % url)
        elif e.code == 401:
            print("! server  %s rejected the agent key (401)" % url)
        else:
            print("! server  %s answered %d" % (url, e.code))
        return
    except (urllib.error.URLError, OSError, ValueError) as e:
        print("! server  can't reach %s: %s" % (url, getattr(e, "reason", e)))
        return
    print("✓ server  %s" % url)


def write_config(url, agent_key):
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    fd = os.open(CONFIG_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"url": url, "agentKey": agent_key}, f, indent=2)
        f.write("\n")
    os.chmod(CONFIG_PATH, 0o600)
    print("✓ config  %s" % CONFIG_PATH)


def install_skill(agent, skill, skill_dir):
    """Copies skill/<skill>/SKILL.md (with {{SKILL_DIR}} filled in) and the shared script into skill_dir."""
    os.makedirs(skill_dir, exist_ok=True)
    with open(os.path.join(SKILL_SRC, skill, "SKILL.md"), encoding="utf-8") as f:
        skill_md = f.read().replace("{{SKILL_DIR}}", skill_dir)
    with open(os.path.join(skill_dir, "SKILL.md"), "w", encoding="utf-8") as f:
        f.write(skill_md)
    script = os.path.join(skill_dir, SCRIPT)
    shutil.copyfile(os.path.join(SKILL_SRC, SCRIPT), script)
    os.chmod(script, 0o755)
    print("✓ %-7s %s" % (agent, skill_dir))


def main():
    existing = read_config()
    print("Loopback skills installer\n")
    url = normalize_url(ask_text("Loopback server base URL", existing.get("url", "")))
    agent_key = ask_text("Agent key (LOOPBACK_AGENT_KEY)", existing.get("agentKey", ""), secret=True)
    print()
    skills = ask_choice("Install which skills?", ["%s — %s" % (n, what) for n, what, _ in SKILLS], multi=True, default=[1, 2])
    print()
    agents = ask_choice("Install for which agents?", [a for a, _ in AGENTS], multi=True, default=[1, 2])
    print()
    (scope,) = ask_choice("Scope?", ["%s (%s)" % s for s in SCOPES], multi=False, default=[1])
    print()

    check_server(url, agent_key)
    write_config(url, agent_key)
    base = SCOPES[scope][1]
    for i in agents:
        name, dot_dir = AGENTS[i]
        for j in skills:
            skill = SKILLS[j][0]
            install_skill(name, skill, os.path.join(base, dot_dir, "skills", skill))
    first_skill, _, example = SKILLS[skills[0]]
    print("\nAgents pick the skills up on their next session. Try:\n  python3 %s %s"
          % (os.path.join(base, AGENTS[agents[0]][1], "skills", first_skill, SCRIPT), example))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyboardInterrupt, EOFError):
        print("\naborted")
        sys.exit(1)
