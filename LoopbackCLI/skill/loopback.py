#!/usr/bin/env python3
"""Loopback: ask a human a question over HTTP and wait for (or poll for) the answer, or just notify them.

Plain Python >= 3.8, standard library only. Copied verbatim into agents' skill dirs by install.py
(both the `loopback` and the `loopback-notify` skill ship this same file).

Config: LOOPBACK_URL + LOOPBACK_AGENT_KEY env vars, else ~/.config/loopback/config.json {"url","agentKey"}.
Uses the server's agent API: it can create requests and follow them by id, not list or answer them.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".config", "loopback", "config.json")

# Longest single HTTP long-poll; the server caps at 600 s and we stay under proxies' idle limits.
POLL_CHUNK_SECONDS = 120

EXIT_ANSWERED, EXIT_ERROR, EXIT_CANCELLED, EXIT_PENDING = 0, 1, 2, 3

EPILOG = """\
examples:
  loopback.py ask "Deploy api v2.3?" --context "CI green. 1 migration." \\
      --option "Deploy now :: run the migration and roll out" --option "Hold" \\
      --source my-project --timeout 300
  loopback.py ask "Merge PR #42?" --option Merge --option "Request changes" --no-wait
  loopback.py notify "Build finished" --context "All 42 tests passed." --source my-project
  loopback.py wait <id> --timeout 300
  loopback.py status <id>
  loopback.py cancel <id>

Prints JSON {id, status, title, answer?} on stdout.
Exit codes: 0 answered (or created with --no-wait, or notified) · 2 cancelled ·
            3 still pending (re-run "wait <id>") · 1 error or bad usage

Config: LOOPBACK_URL + LOOPBACK_AGENT_KEY, or %s {"url", "agentKey"}.
""" % CONFIG_PATH


class LoopbackError(Exception):
    pass


def load_config():
    file_config = {}
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            file_config = json.load(f)
    except (OSError, ValueError):
        pass  # no config file; env vars may still be set
    url = (os.environ.get("LOOPBACK_URL") or file_config.get("url") or "").rstrip("/")
    agent_key = os.environ.get("LOOPBACK_AGENT_KEY") or file_config.get("agentKey") or ""
    if not url or not agent_key:
        raise LoopbackError(
            "Loopback is not configured. Set LOOPBACK_URL and LOOPBACK_AGENT_KEY, or create %s "
            'with {"url": "...", "agentKey": "..."}.' % CONFIG_PATH
        )
    if "://" not in url:
        url = "https://" + url
    return {"url": url, "agentKey": agent_key}


def call(config, method, path, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        config["url"] + path,
        data=data,
        method=method,
        headers={"Authorization": "Bearer " + config["agentKey"], "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            message = json.loads(text).get("error") or text
        except ValueError:
            message = text
        raise LoopbackError("%s %s -> %d: %s" % (method, path, e.code, message))
    except (urllib.error.URLError, OSError) as e:
        raise LoopbackError("%s %s failed: %s" % (method, path, getattr(e, "reason", e)))


def request_path(request_id, suffix=""):
    return "/api/agent/requests/" + urllib.parse.quote(request_id, safe="") + suffix


def wait_for_answer(config, request_id, timeout_seconds):
    """Long-polls until the request is answered/cancelled or timeout_seconds elapse."""
    deadline = time.monotonic() + timeout_seconds
    request = call(config, "GET", request_path(request_id))
    while request["status"] == "pending":
        remaining = int(deadline - time.monotonic() + 0.999)
        if remaining <= 0:
            break
        chunk = min(POLL_CHUNK_SECONDS, remaining)
        request = call(config, "GET", request_path(request_id, "/wait?timeout=%d" % chunk), timeout=chunk + 30)
    return request


def parse_option(raw):
    """'Label :: description' -> {label, description}."""
    label, _, description = raw.partition("::")
    option = {"label": label.strip()}
    if description.strip():
        option["description"] = description.strip()
    return option


def summarize(request):
    """What the agent needs to see, nothing else."""
    out = {"id": request["id"], "status": request["status"], "title": request["title"]}
    answer = request.get("answer")
    if answer:
        out["answer"] = {"selected": answer.get("selected", []), "text": answer.get("text")}
    return out


def exit_code_for(request):
    codes = {"answered": EXIT_ANSWERED, "notified": EXIT_ANSWERED, "cancelled": EXIT_CANCELLED}
    return codes.get(request["status"], EXIT_PENDING)


def emit(request):
    print(json.dumps(summarize(request), indent=2, ensure_ascii=False))
    return exit_code_for(request)


def cmd_ask(config, args):
    body = {
        "title": args.title,
        "context": args.context,
        "options": [parse_option(o) for o in args.option],
        "multiSelect": args.multi,
        "allowFreeText": not args.no_text,
        "source": args.source,
    }
    created = call(config, "POST", "/api/agent/requests", {k: v for k, v in body.items() if v is not None})
    if args.no_wait:
        emit(created)
        return EXIT_ANSWERED
    print('Loopback: asked "%s" (id %s); waiting up to %ds...' % (args.title, created["id"], args.timeout), file=sys.stderr)
    return emit(wait_for_answer(config, created["id"], args.timeout))


def cmd_notify(config, args):
    """One-way: push a message to the phone and return at once. Nothing to wait for."""
    body = {"title": args.title, "context": args.context, "source": args.source}
    return emit(call(config, "POST", "/api/agent/notifications", {k: v for k, v in body.items() if v is not None}))


def cmd_wait(config, args):
    return emit(wait_for_answer(config, args.id, args.timeout))


def cmd_status(config, args):
    return emit(call(config, "GET", request_path(args.id)))


def cmd_cancel(config, args):
    return emit(call(config, "DELETE", request_path(args.id)))


class Parser(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(EXIT_ERROR, "%s: error: %s\n" % (self.prog, message))


def build_parser():
    parser = Parser(
        prog="loopback.py",
        description="Ask the human a question, or send them a note, through a push notification to their phone (Loopback).",
        epilog=EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="command", metavar="<command>", parser_class=Parser)

    ask = sub.add_parser("ask", help="create a request and (by default) wait for the answer")
    ask.add_argument("title", help="one short question; the notification headline")
    ask.add_argument("--context", help="markdown with the facts needed to decide")
    ask.add_argument("--option", action="append", default=[], metavar='"LABEL :: DESCRIPTION"',
                     help="a choice; repeat for each. Description is optional")
    ask.add_argument("--multi", action="store_true", help="let the user pick several options")
    ask.add_argument("--no-text", action="store_true", help='hide the free-text "my answer" field')
    ask.add_argument("--source", default="agent", help="who is asking, ideally the project name; shown in the inbox (default: agent)")
    ask.add_argument("--timeout", type=int, default=300, metavar="SECONDS", help="how long to block (default: 300)")
    ask.add_argument("--no-wait", action="store_true", help="print the new request's id and return immediately")
    ask.set_defaults(func=cmd_ask)

    notify = sub.add_parser("notify", help="send a one-way notification; no answer expected, returns immediately")
    notify.add_argument("title", help="one short headline; the notification title")
    notify.add_argument("--context", help="markdown with the message itself")
    notify.add_argument("--source", default="agent", help="who is notifying, ideally the project name; shown in the inbox (default: agent)")
    notify.set_defaults(func=cmd_notify)

    wait = sub.add_parser("wait", help="block until a request is answered or cancelled")
    wait.add_argument("id")
    wait.add_argument("--timeout", type=int, default=300, metavar="SECONDS", help="how long to block (default: 300)")
    wait.set_defaults(func=cmd_wait)

    status = sub.add_parser("status", help="current state of a request, instantly")
    status.add_argument("id")
    status.set_defaults(func=cmd_status)

    cancel = sub.add_parser("cancel", help="withdraw a pending request (dismisses the notification)")
    cancel.add_argument("id")
    cancel.set_defaults(func=cmd_cancel)

    return parser


def main(argv):
    parser = build_parser()
    if not argv:
        parser.print_help(sys.stderr)
        return EXIT_ERROR
    args = parser.parse_args(argv)
    if not args.command:
        parser.print_help(sys.stderr)
        return EXIT_ERROR
    try:
        return args.func(load_config(), args)
    except LoopbackError as e:
        print("Loopback error: %s" % e, file=sys.stderr)
        return EXIT_ERROR
    except KeyboardInterrupt:
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
