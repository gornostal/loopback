//! `/api/app/*` client. All HTTP lives on the Rust side so the webview needs no CORS and the event
//! stream keeps running while the window is hidden.

use std::time::Duration;

use reqwest::{Client, RequestBuilder, Response, StatusCode};
use serde::de::DeserializeOwned;
use serde_json::json;

use crate::config::Settings;
use crate::models::{LoopbackRequest, RequestList};

#[derive(Clone)]
pub struct Api {
    http: Client,
    base: String,
    key: String,
}

impl Api {
    pub fn new(settings: &Settings) -> Api {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .user_agent(concat!("loopback-desktop/", env!("CARGO_PKG_VERSION")))
            .build()
            .expect("HTTP client");
        Api {
            http,
            base: settings.url.clone(),
            key: settings.app_key.clone(),
        }
    }

    fn url(&self, path: &str) -> String {
        format!("{}/api/app{path}", self.base)
    }

    fn get(&self, path: &str) -> RequestBuilder {
        self.http.get(self.url(path)).bearer_auth(&self.key)
    }

    fn post(&self, path: &str) -> RequestBuilder {
        self.http.post(self.url(path)).bearer_auth(&self.key)
    }

    pub async fn list(&self, status: &str, limit: u32) -> Result<Vec<LoopbackRequest>, String> {
        let req = self
            .get("/requests")
            .query(&[("status", status), ("limit", &limit.to_string())]);
        Ok(send::<RequestList>(req).await?.requests)
    }

    pub async fn request(&self, id: &str) -> Result<LoopbackRequest, String> {
        send(self.get(&format!("/requests/{}", encode(id)))).await
    }

    pub async fn answer(
        &self,
        id: &str,
        selected: &[String],
        text: Option<&str>,
    ) -> Result<LoopbackRequest, String> {
        let body = json!({ "selected": selected, "text": text });
        send(
            self.post(&format!("/requests/{}/answer", encode(id)))
                .json(&body),
        )
        .await
    }

    pub async fn mark_read(&self, id: &str) -> Result<LoopbackRequest, String> {
        send(self.post(&format!("/requests/{}/read", encode(id)))).await
    }

    /// Opens the SSE stream. No overall timeout: the caller watches for pings instead.
    pub async fn open_events(&self) -> Result<Response, String> {
        let res = self
            .get("/events")
            .header("Accept", "text/event-stream")
            .send()
            .await
            .map_err(describe)?;
        check(res).await
    }
}

async fn send<T: DeserializeOwned>(req: RequestBuilder) -> Result<T, String> {
    let res = req
        .timeout(Duration::from_secs(30))
        .send()
        .await
        .map_err(describe)?;
    let res = check(res).await?;
    res.json::<T>()
        .await
        .map_err(|e| format!("Unexpected response from server: {e}"))
}

/// Turns a non-2xx response into the server's `{ "error": … }` message.
async fn check(res: Response) -> Result<Response, String> {
    let status = res.status();
    if status.is_success() {
        return Ok(res);
    }
    if status == StatusCode::UNAUTHORIZED {
        return Err("Wrong app key (the server said 401)".into());
    }
    if status == StatusCode::NOT_FOUND && res.url().path().ends_with("/events") {
        return Err("This server has no event stream; update LoopbackServer".into());
    }
    let text = res.text().await.unwrap_or_default();
    let message = serde_json::from_str::<serde_json::Value>(&text)
        .ok()
        .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(str::to_owned))
        .unwrap_or_else(|| format!("Server returned {status}"));
    Err(message)
}

fn describe(err: reqwest::Error) -> String {
    if err.is_connect() {
        "Can't connect to the server".into()
    } else if err.is_timeout() {
        "The server took too long to respond".into()
    } else {
        err.to_string()
    }
}

/// Ids are base58, but don't let anything odd reshape the path.
fn encode(id: &str) -> String {
    id.chars().filter(|c| c.is_ascii_alphanumeric()).collect()
}
