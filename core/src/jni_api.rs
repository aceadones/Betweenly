//! JNI bridge: the ONLY glue between Kotlin and the Rust core.
//! DATA-FLOW: no network or disk here. Bytes in, bytes out.
//! Session state is passed in/out as bytes so Kotlin can persist it (Keystore-wrapped).
use crate::{generate_code, normalize_code, rendezvous_id, Pairing, Session};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::Mutex;

static PENDING: Mutex<Option<HashMap<i64, Pairing>>> = Mutex::new(None);
static NEXT: Mutex<i64> = Mutex::new(1);

const SESSION_LEN: usize = 80;

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

fn jstr<'l>(env: &mut JNIEnv<'l>, s: &str) -> JString<'l> {
    env.new_string(s).unwrap_or_default()
}

fn empty<'l>(env: &mut JNIEnv<'l>) -> JByteArray<'l> {
    env.byte_array_from_slice(&[]).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_generateCode<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> JString<'l> {
    let c = generate_code().unwrap_or_default();
    jstr(&mut env, &c)
}

/// Canonical code, or "" if invalid.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_normalizeCode<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, code: JString<'l>) -> JString<'l> {
    let s: String = env.get_string(&code).map(|s| s.into()).unwrap_or_default();
    let out = normalize_code(&s).unwrap_or_default();
    jstr(&mut env, &out)
}

/// 32-hex rendezvous id for the relay, or "" if code invalid.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_rendezvousHex<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, code: JString<'l>) -> JString<'l> {
    let s: String = env.get_string(&code).map(|s| s.into()).unwrap_or_default();
    let out = rendezvous_id(&s).map(|b| hex(&b)).unwrap_or_default();
    jstr(&mut env, &out)
}

/// Returns 8-byte big-endian handle followed by the PAKE message. Empty on error.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_pairStart<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, code: JString<'l>) -> JByteArray<'l> {
    let s: String = env.get_string(&code).map(|s| s.into()).unwrap_or_default();
    match Pairing::start(&s) {
        Ok((p, msg)) => {
            let mut n = NEXT.lock().unwrap();
            let h = *n;
            *n += 1;
            PENDING.lock().unwrap().get_or_insert_with(HashMap::new).insert(h, p);
            let mut out = h.to_be_bytes().to_vec();
            out.extend_from_slice(&msg);
            env.byte_array_from_slice(&out).unwrap_or_default()
        }
        Err(_) => empty(&mut env),
    }
}

/// Returns session bytes (80) or empty on failure.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_pairFinish<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, handle: jlong, theirs: JByteArray<'l>, initiator: jboolean) -> JByteArray<'l> {
    let msg = env.convert_byte_array(&theirs).unwrap_or_default();
    let p = PENDING.lock().unwrap().as_mut().and_then(|m| m.remove(&handle));
    match p.and_then(|p| p.finish(&msg).ok()) {
        Some(s) => {
            let s = s.with_role(initiator != 0);
            env.byte_array_from_slice(&s.to_bytes()).unwrap_or_default()
        }
        None => empty(&mut env),
    }
}

/// Returns new session bytes (80) followed by ciphertext. Empty on error.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_seal<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, session: JByteArray<'l>, plain: JByteArray<'l>) -> JByteArray<'l> {
    let sb = env.convert_byte_array(&session).unwrap_or_default();
    let pt = env.convert_byte_array(&plain).unwrap_or_default();
    let r = Session::from_bytes(&sb).and_then(|mut s| s.seal(&pt).map(|ct| (s.to_bytes(), ct)));
    match r {
        Ok((mut sess, ct)) => {
            sess.extend_from_slice(&ct);
            env.byte_array_from_slice(&sess).unwrap_or_default()
        }
        Err(_) => empty(&mut env),
    }
}

/// Returns new session bytes (80) followed by plaintext. Empty on error (tamper/replay).
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_open<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, session: JByteArray<'l>, data: JByteArray<'l>) -> JByteArray<'l> {
    let sb = env.convert_byte_array(&session).unwrap_or_default();
    let ct = env.convert_byte_array(&data).unwrap_or_default();
    let r = Session::from_bytes(&sb).and_then(|mut s| s.open(&ct).map(|pt| (s.to_bytes(), pt)));
    match r {
        Ok((mut sess, pt)) => {
            sess.extend_from_slice(&pt);
            env.byte_array_from_slice(&sess).unwrap_or_default()
        }
        Err(_) => empty(&mut env),
    }
}

/// Which = 0 safety number, 1 inbox hex, 2 outbox hex.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_sessionInfo<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, session: JByteArray<'l>, which: jlong) -> JString<'l> {
    let sb = env.convert_byte_array(&session).unwrap_or_default();
    let out = match Session::from_bytes(&sb) {
        Ok(s) => match which {
            0 => s.safety_number.clone(),
            1 => hex(&s.inbox_id),
            _ => hex(&s.outbox_id),
        },
        Err(_) => String::new(),
    };
    let _ = SESSION_LEN;
    jstr(&mut env, &out)
}

/// Returns 32-byte key followed by (nonce||ciphertext). Empty on error.
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_fileEncrypt<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, plain: JByteArray<'l>) -> JByteArray<'l> {
    let pt = env.convert_byte_array(&plain).unwrap_or_default();
    match crate::file_encrypt(&pt) {
        Ok((k, ct)) => {
            let mut out = k.to_vec();
            out.extend_from_slice(&ct);
            env.byte_array_from_slice(&out).unwrap_or_default()
        }
        Err(_) => empty(&mut env),
    }
}

/// Empty on error (wrong key / tampered).
#[no_mangle]
pub extern "system" fn Java_app_loviedowpie_Core_fileDecrypt<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, key: JByteArray<'l>, data: JByteArray<'l>) -> JByteArray<'l> {
    let k = env.convert_byte_array(&key).unwrap_or_default();
    let d = env.convert_byte_array(&data).unwrap_or_default();
    match crate::file_decrypt(&k, &d) {
        Ok(pt) => env.byte_array_from_slice(&pt).unwrap_or_default(),
        Err(_) => empty(&mut env),
    }
}
