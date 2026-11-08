//! Desktop notifications for new questions and one-way notifications.
//!
//! On Linux we talk to the freedesktop notification daemon (notify-rust) so we can:
//! - open the request when the notification is clicked,
//! - offer quick-answer buttons for single-select questions with a few options,
//! - close the notification once the request is answered, cancelled or read elsewhere.
//!
//! Other platforms use the Tauri notification plugin: a plain notification, no actions.

use crate::models::LoopbackRequest;

/// Title and body for a request, mirroring the Android notifications: a question shows the first
/// line of its context, a notification its whole context collapsed onto one line.
fn content(req: &LoopbackRequest) -> (String, String) {
    let context = req.context.as_deref().unwrap_or("");
    let body = if req.is_notification() {
        let text = context
            .lines()
            .map(strip_markdown)
            .filter(|l| !l.is_empty())
            .collect::<Vec<_>>()
            .join(" ");
        if text.is_empty() {
            "Click to read".to_string()
        } else {
            truncate(&text, 400)
        }
    } else {
        let first = context
            .lines()
            .map(strip_markdown)
            .find(|l| !l.is_empty())
            .unwrap_or_default();
        if first.is_empty() {
            "Click to answer".to_string()
        } else {
            truncate(&first, 200)
        }
    };
    let title = match req
        .source
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(source) => format!("{} · {source}", req.title),
        None => req.title.clone(),
    };
    (title, body)
}

/// Flattens one line of markdown: drops heading/list/quote markers and emphasis, keeps link text.
fn strip_markdown(line: &str) -> String {
    let mut s = line.trim();
    for prefix in [
        "###### ", "##### ", "#### ", "### ", "## ", "# ", "> ", "- ", "* ", "+ ",
    ] {
        if let Some(rest) = s.strip_prefix(prefix) {
            s = rest.trim_start();
            break;
        }
    }
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            // Not `_`: it shows up in identifiers far more often than as emphasis.
            '*' | '`' | '~' => {}
            '[' => {
                // [text](url) → text
                let text: String = chars.by_ref().take_while(|&c| c != ']').collect();
                out.push_str(&text);
                if chars.peek() == Some(&'(') {
                    for c in chars.by_ref() {
                        if c == ')' {
                            break;
                        }
                    }
                }
            }
            _ => out.push(c),
        }
    }
    out.trim().to_string()
}

fn truncate(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    let mut s: String = text.chars().take(max - 1).collect();
    s.push('…');
    s
}

#[cfg(target_os = "linux")]
pub use linux::Notifier;

#[cfg(not(target_os = "linux"))]
pub use plugin::Notifier;

#[cfg(target_os = "linux")]
mod linux {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    use notify_rust::{ActionResponse, Hint, Notification, NotificationHandle, Timeout};
    use tauri::{AppHandle, Manager};

    use super::content;
    use crate::models::LoopbackRequest;

    /// Basename of the installed .desktop file (the binary name, as Tauri's bundles name it too).
    const DESKTOP_ENTRY: &str = "loopback-desktop";

    /// Quick-answer buttons only for single-select questions with at most this many options.
    const MAX_QUICK_OPTIONS: usize = 3;

    pub struct Notifier {
        /// Request id → live notification, so it can be closed when the request resolves elsewhere.
        handles: Arc<Mutex<HashMap<String, NotificationHandle>>>,
        icon: Option<String>,
    }

    impl Notifier {
        pub fn new(app: &AppHandle) -> Notifier {
            Notifier {
                handles: Default::default(),
                icon: write_icon(app),
            }
        }

        pub fn show(&self, app: &AppHandle, req: &LoopbackRequest) {
            let (title, body) = content(req);
            let is_question = !req.is_notification();
            let quick: Vec<String> =
                if is_question && !req.multi_select && req.options.len() <= MAX_QUICK_OPTIONS {
                    req.options.iter().map(|o| o.label.clone()).collect()
                } else {
                    Vec::new()
                };
            let id = req.id.clone();
            let icon = self.icon.clone();
            let handles = self.handles.clone();
            let app = app.clone();

            // notify-rust blocks (D-Bus round trips, then waiting for a click), so each notification
            // gets its own thread; it ends when the notification is clicked or closed.
            std::thread::spawn(move || {
                let mut n = Notification::new();
                n.appname("Loopback").summary(&title).body(&body);
                // Matches loopback-desktop.desktop, so the daemon shows our icon and per-app settings.
                n.hint(Hint::DesktopEntry(DESKTOP_ENTRY.into()));
                if let Some(icon) = &icon {
                    n.icon(icon);
                }
                n.action("default", if is_question { "Answer" } else { "Open" });
                for (i, label) in quick.iter().enumerate() {
                    n.action(&format!("option:{i}"), label);
                }
                if is_question {
                    // Questions wait for the human; keep them on screen where the daemon allows it.
                    n.timeout(Timeout::Never);
                }
                let handle = match n.show() {
                    Ok(h) => h,
                    Err(e) => {
                        eprintln!("Can't show notification: {e}");
                        return;
                    }
                };
                let nid = handle.id();
                // Keeping the handle also keeps its D-Bus connection, which some daemons need for actions.
                handles.lock().unwrap().insert(id.clone(), handle);
                let _ = notify_rust::handle_action(nid, |response| {
                    let ActionResponse::Custom(action) = response else {
                        return;
                    };
                    match action
                        .strip_prefix("option:")
                        .and_then(|i| i.parse::<usize>().ok())
                    {
                        Some(i) if i < quick.len() => {
                            crate::quick_answer(&app, id.clone(), quick[i].clone())
                        }
                        _ => crate::open_request(&app, &id),
                    }
                });
                handles.lock().unwrap().remove(&id);
            });
        }

        pub fn close(&self, request_id: &str) {
            let handle = self.handles.lock().unwrap().remove(request_id);
            if let Some(handle) = handle {
                std::thread::spawn(move || handle.close());
            }
        }
    }

    /// The daemon wants an icon path or theme name; the bundled PNG isn't on disk in dev builds,
    /// so write it to the cache dir once.
    fn write_icon(app: &AppHandle) -> Option<String> {
        let dir = app.path().app_cache_dir().ok()?;
        std::fs::create_dir_all(&dir).ok()?;
        let path = dir.join("notification-icon.png");
        std::fs::write(&path, include_bytes!("../icons/128x128.png")).ok()?;
        Some(path.to_string_lossy().into_owned())
    }
}

#[cfg(not(target_os = "linux"))]
mod plugin {
    use tauri::AppHandle;
    use tauri_plugin_notification::NotificationExt;

    use super::content;
    use crate::models::LoopbackRequest;

    pub struct Notifier;

    impl Notifier {
        pub fn new(_app: &AppHandle) -> Notifier {
            Notifier
        }

        pub fn show(&self, app: &AppHandle, req: &LoopbackRequest) {
            let (title, body) = content(req);
            if let Err(e) = app.notification().builder().title(title).body(body).show() {
                eprintln!("Can't show notification: {e}");
            }
        }

        /// The plugin can't withdraw a desktop notification; the inbox updates on its own.
        pub fn close(&self, _request_id: &str) {}
    }
}

#[cfg(test)]
mod tests {
    use super::strip_markdown;

    #[test]
    fn strips_inline_markdown() {
        assert_eq!(
            strip_markdown("## Tests **passed** in `ci`"),
            "Tests passed in ci"
        );
        assert_eq!(
            strip_markdown("- see [the PR](https://x/1) now"),
            "see the PR now"
        );
    }
}
