//! Server URL + app key, stored as JSON in the app's config dir (mode 600 on Unix).

use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub url: String,
    pub app_key: String,
}

impl Settings {
    /// Trims whitespace and trailing slashes; rejects anything that isn't http(s).
    pub fn normalized(url: &str, app_key: &str) -> Result<Settings, String> {
        let url = url.trim().trim_end_matches('/').to_string();
        let app_key = app_key.trim().to_string();
        if !(url.starts_with("http://") || url.starts_with("https://")) {
            return Err("Server URL must start with http:// or https://".into());
        }
        if app_key.is_empty() {
            return Err("App key is required".into());
        }
        Ok(Settings { url, app_key })
    }
}

fn path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    Ok(dir.join("config.json"))
}

pub fn load(app: &AppHandle) -> Option<Settings> {
    let text = fs::read_to_string(path(app).ok()?).ok()?;
    let settings: Settings = serde_json::from_str(&text).ok()?;
    (!settings.url.is_empty() && !settings.app_key.is_empty()).then_some(settings)
}

pub fn save(app: &AppHandle, settings: &Settings) -> Result<(), String> {
    let path = path(app)?;
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| format!("Can't create {}: {e}", dir.display()))?;
    }
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    write_private(&path, json.as_bytes())
        .map_err(|e| format!("Can't write {}: {e}", path.display()))
}

#[cfg(unix)]
fn write_private(path: &PathBuf, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    // `mode` only applies on create; tighten a file left over from an older version too.
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(bytes)
}

#[cfg(not(unix))]
fn write_private(path: &PathBuf, bytes: &[u8]) -> std::io::Result<()> {
    fs::write(path, bytes)
}
