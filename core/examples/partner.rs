//! Test-only desktop "partner" used to exercise the real relay + the Android app.
//! Usage: partner <relay-url> <CODE>
//! Joins as role "b", prints the safety number, sends a profile + location message,
//! then prints what it receives from the phone for ~60 seconds.
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use loviedowpie_core::*;
use std::process::Command;
use std::{thread::sleep, time::Duration};

fn curl(args: &[&str], body: Option<&[u8]>) -> String {
    let mut c = Command::new("curl");
    c.arg("-sS").arg("--max-time").arg("20").args(args);
    if let Some(b) = body {
        use std::io::Write;
        use std::process::Stdio;
        let mut ch = c.arg("--data-binary").arg("@-").stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
        ch.stdin.take().unwrap().write_all(b).unwrap();
        return String::from_utf8_lossy(&ch.wait_with_output().unwrap().stdout).to_string();
    }
    String::from_utf8_lossy(&c.output().unwrap().stdout).to_string()
}
fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{:02x}", x)).collect() }
fn pad(j: &str) -> Vec<u8> {
    let mut v = (j.len() as u16).to_be_bytes().to_vec();
    v.extend_from_slice(j.as_bytes());
    while v.len() % 512 != 0 { v.push(0); }
    v
}
fn unpad(p: &[u8]) -> String {
    let n = u16::from_be_bytes([p[0], p[1]]) as usize;
    String::from_utf8_lossy(&p[2..2 + n]).to_string()
}
fn between<'a>(s: &'a str, key: &str) -> Option<&'a str> {
    let i = s.find(key)? + key.len();
    let j = s[i..].find('"')?;
    Some(&s[i..i + j])
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (relay, code) = (a[1].as_str(), a[2].as_str());
    let rv = hex(&rendezvous_id(code).unwrap());
    let (p, mine) = Pairing::start(code).unwrap();
    curl(&["-X", "PUT", &format!("{relay}/pair/{rv}/b")], Some(B64.encode(&mine).as_bytes()));
    let mut theirs = None;
    for _ in 0..60 {
        let r = curl(&[&format!("{relay}/pair/{rv}/b")], None);
        if let Some(m) = between(&r, "\"msg\":\"") { theirs = Some(B64.decode(m).unwrap()); break; }
        sleep(Duration::from_secs(2));
    }
    let mut s = p.finish(&theirs.expect("no initiator msg")).unwrap().with_role(false);
    println!("PAIRED. safety number = {}", s.safety_number);
    let inbox = hex(&s.inbox_id);
    let outbox = hex(&s.outbox_id);
    let send = |s: &mut Session, j: &str, wake: &str| {
        let ct = s.seal(&pad(j)).unwrap();
        println!("sent: {}", curl(&["-X", "POST", &format!("{relay}/box/{outbox}{wake}")], Some(&ct)));
    };
    send(&mut s, r#"{"t":"profile","name":"Bob (desktop)","avatar":""}"#, "");
    send(&mut s, r#"{"t":"loc","lat":14.5995,"lon":120.9842,"acc":20.0,"bat":77,"chg":false,"ts":1764000000000,"live":false}"#, "");
    for _ in 0..150 {
        let r = curl(&[&format!("{relay}/box/{inbox}")], None);
        let mut rest = r.as_str();
        while let Some(i) = rest.find("\"") .and_then(|_| rest.find("\"blobs\":[")) {
            let start = i + 9;
            let arr = &rest[start..rest[start..].find(']').map(|e| start + e).unwrap_or(rest.len())];
            for item in arr.split(',').filter(|x| x.len() > 2) {
                let b = B64.decode(item.trim_matches('"')).unwrap();
                match s.open(&b) { Ok(pt) => println!("RECEIVED: {}", unpad(&pt)), Err(e) => println!("open failed: {:?}", e) }
            }
            break;
        }
        sleep(Duration::from_secs(2));
    }
}
