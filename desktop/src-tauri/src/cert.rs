//! Self-signed TLS certificate management for the VrShare HTTPS server.
//! The certificate is generated once (ECDSA P-256) and persisted in the app
//! data directory. Peers pin the SHA-256 fingerprint of this certificate.

use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256};
use sha2::{Digest, Sha256};
use std::path::PathBuf;

pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    pub fingerprint_hex: String,
}

fn paths(data_dir: &std::path::Path) -> (PathBuf, PathBuf) {
    (
        data_dir.join("cert.der"),
        data_dir.join("key.der"),
    )
}

pub fn load_or_generate(data_dir: &std::path::Path) -> anyhow::Result<Identity> {
    std::fs::create_dir_all(data_dir)?;
    let (cert_path, key_path) = paths(data_dir);

    let (cert_der, key_der) = match (
        std::fs::read(&cert_path),
        std::fs::read(&key_path),
    ) {
        (Ok(cert_der), Ok(key_der)) if !cert_der.is_empty() && !key_der.is_empty() => {
            (cert_der, key_der)
        }
        _ => {
            let mut params = CertificateParams::new(vec!["VrShare".to_string()])
                .map_err(|e| anyhow::anyhow!("cert params failed: {e}"))?;
            let mut dn = DistinguishedName::new();
            dn.push(DnType::CommonName, "VrShare");
            params.distinguished_name = dn;

            let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
                .map_err(|e| anyhow::anyhow!("key generation failed: {e}"))?;
            let certified = params
                .self_signed(&key_pair)
                .map_err(|e| anyhow::anyhow!("self-sign failed: {e}"))?;
            let cert_der = certified.der().to_vec();
            let key_der = key_pair.serialize_der();

            std::fs::write(&cert_path, &cert_der)?;
            std::fs::write(&key_path, &key_der)?;
            (cert_der, key_der)
        }
    };

    let fingerprint_hex = hex::encode(Sha256::digest(&cert_der));
    Ok(Identity {
        cert_der,
        key_der,
        fingerprint_hex,
    })
}
