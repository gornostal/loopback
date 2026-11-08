//! Keeps one SSE connection to `GET /api/app/events` open, reconnecting with backoff. Every event is
//! forwarded to the webview; new questions and notifications also become desktop notifications.

use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

use crate::api::Api;
use crate::models::LoopbackRequest;
use crate::{AppState, ConnectionStatus};

/// The server pings every 20 s; this much silence means the connection is dead.
const STALL_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_BACKOFF: Duration = Duration::from_secs(30);

/// Payload of the `request-event` event the webview listens to.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestEvent {
    /// `request` | `notification` | `answered` | `cancelled` | `read`
    #[serde(rename = "type")]
    pub kind: String,
    pub request: LoopbackRequest,
}

pub async fn run(app: AppHandle, api: Api) {
    let mut backoff = Duration::from_secs(1);
    loop {
        set_status(&app, ConnectionStatus::Connecting);
        let result = match api.open_events().await {
            Ok(res) => read_stream(&app, res, &mut backoff).await,
            Err(e) => Err(e),
        };
        let message = match result {
            Ok(()) => "Server closed the connection".to_string(),
            Err(e) => e,
        };
        set_status(&app, ConnectionStatus::Error { message });
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

async fn read_stream(
    app: &AppHandle,
    mut res: reqwest::Response,
    backoff: &mut Duration,
) -> Result<(), String> {
    let mut buf: Vec<u8> = Vec::new();
    let mut parser = SseParser::default();
    loop {
        let chunk = tokio::time::timeout(STALL_TIMEOUT, res.chunk())
            .await
            .map_err(|_| "No data from the server for a minute".to_string())?
            .map_err(|e| format!("Connection lost: {e}"))?;
        let Some(chunk) = chunk else { return Ok(()) };
        buf.extend_from_slice(&chunk);
        while let Some(pos) = buf.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = buf.drain(..=pos).collect();
            let line = String::from_utf8_lossy(&line);
            if let Some((event, data)) = parser.feed(line.trim_end_matches(['\n', '\r'])) {
                if event == "hello" {
                    *backoff = Duration::from_secs(1);
                }
                handle(app, &event, &data);
            }
        }
    }
}

fn handle(app: &AppHandle, event: &str, data: &str) {
    if event == "hello" {
        set_status(app, ConnectionStatus::Connected);
        // Whatever happened while disconnected: the webview refetches its lists.
        let _ = app.emit("resync", ());
        return;
    }
    let request: LoopbackRequest = match serde_json::from_str(data) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("Ignoring `{event}` event with unexpected data: {e}");
            return;
        }
    };
    let state = app.state::<AppState>();
    match event {
        "request" | "notification" => {
            if !window_in_focus(app) {
                state.notifier.show(app, &request);
            }
        }
        "answered" | "cancelled" | "read" => state.notifier.close(&request.id),
        _ => {}
    }
    let _ = app.emit(
        "request-event",
        RequestEvent {
            kind: event.to_string(),
            request,
        },
    );
}

/// Skip the desktop notification when the user is already looking at the inbox; it updates live.
fn window_in_focus(app: &AppHandle) -> bool {
    app.get_webview_window("main")
        .map(|w| w.is_visible().unwrap_or(false) && w.is_focused().unwrap_or(false))
        .unwrap_or(false)
}

pub fn set_status(app: &AppHandle, status: ConnectionStatus) {
    *app.state::<AppState>().status.lock().unwrap() = status.clone();
    let _ = app.emit("connection-status", status);
}

/// Minimal `text/event-stream` parser: `event:` and `data:` fields, blank line dispatches, `:` comments.
#[derive(Default)]
struct SseParser {
    event: String,
    data: Vec<String>,
}

impl SseParser {
    fn feed(&mut self, line: &str) -> Option<(String, String)> {
        if line.is_empty() {
            let event = std::mem::take(&mut self.event);
            let data = std::mem::take(&mut self.data);
            if data.is_empty() {
                return None;
            }
            let event = if event.is_empty() {
                "message".into()
            } else {
                event
            };
            return Some((event, data.join("\n")));
        }
        if line.starts_with(':') {
            return None;
        }
        let (field, value) = line.split_once(':').unwrap_or((line, ""));
        let value = value.strip_prefix(' ').unwrap_or(value);
        match field {
            "event" => self.event = value.to_string(),
            "data" => self.data.push(value.to_string()),
            _ => {}
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::SseParser;

    #[test]
    fn parses_events_and_skips_comments() {
        let mut p = SseParser::default();
        let lines = [
            "retry: 3000",
            "event: hello",
            "data: {}",
            "",
            ": ping",
            "",
            "event: request",
            "data: {\"a\":",
            "data: 1}",
            "",
        ];
        let events: Vec<_> = lines.iter().filter_map(|l| p.feed(l)).collect();
        assert_eq!(
            events,
            vec![
                ("hello".into(), "{}".into()),
                ("request".into(), "{\"a\":\n1}".into())
            ]
        );
    }
}
