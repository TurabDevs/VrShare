//! Outgoing file transfers: desktop -> peer (multipart POST with progress).

use futures_util::Stream;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio_util::codec::{BytesCodec, FramedRead};

use crate::state::AppState;
use crate::tls_client::pinned_client_with_timeout;

/// AsyncRead wrapper that reports progress.
struct ProgressReader<R> {
    inner: R,
    sent: u64,
    tx: tokio::sync::mpsc::UnboundedSender<u64>,
}

impl<R: tokio::io::AsyncRead + Unpin> ProgressReader<R> {
    fn new(inner: R, tx: tokio::sync::mpsc::UnboundedSender<u64>) -> Self {
        Self {
            inner,
            sent: 0,
            tx,
        }
    }
}

impl<R: tokio::io::AsyncRead + Unpin> tokio::io::AsyncRead for ProgressReader<R> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let before = buf.filled().len();
        let this = &mut *self;
        match Pin::new(&mut this.inner).poll_read(cx, buf) {
            Poll::Ready(Ok(())) => {
                let n = buf.filled().len() - before;
                this.sent += n as u64;
                let _ = this.tx.send(this.sent);
                Poll::Ready(Ok(()))
            }
            other => other,
        }
    }
}

struct ProgressStream {
    inner: FramedRead<ProgressReader<tokio::fs::File>, BytesCodec>,
}

impl Stream for ProgressStream {
    type Item = Result<bytes::Bytes, std::io::Error>;
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        use futures_util::StreamExt;
        match self.inner.poll_next_unpin(cx) {
            Poll::Ready(Some(Ok(b))) => Poll::Ready(Some(Ok(b.freeze()))),
            Poll::Ready(Some(Err(e))) => Poll::Ready(Some(Err(e))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

pub async fn send_file(
    state: Arc<AppState>,
    peer_id: &str,
    path: PathBuf,
) -> Result<(), String> {
    let peer = state
        .peers
        .lock()
        .unwrap()
        .get(peer_id)
        .cloned()
        .ok_or_else(|| "peer not found (is it online?)".to_string())?;
    let token = state
        .store
        .trusted
        .read()
        .unwrap()
        .get(peer_id)
        .map(|d| d.token.clone())
        .ok_or_else(|| "device is not paired".to_string())?;

    let meta = tokio::fs::metadata(&path)
        .await
        .map_err(|e| format!("cannot read file: {e}"))?;
    let size = meta.len();
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();
    let transfer_id = uuid::Uuid::new_v4().to_string();

    // Build a TLS-pinned client (certificate fingerprint pinning).
    let client = pinned_client_with_timeout(&peer.fp, 300)?;

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<u64>();
    let file = tokio::fs::File::open(&path)
        .await
        .map_err(|e| format!("cannot open file: {e}"))?;
    let reader = ProgressReader::new(file, tx);
    let stream = ProgressStream {
        inner: FramedRead::new(reader, BytesCodec::new()),
    };
    let body = reqwest::Body::wrap_stream(stream);

    let part = reqwest::multipart::Part::stream(body).file_name(name.clone());
    let form = reqwest::multipart::Form::new()
        .text("id", transfer_id.clone())
        .text("name", name.clone())
        .text("size", size.to_string())
        .part("file", part);

    // Progress pump.
    let state_c = state.clone();
    let tid_c = transfer_id.clone();
    let name_c = name.clone();
    let peer_name = peer.name.clone();
    tokio::spawn(async move {
        while let Some(sent) = rx.recv().await {
            let done = sent >= size;
            {
                let mut t = state_c.transfers.lock().unwrap();
                t.insert(
                    tid_c.clone(),
                    crate::state::TransferProgress {
                        id: tid_c.clone(),
                        name: name_c.clone(),
                        received: sent,
                        size,
                        done,
                        direction: "up".to_string(),
                        peer: peer_name.clone(),
                    },
                );
            }
            state_c.emit(
                "transfer-progress",
                serde_json::json!({
                    "id": tid_c, "name": name_c, "received": sent,
                    "size": size, "done": done, "direction": "up",
                    "peer": peer_name,
                }),
            );
            if done {
                break;
            }
        }
    });

    let url = format!("https://{}:{}/api/files", peer.ip, peer.port);
    let resp = client
        .post(&url)
        .header("X-VrShare-Token", token)
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("upload failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("peer returned {status}: {text}"));
    }
    state.log(format!("Sent file: {} -> {} ({} bytes)", name, peer.name, size));
    Ok(())
}
