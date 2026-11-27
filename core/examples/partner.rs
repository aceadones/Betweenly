//! Desktop partner for the relay and the Android app. No extra crates.
//!
//! Usage (from `core/`):
//!   cargo run --example partner -- <relay-url> <CODE>
//!
//! The phone shows the code (role a). This process joins as role b.
//! After pairing it reads commands from stdin:
//!   chat <text>
//!   ring
//!   share on|off
//!   loc <lat> <lon> <bat>
//!   draw <file.png>
//!   photo <file>
//!   sticker <file.png>
//!   gif <file.gif>
//!   file <path>
//!   unsend <id>
//!   wall color <0-5>
//!   react <id> <emoji>
//!   quit
//!
//! Every decrypted inbox message is printed. Attachments and drawings are
//! downloaded, decrypted, and saved under /tmp/partner-in/.
//! `curl` must be on PATH. Needs the `base64` dev-dependency only.

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use betweenly_core::{file_decrypt, file_encrypt, rendezvous_id, Pairing, Session};
use std::fs::File;
use std::io::{self, BufRead, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const MAX_FILE: u64 = 24 * 1024 * 1024;
static TEMP_SEQ: AtomicU64 = AtomicU64::new(1);

fn main() {
    let mut args = std::env::args().skip(1);
    let relay = match args.next() {
        Some(v) => v.trim().trim_end_matches('/').to_string(),
        None => {
            usage();
            std::process::exit(2);
        }
    };
    let code = match args.next() {
        Some(v) => v,
        None => {
            usage();
            std::process::exit(2);
        }
    };
    if relay.is_empty() {
        usage();
        std::process::exit(2);
    }

    let mut session = match pair(&relay, &code) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("pairing failed: {e}");
            std::process::exit(1);
        }
    };
    println!("PAIRED. safety number = {}", session.safety_number);
    let inbox = hex(&session.inbox_id);
    let outbox = hex(&session.outbox_id);
    let profile = r#"{"t":"profile","name":"Desktop","avatar":""}"#;
    if !send_sealed(&mut session, &relay, &outbox, profile, true) {
        println!("profile was not accepted; commands still work");
    }

    let session = Arc::new(Mutex::new(session));
    let quit = Arc::new(AtomicBool::new(false));
    let worker = {
        let session = Arc::clone(&session);
        let quit = Arc::clone(&quit);
        let relay = relay.clone();
        let inbox = inbox.clone();
        thread::spawn(move || inbox_loop(session, quit, relay, inbox))
    };

    println!("commands: chat, ring, share on|off, loc <lat> <lon> <bat>, draw, photo, sticker, gif, file, quit");
    let stdin = io::stdin();
    let mut reader = stdin.lock();
    let mut line = String::new();
    loop {
        print!("> ");
        let _ = io::stdout().flush();
        line.clear();
        let read = match reader.read_line(&mut line) {
            Ok(n) => n,
            Err(e) => {
                println!("stdin: {e}");
                break;
            }
        };
        if read == 0 {
            break;
        }
        if handle_line(&session, &relay, &outbox, line.trim()) {
            break;
        }
    }
    quit.store(true, Ordering::Relaxed);
    println!("quitting");
    let _ = worker.join();
}

fn usage() {
    eprintln!("usage: partner <relay-url> <CODE>");
}

fn pair(relay: &str, code: &str) -> Result<Session, String> {
    let rv = hex(&rendezvous_id(code).map_err(|e| format!("bad code: {e:?}"))?);
    let (pairing, mine) = Pairing::start(code).map_err(|e| format!("start: {e:?}"))?;
    let mut pairing = Some(pairing);
    let body = B64.encode(mine);
    let url = format!("{relay}/pair/{rv}/b");
    let (status, _) = request("PUT", &url, Some(body.as_bytes()))?;
    if !(200..300).contains(&status) {
        return Err(format!("pair put http {status}"));
    }
    println!("waiting for the phone that showed the code");
    for _ in 0..60 {
        match request("GET", &url, None) {
            Ok((status, body)) if (200..300).contains(&status) => {
                let text = String::from_utf8_lossy(&body);
                if let Some(msg) = json_string(&text, "msg") {
                    let raw = B64.decode(msg.as_bytes()).map_err(|e| format!("pair decode: {e}"))?;
                    let ready = pairing.take().ok_or_else(|| "pairing already finished".to_string())?;
                    let session = ready.finish(&raw).map_err(|e| format!("finish: {e:?}"))?;
                    return Ok(session.with_role(false));
                }
            }
            Ok(_) => {}
            Err(e) => println!("pair poll: {e}"),
        }
        thread::sleep(Duration::from_secs(2));
    }
    Err("no message from the phone that showed the code".into())
}

fn inbox_loop(session: Arc<Mutex<Session>>, quit: Arc<AtomicBool>, relay: String, inbox: String) {
    while !quit.load(Ordering::Relaxed) {
        poll_inbox(&session, &quit, &relay, &inbox);
        if sleep_quit(&quit, 2_000) {
            break;
        }
    }
}

fn poll_inbox(session: &Mutex<Session>, quit: &AtomicBool, relay: &str, inbox: &str) {
    let url = format!("{relay}/box/{inbox}");
    let (status, body) = match request("GET", &url, None) {
        Ok(v) => v,
        Err(e) => {
            println!("inbox: {e}");
            return;
        }
    };
    if !(200..300).contains(&status) {
        if status != 404 {
            println!("inbox http {status}");
        }
        return;
    }
    let text = String::from_utf8_lossy(&body).into_owned();
    for blob in decode_blobs(&text) {
        if quit.load(Ordering::Relaxed) {
            return;
        }
        let opened = {
            let mut guard = session.lock().unwrap_or_else(|e| e.into_inner());
            guard.open(&blob)
        };
        match opened {
            Ok(plain) => {
                let json = unpad(&plain);
                println!("RECEIVED: {json}");
                save_attachment(relay, quit, &json);
            }
            Err(e) => println!("open failed: {e:?}"),
        }
    }
}

fn handle_line(session: &Mutex<Session>, relay: &str, outbox: &str, line: &str) -> bool {
    if line.is_empty() {
        return false;
    }
    let mut parts = line.splitn(2, char::is_whitespace);
    let cmd = parts.next().unwrap_or("").to_ascii_lowercase();
    let arg = parts.next().unwrap_or("").trim();
    match cmd.as_str() {
        "quit" | "exit" => true,
        "help" => {
            println!("chat <text> | ring | share on|off | loc <lat> <lon> <bat> | draw <png> | photo <file> | sticker <png> | gif <gif> | file <path> | unsend <id> | wall color <0-5> | react <id> <emoji> | quit");
            false
        }
        "chat" => {
            if arg.is_empty() {
                println!("usage: chat <text>");
            } else {
                let json = format!(
                    "{{\"t\":\"chat\",\"id\":\"{}\",\"ts\":{},\"text\":\"{}\"}}",
                    random_hex(8),
                    now_ms(),
                    json_escape(arg),
                );
                let _ = send_json(session, relay, outbox, &json, true);
            }
            false
        }
        "ring" => {
            let _ = send_json(session, relay, outbox, r#"{"t":"ring"}"#, true);
            false
        }
        "share" => {
            let on = match arg.to_ascii_lowercase().as_str() {
                "on" => Some(true),
                "off" => Some(false),
                _ => None,
            };
            match on {
                Some(value) => {
                    let json = format!(
                        "{{\"t\":\"share\",\"on\":{},\"ts\":{}}}",
                        if value { "true" } else { "false" },
                        now_ms(),
                    );
                    let _ = send_json(session, relay, outbox, &json, true);
                }
                None => println!("usage: share on|off"),
            }
            false
        }
        "loc" => {
            match parse_loc(arg) {
                Some((lat, lon, bat)) => {
                    let json = format!(
                        "{{\"t\":\"loc\",\"lat\":{lat},\"lon\":{lon},\"acc\":25.0,\"bat\":{bat},\"chg\":false,\"ts\":{},\"live\":false}}",
                        now_ms(),
                    );
                    // Wake so a background phone still fetches the point.
                    let _ = send_json(session, relay, outbox, &json, true);
                }
                None => println!("usage: loc <lat> <lon> <bat>"),
            }
            false
        }
        "typing" => {
            let json = format!("{{\"t\":\"typing\",\"on\":{}}}", if arg.trim() == "off" { "false" } else { "true" });
            let _ = send_json(session, relay, outbox, &json, false);
            false
        }
        "draw" => {
            send_drawing(session, relay, outbox, arg);
            false
        }
        "photo" => {
            send_attachment(session, relay, outbox, arg, "photo");
            false
        }
        "sticker" => {
            send_attachment(session, relay, outbox, arg, "sticker");
            false
        }
        "gif" => {
            send_attachment(session, relay, outbox, arg, "gif");
            false
        }
        "file" => {
            send_attachment(session, relay, outbox, arg, "file");
            false
        }
        "unsend" => {
            let id = arg.to_ascii_lowercase();
            if !msg_id(&id) {
                println!("usage: unsend <id>");
            } else {
                let json = format!("{{\"t\":\"unsend\",\"id\":\"{id}\"}}");
                let _ = send_json(session, relay, outbox, &json, false);
            }
            false
        }
        "wall" => {
            send_wall_color(session, relay, outbox, arg);
            false
        }
        "react" => {
            send_react(session, relay, outbox, arg);
            false
        }
        _ => {
            println!("unknown command. type help");
            false
        }
    }
}

fn msg_id(id: &str) -> bool {
    id.len() == 16 && id.bytes().all(|b| b.is_ascii_hexdigit())
}

fn send_wall_color(session: &Mutex<Session>, relay: &str, outbox: &str, arg: &str) {
    let mut parts = arg.split_whitespace();
    let kind = parts.next().unwrap_or("").to_ascii_lowercase();
    let color = parts.next().and_then(|s| s.parse::<i32>().ok());
    let extra = parts.next();
    match (kind.as_str(), color, extra) {
        ("color", Some(n), None) if (0..=5).contains(&n) => {
            let json = format!(
                "{{\"t\":\"wall\",\"kind\":\"color\",\"color\":{n},\"fid\":\"\",\"key\":\"\",\"ts\":{}}}",
                now_ms(),
            );
            let _ = send_json(session, relay, outbox, &json, false);
        }
        _ => println!("usage: wall color <0-5>"),
    }
}

fn send_react(session: &Mutex<Session>, relay: &str, outbox: &str, arg: &str) {
    let mut parts = arg.split_whitespace();
    let id = parts.next().unwrap_or("").to_ascii_lowercase();
    let emoji = parts.next().unwrap_or("");
    if !msg_id(&id) || emoji.is_empty() || emoji.chars().count() > 16 || parts.next().is_some() {
        println!("usage: react <id> <emoji>");
        return;
    }
    let json = format!(
        "{{\"t\":\"react\",\"id\":\"{id}\",\"emoji\":\"{}\"}}",
        json_escape(emoji),
    );
    let _ = send_json(session, relay, outbox, &json, true);
}

fn parse_loc(arg: &str) -> Option<(f64, f64, i32)> {
    let mut it = arg.split_whitespace();
    let lat: f64 = it.next()?.parse().ok()?;
    let lon: f64 = it.next()?.parse().ok()?;
    let bat: i32 = it.next()?.parse().ok()?;
    if it.next().is_some() || !lat.is_finite() || !lon.is_finite() {
        return None;
    }
    if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) || !(0..=100).contains(&bat) {
        return None;
    }
    Some((lat, lon, bat))
}

fn send_drawing(session: &Mutex<Session>, relay: &str, outbox: &str, path: &str) {
    let Some(bytes) = read_payload(path) else { return };
    if bytes.len() < 24 || !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        println!("draw expects a PNG");
        return;
    }
    let Some((fid, key)) = upload(relay, &bytes) else { return };
    let json = format!(
        "{{\"t\":\"draw\",\"ts\":{},\"fid\":\"{fid}\",\"key\":\"{}\",\"size\":{}}}",
        now_ms(),
        json_escape(&key),
        bytes.len(),
    );
    let _ = send_json(session, relay, outbox, &json, false);
}

fn send_attachment(session: &Mutex<Session>, relay: &str, outbox: &str, path: &str, kind: &str) {
    if kind == "sticker" && !path.to_ascii_lowercase().ends_with(".png") {
        println!("sticker expects a PNG");
        return;
    }
    if kind == "gif" && !path.to_ascii_lowercase().ends_with(".gif") {
        println!("gif expects a GIF");
        return;
    }
    let Some(bytes) = read_payload(path) else { return };
    let Some((fid, key)) = upload(relay, &bytes) else { return };
    let file_path = Path::new(path);
    let name = file_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file");
    let (w, h) = image_size(&bytes);
    let json = format!(
        "{{\"t\":\"att\",\"id\":\"{}\",\"ts\":{},\"kind\":\"{kind}\",\"mime\":\"{}\",\"name\":\"{}\",\"size\":{},\"fid\":\"{fid}\",\"key\":\"{}\",\"w\":{w},\"h\":{h},\"thumb\":\"\",\"text\":\"\"}}",
        random_hex(8),
        now_ms(),
        json_escape(&mime_of(file_path, kind)),
        json_escape(name),
        bytes.len(),
        json_escape(&key),
    );
    let _ = send_json(session, relay, outbox, &json, true);
}

fn read_payload(path: &str) -> Option<Vec<u8>> {
    if path.is_empty() {
        println!("missing file path");
        return None;
    }
    let meta = match std::fs::metadata(path) {
        Ok(m) => m,
        Err(e) => {
            println!("cannot read file: {e}");
            return None;
        }
    };
    if !meta.is_file() || meta.len() == 0 || meta.len() > MAX_FILE {
        println!("file empty or larger than 24 MB");
        return None;
    }
    match std::fs::read(path) {
        Ok(bytes) => Some(bytes),
        Err(e) => {
            println!("cannot read file: {e}");
            None
        }
    }
}

fn upload(relay: &str, plain: &[u8]) -> Option<(String, String)> {
    let (key, data) = match file_encrypt(plain) {
        Ok(v) => v,
        Err(e) => {
            println!("encrypt failed: {e:?}");
            return None;
        }
    };
    let key_b64 = B64.encode(key);
    for _ in 0..3 {
        let fid = random_hex(16);
        let url = format!("{relay}/file/{fid}");
        match request("PUT", &url, Some(&data)) {
            Ok((status, _)) if (200..300).contains(&status) => return Some((fid, key_b64.clone())),
            Ok((409, _)) => continue,
            Ok((status, _)) => {
                println!("upload http {status}");
                return None;
            }
            Err(e) => {
                println!("upload failed: {e}");
                return None;
            }
        }
    }
    println!("upload rejected: file id already used");
    None
}

fn save_attachment(relay: &str, quit: &AtomicBool, json: &str) {
    let kind = match json_string(json, "t") {
        Some(v) if v == "att" || v == "draw" => v,
        _ => return,
    };
    let Some(fid) = json_string(json, "fid") else { return };
    let Some(key) = json_string(json, "key") else { return };
    let Some(bytes) = download(relay, quit, &fid, &key) else {
        println!("could not download attachment");
        return;
    };
    let name = if kind == "draw" {
        format!("draw-{}.png", json_u64(json, "ts").unwrap_or(0))
    } else {
        json_string(json, "name").unwrap_or_else(|| "file.bin".into())
    };
    let id = json_string(json, "id").unwrap_or_else(|| "draw".into());
    let dir = Path::new("/tmp/partner-in");
    if let Err(e) = std::fs::create_dir_all(dir) {
        println!("cannot create /tmp/partner-in: {e}");
        return;
    }
    let path = dir.join(format!("{}-{}", safe_token(&id), safe_name(&name)));
    match std::fs::write(&path, &bytes) {
        Ok(()) => println!("saved {}", path.display()),
        Err(e) => println!("save failed: {e}"),
    }
}

fn download(relay: &str, quit: &AtomicBool, fid: &str, key_b64: &str) -> Option<Vec<u8>> {
    let key = B64.decode(key_b64.trim().as_bytes()).ok()?;
    if key.len() != 32 {
        return None;
    }
    let waits = [0u64, 2_000, 5_000, 10_000, 20_000, 30_000];
    for wait in waits {
        if wait > 0 && sleep_quit(quit, wait) {
            return None;
        }
        let url = format!("{relay}/file/{fid}");
        match request("GET", &url, None) {
            Ok((status, body)) if (200..300).contains(&status) && !body.is_empty() => {
                return file_decrypt(&key, &body).ok();
            }
            Ok((status, _)) if status == 404 || status >= 500 || status == 0 => continue,
            Ok(_) => return None,
            Err(_) => continue,
        }
    }
    None
}

fn send_json(session: &Mutex<Session>, relay: &str, outbox: &str, json: &str, wake: bool) -> bool {
    let ct = {
        let mut guard = session.lock().unwrap_or_else(|e| e.into_inner());
        match seal_json(&mut guard, json) {
            Some(ct) => ct,
            None => return false,
        }
    };
    post_box(relay, outbox, &ct, wake)
}

fn send_sealed(session: &mut Session, relay: &str, outbox: &str, json: &str, wake: bool) -> bool {
    let Some(ct) = seal_json(session, json) else { return false };
    post_box(relay, outbox, &ct, wake)
}

fn seal_json(session: &mut Session, json: &str) -> Option<Vec<u8>> {
    let Some(padded) = pad(json) else {
        println!("message is too large to send");
        return None;
    };
    match session.seal(&padded) {
        Ok(ct) => Some(ct),
        Err(e) => {
            println!("seal failed: {e:?}");
            None
        }
    }
}

fn post_box(relay: &str, outbox: &str, ct: &[u8], wake: bool) -> bool {
    let url = if wake {
        format!("{relay}/box/{outbox}?wake=1")
    } else {
        format!("{relay}/box/{outbox}")
    };
    match request("POST", &url, Some(ct)) {
        Ok((status, body)) => {
            let text = String::from_utf8_lossy(&body);
            println!("sent http {status} {text}");
            (200..300).contains(&status)
        }
        Err(e) => {
            println!("send failed: {e}");
            false
        }
    }
}

/// DATA-FLOW: every relay call goes through here. Pairing bodies are PAKE
/// messages. Mailbox bodies are sealed ciphertext. File bodies are ciphertext
/// from `file_encrypt`. The file key is never sent on its own.
fn request(method: &str, url: &str, body: Option<&[u8]>) -> Result<(u16, Vec<u8>), String> {
    let out = TempFile::new("out")?;
    let mut cmd = Command::new("curl");
    cmd.arg("-sS")
        .arg("--max-time")
        .arg(if method == "GET" { "30" } else { "120" })
        .arg("-o")
        .arg(&out.path)
        .arg("-w")
        .arg("%{http_code}")
        .arg("-X")
        .arg(method)
        .arg("-A")
        .arg("BetweenlyPartner")
        .stdin(Stdio::null());
    let _input = if let Some(bytes) = body {
        let input = TempFile::new("in")?;
        std::fs::write(&input.path, bytes).map_err(|e| format!("temp file: {e}"))?;
        cmd.arg("--data-binary").arg(format!("@{}", input.path.display()));
        Some(input)
    } else {
        None
    };
    cmd.arg(url);
    let output = cmd.output().map_err(|e| format!("curl: {e}"))?;
    let status_text = String::from_utf8_lossy(&output.stdout);
    let status = status_text.trim().parse::<u16>().unwrap_or(0);
    let data = std::fs::read(&out.path).unwrap_or_default();
    if !output.status.success() && status == 0 {
        let err = String::from_utf8_lossy(&output.stderr);
        return Err(format!("curl failed: {}", err.trim()));
    }
    Ok((status, data))
}

struct TempFile {
    path: PathBuf,
}

impl TempFile {
    fn new(kind: &str) -> Result<Self, String> {
        let n = TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
        let mut path = std::env::temp_dir();
        path.push(format!("partner-{kind}-{}-{n}.bin", std::process::id()));
        Ok(Self { path })
    }
}

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn sleep_quit(quit: &AtomicBool, ms: u64) -> bool {
    let mut left = ms;
    while left > 0 {
        if quit.load(Ordering::Relaxed) {
            return true;
        }
        let step = left.min(100);
        thread::sleep(Duration::from_millis(step));
        left -= step;
    }
    quit.load(Ordering::Relaxed)
}

fn pad(json: &str) -> Option<Vec<u8>> {
    let bytes = json.as_bytes();
    if bytes.is_empty() || bytes.len() > 65535 {
        return None;
    }
    let mut out = (bytes.len() as u16).to_be_bytes().to_vec();
    out.extend_from_slice(bytes);
    while out.len() % 512 != 0 {
        out.push(0);
    }
    Some(out)
}

fn unpad(padded: &[u8]) -> String {
    if padded.len() < 2 {
        return String::new();
    }
    let n = u16::from_be_bytes([padded[0], padded[1]]) as usize;
    let end = (2 + n).min(padded.len());
    String::from_utf8_lossy(&padded[2..end]).into_owned()
}

fn decode_blobs(json: &str) -> Vec<Vec<u8>> {
    let Some(key_at) = json.find("\"blobs\"") else { return Vec::new() };
    let Some(rel) = json[key_at..].find('[') else { return Vec::new() };
    let rest = &json[key_at + rel + 1..];
    let Some(end) = rest.find(']') else { return Vec::new() };
    let mut out = Vec::new();
    for part in rest[..end].split(',') {
        let token = part.trim().trim_matches('"');
        if token.is_empty() {
            continue;
        }
        if let Ok(bytes) = B64.decode(token.as_bytes()) {
            out.push(bytes);
        }
    }
    out
}

fn json_string(json: &str, key: &str) -> Option<String> {
    let pat = format!("\"{key}\"");
    let mut search = json;
    while let Some(at) = search.find(&pat) {
        let after_key = &search[at + pat.len()..];
        let colon = after_key.find(':')?;
        let mut rest = after_key[colon + 1..].trim_start();
        if !rest.starts_with('"') {
            search = &search[at + pat.len()..];
            continue;
        }
        rest = &rest[1..];
        let mut out = String::new();
        let mut chars = rest.chars();
        while let Some(c) = chars.next() {
            if c == '\\' {
                match chars.next() {
                    Some('"') => out.push('"'),
                    Some('\\') => out.push('\\'),
                    Some('/') => out.push('/'),
                    Some('n') => out.push('\n'),
                    Some('r') => out.push('\r'),
                    Some('t') => out.push('\t'),
                    Some('u') => {
                        let mut hex = String::new();
                        for _ in 0..4 {
                            match chars.next() {
                                Some(h) => hex.push(h),
                                None => break,
                            }
                        }
                        if let Ok(v) = u32::from_str_radix(&hex, 16) {
                            if let Some(ch) = char::from_u32(v) {
                                out.push(ch);
                            }
                        }
                    }
                    Some(other) => out.push(other),
                    None => break,
                }
            } else if c == '"' {
                return Some(out);
            } else {
                out.push(c);
            }
        }
        return None;
    }
    None
}

fn json_u64(json: &str, key: &str) -> Option<u64> {
    let pat = format!("\"{key}\"");
    let at = json.find(&pat)?;
    let after = &json[at + pat.len()..];
    let colon = after.find(':')?;
    let digits: String = after[colon + 1..]
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    if digits.is_empty() { None } else { digits.parse().ok() }
}

fn json_escape(text: &str) -> String {
    let mut out = String::new();
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn image_size(bytes: &[u8]) -> (u32, u32) {
    if bytes.len() >= 24 && bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        let w = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
        let h = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
        return (w, h);
    }
    if bytes.len() >= 10 && (bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a")) {
        let w = u16::from_le_bytes([bytes[6], bytes[7]]) as u32;
        let h = u16::from_le_bytes([bytes[8], bytes[9]]) as u32;
        return (w, h);
    }
    (0, 0)
}

fn mime_of(path: &Path, kind: &str) -> String {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    match (kind, ext.as_str()) {
        ("gif", _) => "image/gif".into(),
        (_, "png") => "image/png".into(),
        (_, "jpg" | "jpeg") => "image/jpeg".into(),
        (_, "gif") => "image/gif".into(),
        (_, "webp") => "image/webp".into(),
        (_, "mp4") => "video/mp4".into(),
        (_, "pdf") => "application/pdf".into(),
        (_, "txt") => "text/plain".into(),
        _ => "application/octet-stream".into(),
    }
}

fn safe_name(name: &str) -> String {
    let mut out = String::new();
    for c in name.chars() {
        if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' {
            out.push(c);
        } else {
            out.push('_');
        }
        if out.len() >= 80 {
            break;
        }
    }
    let trimmed = out.trim_matches('.').trim_matches('_');
    if trimmed.is_empty() {
        "file.bin".into()
    } else {
        trimmed.to_string()
    }
}

fn safe_token(id: &str) -> String {
    let hex: String = id.chars().filter(|c| c.is_ascii_hexdigit()).take(32).collect();
    if hex.is_empty() { "msg".into() } else { hex }
}

fn random_hex(nbytes: usize) -> String {
    let mut bytes = vec![0u8; nbytes];
    let mut filled = false;
    if let Ok(mut file) = File::open("/dev/urandom") {
        filled = file.read_exact(&mut bytes).is_ok();
    }
    if !filled {
        let mut state = now_ms()
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            .wrapping_add(std::process::id() as u64);
        for byte in &mut bytes {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            *byte = (state >> 33) as u8;
        }
    }
    hex(&bytes)
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0f) as usize] as char);
    }
    out
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| u64::try_from(d.as_millis()).unwrap_or(0))
        .unwrap_or(0)
}
