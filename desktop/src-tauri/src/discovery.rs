//! mDNS discovery: register `_vrshare._tcp.local.` and browse for peers.

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;

use crate::state::{AppState, PeerInfo, PORT, SERVICE_TYPE};

fn local_ip() -> Option<IpAddr> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    // No packet is actually sent; this just selects the outbound interface.
    sock.connect("8.8.8.8:80").ok()?;
    sock.local_addr().ok().map(|a| a.ip())
}

pub fn run(state: Arc<AppState>) {
    std::thread::Builder::new()
        .name("vrshare-mdns".into())
        .spawn(move || {
            if let Err(e) = run_blocking(state) {
                eprintln!("[vrshare] mDNS error: {e}");
            }
        })
        .expect("failed to spawn mDNS thread");
}

fn run_blocking(state: Arc<AppState>) -> anyhow::Result<()> {
    let daemon = ServiceDaemon::new()?;

    let ip = local_ip();
    let addrs: Vec<IpAddr> = ip.into_iter().collect();
    if addrs.is_empty() {
        anyhow::bail!("no local IP address found");
    }

    let host = format!("vrshare-{}.local.", &state.device_id[..8.min(state.device_id.len())]);
    let instance = format!("{} ({})", state.device_name(), &state.device_id[..8.min(state.device_id.len())]);

    let mut txt = HashMap::new();
    txt.insert("id".to_string(), state.device_id.clone());
    txt.insert("name".to_string(), state.device_name());
    txt.insert("fp".to_string(), state.fingerprint_hex.clone());
    txt.insert("os".to_string(), std::env::consts::OS.to_string());
    txt.insert("v".to_string(), crate::state::PROTOCOL_VERSION.to_string());

    let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host, &addrs[..], PORT, Some(txt))?;
    daemon.register(info)?;
    state.log(format!(
        "Advertising as '{}' on {}:{}",
        state.device_name(),
        addrs[0],
        PORT
    ));

    let browse = daemon.browse(SERVICE_TYPE)?;
    loop {
        let event = match browse.recv() {
            Ok(e) => e,
            Err(_) => break,
        };
        match event {
            ServiceEvent::ServiceResolved(info) => {
                handle_resolved(&state, &info);
            }
            ServiceEvent::ServiceRemoved(_ty, instance_name) => {
                let id = state
                    .instance_to_id
                    .lock()
                    .unwrap()
                    .remove(&instance_name);
                if let Some(id) = id {
                    if id == state.device_id {
                        continue; // our own record
                    }
                    state.peers.lock().unwrap().remove(&id);
                    state.log(format!("Device went offline: {instance_name}"));
                    state.emit_peers();
                }
            }
            _ => {}
        }
    }
    Ok(())
}

fn handle_resolved(state: &Arc<AppState>, info: &ServiceInfo) {
    let props = info.get_properties();
    let get = |k: &str| props.get(k).map(|v| v.to_string()).unwrap_or_default();
    let id = get("id");
    if id.is_empty() || id == state.device_id {
        return; // not a VrShare peer, or ourselves
    }
    let ip = info
        .get_addresses()
        .iter()
        .find(|a| a.is_ipv4())
        .or_else(|| info.get_addresses().iter().next())
        .map(|a| a.to_string())
        .unwrap_or_default();
    if ip.is_empty() {
        return;
    }
    let peer = PeerInfo {
        id: id.clone(),
        name: {
            let n = get("name");
            if n.is_empty() {
                id.clone()
            } else {
                n
            }
        },
        fp: get("fp"),
        os: get("os"),
        ip,
        port: info.get_port(),
        trusted: state.is_trusted(&id),
    };
    state
        .instance_to_id
        .lock()
        .unwrap()
        .insert(info.get_fullname().to_string(), id.clone());
    let is_new = {
        let mut peers = state.peers.lock().unwrap();
        let is_new = !peers.contains_key(&id);
        peers.insert(id.clone(), peer.clone());
        is_new
    };
    if is_new {
        state.log(format!("Discovered device: {} ({})", peer.name, peer.os));
    }
    state.emit_peers();
}
