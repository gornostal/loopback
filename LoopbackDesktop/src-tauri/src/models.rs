//! Mirrors the server's JSON (`LoopbackServer/src/types.ts`). Keep in sync with `src/types.ts` here,
//! `api/Models.kt` in the Android app and `skill/loopback.py`.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestOption {
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Answer {
    #[serde(default)]
    pub selected: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    pub answered_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoopbackRequest {
    pub id: String,
    pub created_at: String,
    /// `ask` or `notify`.
    #[serde(default = "default_kind")]
    pub kind: String,
    /// `pending`, `answered`, `cancelled` or `notified`.
    pub status: String,
    pub title: String,
    #[serde(default)]
    pub context: Option<String>,
    #[serde(default)]
    pub options: Vec<RequestOption>,
    #[serde(default)]
    pub multi_select: bool,
    #[serde(default = "default_true")]
    pub allow_free_text: bool,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub answer: Option<Answer>,
    #[serde(default)]
    pub read_at: Option<String>,
}

impl LoopbackRequest {
    pub fn is_notification(&self) -> bool {
        self.kind == "notify"
    }
}

fn default_kind() -> String {
    "ask".into()
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
pub struct RequestList {
    pub requests: Vec<LoopbackRequest>,
}
