//! Outgoing HTTPS client used when the desktop talks to a peer
//! (send file/text/clipboard, remote input, system actions).
//!
//! Security: the peer's self-signed certificate is pinned by SHA-256
//! fingerprint (stored at pairing time). Any other certificate is rejected.

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error, SignatureScheme};
use std::sync::Arc;

use crate::state::PeerInfo;

#[derive(Debug)]
struct PinVerifier {
    fp: [u8; 32],
}

impl PinVerifier {
    fn new(fp_hex: &str) -> Result<Self, String> {
        let bytes = hex::decode(fp_hex).map_err(|e| format!("bad fingerprint: {e}"))?;
        if bytes.len() != 32 {
            return Err("fingerprint must be 32 bytes".to_string());
        }
        let mut fp = [0u8; 32];
        fp.copy_from_slice(&bytes);
        Ok(Self { fp })
    }
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        use sha2::{Digest, Sha256};
        let digest: [u8; 32] = Sha256::digest(end_entity.as_ref()).into();
        if digest == self.fp {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(Error::General(
                "VrShare: peer certificate fingerprint mismatch".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        let provider = rustls::crypto::ring::default_provider();
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        let provider = rustls::crypto::ring::default_provider();
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

fn client_for(peer: &PeerInfo) -> Result<reqwest::Client, String> {
    pinned_client_with_timeout(&peer.fp, 60)
}

/// Build an HTTPS client that pins the peer's certificate by SHA-256 fingerprint.
#[allow(dead_code)]
pub fn pinned_client(fp_hex: &str) -> Result<reqwest::Client, String> {
    pinned_client_with_timeout(fp_hex, 60)
}

pub fn pinned_client_with_timeout(fp_hex: &str, secs: u64) -> Result<reqwest::Client, String> {
    let verifier = Arc::new(PinVerifier::new(fp_hex)?);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("tls config: {e}"))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();

    reqwest::ClientBuilder::new()
        .use_preconfigured_tls(tls)
        .timeout(std::time::Duration::from_secs(secs))
        .build()
        .map_err(|e| format!("http client: {e}"))
}

fn url(peer: &PeerInfo, path: &str) -> String {
    format!("https://{}:{}{}", peer.ip, peer.port, path)
}

fn token_for(peer_id: &str, store: &crate::store::Store) -> Result<String, String> {
    store
        .trusted
        .read()
        .unwrap()
        .get(peer_id)
        .map(|d| d.token.clone())
        .ok_or_else(|| "device is not paired".to_string())
}

pub async fn post_json(
    state: &crate::state::AppState,
    peer_id: &str,
    path: &str,
    body: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let peer = state
        .peers
        .lock()
        .unwrap()
        .get(peer_id)
        .cloned()
        .ok_or_else(|| "peer not found (is it online?)".to_string())?;
    let token = token_for(peer_id, &state.store)?;
    let client = client_for(&peer)?;
    let resp = client
        .post(url(&peer, path))
        .header("X-VrShare-Token", token)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("peer returned {status}: {text}"));
    }
    serde_json::from_str(&text).unwrap_or(Ok(serde_json::Value::Null))
}

/// Request the pairing handshake with a discovered (not yet trusted) peer.
/// Returns the status string from the peer ("pending" normally).
pub async fn request_pair(
    state: &crate::state::AppState,
    peer_id: &str,
) -> Result<String, String> {
    let peer = state
        .peers
        .lock()
        .unwrap()
        .get(peer_id)
        .cloned()
        .ok_or_else(|| "peer not found (is it online?)".to_string())?;
    // Pairing requests are unauthenticated by design (like LocalSend); the
    // peer shows a PIN dialog and a human approves.
    let verifier = Arc::new(PinVerifier::new(&peer.fp)?);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("tls config: {e}"))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    let client = reqwest::ClientBuilder::new()
        .use_preconfigured_tls(tls)
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| format!("http client: {e}"))?;

    let body = serde_json::json!({
        "id": state.device_id,
        "name": state.device_name(),
        "fp": state.fingerprint_hex,
        "os": std::env::consts::OS,
    });
    let resp = client
        .post(url(&peer, "/api/pair"))
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("pair request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("peer returned {status}: {text}"));
    }
    let v: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
    if let Some(token) = v.get("token").and_then(|t| t.as_str()) {
        // Already trusted on the other side (re-pair): store the token.
        let mut trusted = state.store.trusted.write().unwrap();
        if let Some(d) = trusted.get_mut(peer_id) {
            d.token = token.to_string();
        } else {
            trusted.insert(
                peer_id.to_string(),
                crate::store::TrustedDevice {
                    id: peer_id.to_string(),
                    name: peer.name.clone(),
                    fp: peer.fp.clone(),
                    os: peer.os.clone(),
                    token: token.to_string(),
                    paired_at: crate::store::Store::paired_now(),
                },
            );
        }
        drop(trusted);
        let _ = state.store.save_trusted();
        state.mark_trusted_in_peers();
        state.emit_peers();
        return Ok("approved".to_string());
    }
    Ok(v
        .get("status")
        .and_then(|s| s.as_str())
        .unwrap_or("pending")
        .to_string())
}

/// Poll the peer for our pairing status (used after request_pair → pending).
pub async fn poll_pair_status(
    state: &crate::state::AppState,
    peer_id: &str,
) -> Result<String, String> {
    let peer = state
        .peers
        .lock()
        .unwrap()
        .get(peer_id)
        .cloned()
        .ok_or_else(|| "peer not found (is it online?)".to_string())?;
    let verifier = Arc::new(PinVerifier::new(&peer.fp)?);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("tls config: {e}"))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    let client = reqwest::ClientBuilder::new()
        .use_preconfigured_tls(tls)
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| format!("http client: {e}"))?;

    let resp = client
        .get(url(&peer, &format!("/api/pair/status?id={}", state.device_id)))
        .send()
        .await
        .map_err(|e| format!("status poll failed: {e}"))?;
    let text = resp.text().await.unwrap_or_default();
    let v: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
    let status = v
        .get("status")
        .and_then(|s| s.as_str())
        .unwrap_or("unknown")
        .to_string();
    if status == "approved" {
        if let Some(token) = v.get("token").and_then(|t| t.as_str()) {
            let mut trusted = state.store.trusted.write().unwrap();
            if let Some(d) = trusted.get_mut(peer_id) {
                d.token = token.to_string();
            } else {
                trusted.insert(
                    peer_id.to_string(),
                    crate::store::TrustedDevice {
                        id: peer_id.to_string(),
                        name: peer.name.clone(),
                        fp: peer.fp.clone(),
                        os: peer.os.clone(),
                        token: token.to_string(),
                        paired_at: crate::store::Store::paired_now(),
                    },
                );
            }
            drop(trusted);
            let _ = state.store.save_trusted();
            state.mark_trusted_in_peers();
            state.emit_peers();
        }
    }
    Ok(status)
}
