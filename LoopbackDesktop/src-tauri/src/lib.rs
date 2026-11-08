mod api;
mod config;
mod events;
mod models;
mod notify;

use std::sync::Mutex;

use serde::Serialize;
use tauri::async_runtime::JoinHandle;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, WindowEvent};

use api::Api;
use config::Settings;
use models::LoopbackRequest;
use notify::Notifier;

/// Start with the window hidden (e.g. from an autostart entry); the tray icon brings it up.
const HIDDEN_ARG: &str = "--hidden";

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum ConnectionStatus {
    /// No server URL / key yet.
    Unconfigured,
    Connecting,
    Connected,
    /// Disconnected; retrying with backoff.
    Error {
        message: String,
    },
}

pub struct AppState {
    settings: Mutex<Option<Settings>>,
    stream: Mutex<Option<JoinHandle<()>>>,
    status: Mutex<ConnectionStatus>,
    notifier: Notifier,
}

impl AppState {
    fn api(&self) -> Result<Api, String> {
        self.settings
            .lock()
            .unwrap()
            .as_ref()
            .map(Api::new)
            .ok_or_else(|| "Set up the server in Settings first".into())
    }
}

/// (Re)starts the event stream with the current settings, or stops it when there are none.
fn restart_stream(app: &AppHandle) {
    let state = app.state::<AppState>();
    if let Some(old) = state.stream.lock().unwrap().take() {
        old.abort();
    }
    match state.api() {
        Ok(api) => {
            let handle = tauri::async_runtime::spawn(events::run(app.clone(), api));
            *state.stream.lock().unwrap() = Some(handle);
        }
        Err(_) => events::set_status(app, ConnectionStatus::Unconfigured),
    }
}

pub fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// Brings the window up on a request (clicked notification).
pub fn open_request(app: &AppHandle, id: &str) {
    show_main(app);
    let _ = app.emit("open-request", id);
}

/// Answers straight from a notification button. On failure, opens the request so it can be retried.
pub fn quick_answer(app: &AppHandle, id: String, label: String) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let result = match app.state::<AppState>().api() {
            Ok(api) => api.answer(&id, &[label], None).await.map(|_| ()),
            Err(e) => Err(e),
        };
        if let Err(e) = result {
            open_request(&app, &id);
            let _ = app.emit("toast", format!("Couldn't send the answer: {e}"));
        }
    });
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

#[tauri::command]
fn get_settings(state: tauri::State<AppState>) -> Option<Settings> {
    state.settings.lock().unwrap().clone()
}

/// Validates the URL and key against the server, then saves them and reconnects.
#[tauri::command]
async fn save_settings(app: AppHandle, url: String, app_key: String) -> Result<(), String> {
    let settings = Settings::normalized(&url, &app_key)?;
    Api::new(&settings).list("pending", 1).await?;
    config::save(&app, &settings)?;
    *app.state::<AppState>().settings.lock().unwrap() = Some(settings);
    restart_stream(&app);
    Ok(())
}

#[tauri::command]
fn connection_status(state: tauri::State<AppState>) -> ConnectionStatus {
    state.status.lock().unwrap().clone()
}

#[tauri::command]
fn reconnect(app: AppHandle) {
    restart_stream(&app);
}

#[tauri::command]
async fn list_requests(
    state: tauri::State<'_, AppState>,
    status: String,
    limit: Option<u32>,
) -> Result<Vec<LoopbackRequest>, String> {
    state.api()?.list(&status, limit.unwrap_or(100)).await
}

#[tauri::command]
async fn get_request(
    state: tauri::State<'_, AppState>,
    id: String,
) -> Result<LoopbackRequest, String> {
    state.api()?.request(&id).await
}

#[tauri::command]
async fn answer_request(
    state: tauri::State<'_, AppState>,
    id: String,
    selected: Vec<String>,
    text: Option<String>,
) -> Result<LoopbackRequest, String> {
    let text = text.as_deref().map(str::trim).filter(|t| !t.is_empty());
    let answered = state.api()?.answer(&id, &selected, text).await?;
    state.notifier.close(&id);
    Ok(answered)
}

#[tauri::command]
async fn mark_read(
    state: tauri::State<'_, AppState>,
    id: String,
) -> Result<LoopbackRequest, String> {
    let read = state.api()?.mark_read(&id).await?;
    state.notifier.close(&id);
    Ok(read)
}

/// The webview reports how many items need attention; shown on the tray tooltip and window badge.
#[tauri::command]
fn set_pending_count(app: AppHandle, count: u32) {
    let tooltip = match count {
        0 => "Loopback".to_string(),
        1 => "Loopback · 1 waiting".to_string(),
        n => format!("Loopback · {n} waiting"),
    };
    if let Some(tray) = app.tray_by_id("main") {
        let _ = tray.set_tooltip(Some(&tooltip));
    }
    if let Some(window) = app.get_webview_window("main") {
        // Not supported everywhere (e.g. Windows, many Linux docks); best effort.
        let _ = window.set_badge_count((count > 0).then_some(count as i64));
    }
}

#[tauri::command]
fn quit(app: AppHandle) {
    app.exit(0);
}

// ---------------------------------------------------------------------------
// App
// ---------------------------------------------------------------------------

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open Loopback", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main")
        .tooltip("Loopback")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        // Launching again just brings the running instance forward.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app)
        }))
        .plugin(tauri_plugin_opener::init());
    #[cfg(not(target_os = "linux"))]
    let builder = builder.plugin(tauri_plugin_notification::init());

    builder
        .setup(|app| {
            let handle = app.handle();
            app.manage(AppState {
                settings: Mutex::new(config::load(handle)),
                stream: Mutex::new(None),
                status: Mutex::new(ConnectionStatus::Unconfigured),
                notifier: Notifier::new(handle),
            });
            if let Err(e) = build_tray(handle) {
                // No tray (e.g. a Linux desktop without AppIndicator): closing the window would strand
                // the app, so the window close handler quits instead.
                eprintln!("Tray icon unavailable: {e}");
            }
            restart_stream(handle);
            if !std::env::args().any(|a| a == HIDDEN_ARG) {
                show_main(handle);
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window hides it to the tray so the event stream (and notifications) keep going.
            if let WindowEvent::CloseRequested { api, .. } = event
                && window.app_handle().tray_by_id("main").is_some()
            {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            connection_status,
            reconnect,
            list_requests,
            get_request,
            answer_request,
            mark_read,
            set_pending_count,
            quit,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
