//! VrShare HTTPS API server (axum + rustls).
//!
//! Public endpoints:  GET  /api/info
//!                    POST /api/pair
//!                    GET  /api/pair/status?id=...
//! Protected (X-VrShare-Token of a trusted device):
//!                    POST /api/text {text, clipboard?}
//!                    GET  /api/clipboard
//!                    POST /api/clipboard {text}
//!                    POST /api/files (multipart: id, name, size, file; resume via X-Offset)
//!                    POST /api/input/move {dx,dy}
//!                    POST /api/input/click {button, action}
//!                    POST /api/input/scroll {dx,dy}
//!                    POST /api/input/key {key, action}
//!                    POST /api/input/text {text}
//!                    POST /api/system {action}

use axum::{
    Router,
    extract::{Multipart, Query, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use crate::state::{AppState, TransferProgress, PORT};
use crate::{clip, input, system};

type Shared = Arc<AppState>;

// ---------- auth middleware ----------

async fn auth(
    State(state): State<Shared>,
    headers: HeaderMap,
    req: axum::http::Request<axum::body::Body>,
    next: Next,
) -> Response {
    let token = headers
        .get("x-vrshare-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    match state.store.id_for_token(token) {
        Some(_) => next.run(req).await,
        None => (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "not paired - token missing or unknown"})),
        )
            .into_response(),
    }
}

// ---------- public ----------

#[derive(Serialize)]
struct Info {
    id: String,
    name: String,
    fp: String,
    os: String,
    v: u8,
    features: Vec<String>,
}

async fn info(State(state): State<Shared>) -> Json<Info> {
    Json(Info {
        id: state.device_id.clone(),
        name: state.device_name(),
        fp: state.fingerprint_hex.clone(),
        os: std::env::consts::OS.to_string(),
        v: crate::state::PROTOCOL_VERSION,
        features: vec![
            "files".into(),
            "clipboard".into(),
            "input".into(),
            "system".into(),
        ],
    })
}

#[derive(Deserialize)]
struct PairReq {
    id: String,
    name: String,
    fp: String,
    os: Option<String>,
}

async fn pair(State(state): State<Shared>, Json(req): Json<PairReq>) -> Response {
    if req.id.is_empty() || req.fp.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "id and fp are required"})),
        )
            .into_response();
    }
    // Already trusted + same fingerprint -> hand back the token (re-pair).
    if let Some(t) = state.store.trusted.read().unwrap().get(&req.id) {
        if t.fp == req.fp {
            return Json(serde_json::json!({"status": "approved", "token": t.token})).into_response();
        }
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "certificate fingerprint changed - unpair and pair again"})),
        )
            .into_response();
    }
    let pin: u32 = rand::random_range(100_000..1_000_000);
    let pin_str = format!("{pin:06}");
    state.pending.lock().unwrap().insert(
        req.id.clone(),
        crate::state::PendingPair {
            id: req.id.clone(),
            name: req.name.clone(),
            os: req.os.clone().unwrap_or_default(),
            fp: req.fp.clone(),
            pin: pin_str.clone(),
            created: Instant::now(),
        },
    );
    state.emit(
        "pair-request",
        serde_json::json!({
            "id": req.id,
            "name": req.name,
            "os": req.os.unwrap_or_default(),
            "pin": pin_str,
        }),
    );
    state.log(format!("Pairing request from '{}' - waiting for approval", req.name));
    Json(serde_json::json!({"status": "pending"})).into_response()
}

async fn pair_status(
    State(state): State<Shared>,
    Query(q): Query<HashMap<String, String>>,
) -> Json<serde_json::Value> {
    let id = q.get("id").cloned().unwrap_or_default();
    if let Some(t) = state.store.trusted.read().unwrap().get(&id) {
        return Json(serde_json::json!({"status": "approved", "token": t.token}));
    }
    if state.pending.lock().unwrap().contains_key(&id) {
        return Json(serde_json::json!({"status": "pending"}));
    }
    Json(serde_json::json!({"status": "unknown"}))
}

// ---------- protected ----------

#[derive(Deserialize)]
struct TextReq {
    text: String,
    clipboard: Option<bool>,
}

async fn recv_text(State(state): State<Shared>, Json(req): Json<TextReq>) -> Response {
    let text = req.text;
    if req.clipboard.unwrap_or(false) {
        match clip::set_text(&text) {
            Ok(()) => {
                *state.last_remote_clipboard.lock().unwrap() =
                    Some((text.clone(), Instant::now()));
            }
            Err(e) => {
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(serde_json::json!({"error": e})),
                )
                    .into_response()
            }
        }
    }
    state.emit("text-received", serde_json::json!({ "text": text }));
    state.log(format!(
        "Received text ({} chars){}",
        text.chars().count(),
        if req.clipboard.unwrap_or(false) {
            " -> clipboard"
        } else {
            ""
        }
    ));
    Json(serde_json::json!({"ok": true})).into_response()
}

async fn get_clipboard(State(_state): State<Shared>) -> Response {
    match clip::get_text() {
        Ok(text) => Json(serde_json::json!({"text": text})).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": e})),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
struct ClipboardReq {
    text: String,
}

async fn set_clipboard(State(state): State<Shared>, Json(req): Json<ClipboardReq>) -> Response {
    match clip::set_text(&req.text) {
        Ok(()) => {
            *state.last_remote_clipboard.lock().unwrap() =
                Some((req.text.clone(), Instant::now()));
            state.emit("clipboard-received", serde_json::json!({ "text": req.text }));
            state.log(format!(
                "Clipboard updated from peer ({} chars)",
                req.text.chars().count()
            ));
            Json(serde_json::json!({"ok": true})).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": e})),
        )
            .into_response(),
    }
}

fn sanitize_filename(name: &str) -> String {
    let base = std::path::Path::new(name)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file");
    let cleaned: String = base
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | ' ' | '(' | ')') {
                c
            } else {
                '_'
            }
        })
        .collect();
    if cleaned.is_empty() {
        "file".to_string()
    } else {
        cleaned
    }
}

async fn recv_file(
    State(state): State<Shared>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Response {
    if !state.store.settings.read().unwrap().auto_accept_files {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "receiver has auto-accept disabled"})),
        )
            .into_response();
    }

    let offset: u64 = headers
        .get("x-offset")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let mut transfer_id = uuid::Uuid::new_v4().to_string();
    let mut name = String::from("file");
    let mut size: u64 = 0;
    let mut file_bytes: Vec<Vec<u8>> = Vec::new();

    while let Ok(Some(field)) = multipart.next_field().await {
        match field.name().unwrap_or("") {
            "id" => {
                if let Ok(t) = field.text().await {
                    if !t.is_empty() {
                        transfer_id = t;
                    }
                }
            }
            "name" => {
                if let Ok(t) = field.text().await {
                    if !t.is_empty() {
                        name = t;
                    }
                }
            }
            "size" => {
                if let Ok(t) = field.text().await {
                    size = t.parse().unwrap_or(0);
                }
            }
            "file" => {
                let mut chunks = Vec::new();
                let mut f = field;
                while let Ok(Some(chunk)) = f.chunk().await {
                    chunks.push(chunk.to_vec());
                }
                file_bytes = chunks;
            }
            _ => {}
        }
    }

    if file_bytes.iter().map(|c| c.len()).sum::<usize>() == 0 {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "no file data in request"})),
        )
            .into_response();
    }

    let safe_name = sanitize_filename(&name);
    let dir = std::path::PathBuf::from(&state.store.settings.read().unwrap().download_dir);
    if let Err(e) = std::fs::create_dir_all(&dir) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("cannot create download dir: {e}")})),
        )
            .into_response();
    }
    let path = dir.join(&safe_name);

    let write_result = (|| -> Result<u64, String> {
        use std::io::{Seek, SeekFrom, Write};
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(offset == 0)
            .open(&path)
            .map_err(|e| format!("open failed: {e}"))?;
        if offset > 0 {
            f.seek(SeekFrom::Start(offset))
                .map_err(|e| format!("seek failed: {e}"))?;
        }
        let mut received = offset;
        for chunk in &file_bytes {
            f.write_all(chunk).map_err(|e| format!("write failed: {e}"))?;
            received += chunk.len() as u64;
        }
        f.flush().map_err(|e| format!("flush failed: {e}"))?;
        Ok(received)
    })();

    let received = match write_result {
        Ok(r) => r,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": e})),
            )
                .into_response()
        }
    };

    let done = size == 0 || received >= size;
    {
        let mut transfers = state.transfers.lock().unwrap();
        transfers.insert(
            transfer_id.clone(),
            TransferProgress {
                id: transfer_id.clone(),
                name: safe_name.clone(),
                received,
                size,
                done,
                direction: "down".to_string(),
                peer: "peer".to_string(),
            },
        );
    }
    state.emit(
        "transfer-progress",
        serde_json::json!({
            "id": transfer_id, "name": safe_name, "received": received,
            "size": size, "done": done, "direction": "down",
        }),
    );
    if done {
        state.log(format!("Received file: {} ({} bytes)", safe_name, received));
    }
    Json(serde_json::json!({"received": received, "done": done})).into_response()
}

// ---------- remote input ----------

#[derive(Deserialize)]
struct MoveReq {
    dx: i32,
    dy: i32,
}

async fn in_move(Json(req): Json<MoveReq>) -> Response {
    ok_or_500(input::move_rel(req.dx, req.dy))
}

#[derive(Deserialize)]
struct ClickReq {
    button: String,
    action: String,
}

async fn in_click(Json(req): Json<ClickReq>) -> Response {
    ok_or_500(input::click(&req.button, &req.action))
}

#[derive(Deserialize)]
struct ScrollReq {
    dx: i32,
    dy: i32,
}

async fn in_scroll(Json(req): Json<ScrollReq>) -> Response {
    ok_or_500(input::scroll(req.dx, req.dy))
}

#[derive(Deserialize)]
struct KeyReq {
    key: String,
    action: String,
}

async fn in_key(Json(req): Json<KeyReq>) -> Response {
    ok_or_500(input::key(&req.key, &req.action))
}

#[derive(Deserialize)]
struct TypeReq {
    text: String,
}

async fn in_text(Json(req): Json<TypeReq>) -> Response {
    ok_or_500(input::type_text(&req.text))
}

fn ok_or_500(r: Result<(), String>) -> Response {
    match r {
        Ok(()) => Json(serde_json::json!({"ok": true})).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": e})),
        )
            .into_response(),
    }
}

// ---------- system ----------

#[derive(Deserialize)]
struct SystemReq {
    action: String,
}

async fn sys_action(State(state): State<Shared>, Json(req): Json<SystemReq>) -> Response {
    match system::run(&req.action) {
        Ok(()) => {
            state.log(format!("Executed system action: {}", req.action));
            Json(serde_json::json!({"ok": true})).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": e})),
        )
            .into_response(),
    }
}

// ---------- serve ----------

pub async fn serve(state: Shared) -> anyhow::Result<()> {
    let protected = Router::new()
        .route("/api/text", post(recv_text))
        .route("/api/clipboard", get(get_clipboard).post(set_clipboard))
        .route("/api/files", post(recv_file))
        .route("/api/input/move", post(in_move))
        .route("/api/input/click", post(in_click))
        .route("/api/input/scroll", post(in_scroll))
        .route("/api/input/key", post(in_key))
        .route("/api/input/text", post(in_text))
        .route("/api/system", post(sys_action))
        .route_layer(middleware::from_fn_with_state(state.clone(), auth));

    let app = Router::new()
        .route("/api/info", get(info))
        .route("/api/pair", post(pair))
        .route("/api/pair/status", get(pair_status))
        .merge(protected)
        .with_state(state.clone());

    let tls = axum_server::tls_rustls::RustlsConfig::from_der(
        vec![state.cert_der.clone()],
        state.key_der.clone(),
    )
    .await?;

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], PORT));
    state.log(format!("HTTPS API listening on port {PORT}"));
    axum_server::bind_rustls(addr, tls)
        .serve(app.into_make_service())
        .await?;
    Ok(())
}
