//! Betweenly core: pairing code, PAKE key agreement, message encryption.
//!
//! DATA-FLOW: this crate does NO networking and NO disk I/O. It only turns
//! bytes into other bytes. The Android app moves those bytes to/from the relay.
//!
//! Flow:
//!  1. Device A calls `generate_code()` and shows it. A tells B the code out-of-band.
//!  2. Both run SPAKE2 with the code as password (`Pairing`). The relay only
//!     ever sees SPAKE2 messages, from which the code/key cannot be derived
//!     (a wrong guess gives one online attempt, then the code is burned).
//!  3. Both derive `Session` (mailbox ids + encryption key) via HKDF.
//!  4. All later data (location, battery, ring, avatar) is sealed with
//!     XChaCha20-Poly1305 using that key.

#[cfg(feature = "jni-api")]
pub mod jni_api;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::Sha256;
use spake2::{Ed25519Group, Identity, Password, Spake2};
use zeroize::Zeroize;

/// Unambiguous alphabet (no 0/O/1/I): 32 symbols, so a random byte maps evenly (256 % 32 == 0).
const ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
pub const CODE_LEN: usize = 8;
/// Domain separation for SPAKE2 and HKDF. Both phones must run this build.
const APP_ID: &[u8] = b"betweenly-v1";

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    BadCode,
    Pake,
    Crypto,
    Replay,
    Malformed,
    Random,
}

/// 8 random characters, ~40 bits. Single-use + short expiry enforced by the relay.
pub fn generate_code() -> Result<String, Error> {
    let mut b = [0u8; CODE_LEN];
    getrandom::getrandom(&mut b).map_err(|_| Error::Random)?;
    Ok(b.iter().map(|x| ALPHABET[(*x & 31) as usize] as char).collect())
}

/// Accepts lowercase / spaces / dashes from the user; returns canonical code.
pub fn normalize_code(input: &str) -> Result<String, Error> {
    let c: String = input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect();
    if c.len() == CODE_LEN && c.bytes().all(|b| ALPHABET.contains(&b)) {
        Ok(c)
    } else {
        Err(Error::BadCode)
    }
}

/// Public rendezvous id the relay uses to match the two devices. Derived from the
/// code with a one-way hash, so the relay never sees the code itself.
pub fn rendezvous_id(code: &str) -> Result<[u8; 16], Error> {
    let code = normalize_code(code)?;
    let hk = Hkdf::<Sha256>::new(Some(APP_ID), code.as_bytes());
    let mut out = [0u8; 16];
    hk.expand(b"rendezvous", &mut out).map_err(|_| Error::Crypto)?;
    Ok(out)
}

/// 6-digit number derived from the session key, shown on both phones to compare.
fn safety_number(key: &[u8; 32]) -> Result<String, Error> {
    let hk = Hkdf::<Sha256>::new(Some(APP_ID), key);
    let mut sn = [0u8; 4];
    hk.expand(b"safety", &mut sn).map_err(|_| Error::Crypto)?;
    let n = u32::from_be_bytes(sn) % 1_000_000;
    Ok(format!("{:03} {:03}", n / 1000, n % 1000))
}

/// In-progress pairing handshake (one per device).
pub struct Pairing {
    state: Spake2<Ed25519Group>,
}

impl Pairing {
    /// Returns the handshake and the message to send to the other device via the relay.
    pub fn start(code: &str) -> Result<(Self, Vec<u8>), Error> {
        let code = normalize_code(code)?;
        let (state, msg) = Spake2::<Ed25519Group>::start_symmetric(
            &Password::new(code.as_bytes()),
            &Identity::new(APP_ID),
        );
        Ok((Self { state }, msg))
    }

    /// Finish with the other device's message.
    pub fn finish(self, their_msg: &[u8]) -> Result<Session, Error> {
        let mut key = self.state.finish(their_msg).map_err(|_| Error::Pake)?;
        let s = Session::from_shared_secret(&key);
        key.zeroize();
        s
    }
}

/// Encrypts a whole file with a fresh random one-time key. Returns (key, nonce||ciphertext).
/// The key travels to the partner inside an end-to-end encrypted chat message; the
/// relay only ever stores the ciphertext.
pub fn file_encrypt(plain: &[u8]) -> Result<([u8; 32], Vec<u8>), Error> {
    let mut key = [0u8; 32];
    getrandom::getrandom(&mut key).map_err(|_| Error::Random)?;
    let mut nonce = [0u8; 24];
    getrandom::getrandom(&mut nonce).map_err(|_| Error::Random)?;
    let c = XChaCha20Poly1305::new((&key).into());
    let ct = c
        .encrypt(XNonce::from_slice(&nonce), Payload { msg: plain, aad: b"file" })
        .map_err(|_| Error::Crypto)?;
    let mut out = nonce.to_vec();
    out.extend_from_slice(&ct);
    Ok((key, out))
}

pub fn file_decrypt(key: &[u8], data: &[u8]) -> Result<Vec<u8>, Error> {
    if key.len() != 32 || data.len() < 24 + 16 {
        return Err(Error::Malformed);
    }
    let (nonce, ct) = data.split_at(24);
    let c = XChaCha20Poly1305::new(key.into());
    c.decrypt(XNonce::from_slice(nonce), Payload { msg: ct, aad: b"file" })
        .map_err(|_| Error::Crypto)
}

/// Long-lived pair state. Persist `to_bytes()` in Android Keystore-wrapped storage.
pub struct Session {
    key: [u8; 32],
    /// Where I read from / write to on the relay (random-looking, derived).
    pub inbox_id: [u8; 16],
    pub outbox_id: [u8; 16],
    /// Short numbers both people can compare aloud to detect a man-in-the-middle.
    pub safety_number: String,
    send_counter: u64,
    recv_counter: u64,
}

impl Session {
    fn from_shared_secret(secret: &[u8]) -> Result<Self, Error> {
        let hk = Hkdf::<Sha256>::new(Some(APP_ID), secret);
        let mut key = [0u8; 32];
        hk.expand(b"enc-key", &mut key).map_err(|_| Error::Crypto)?;
        let mut a = [0u8; 16];
        let mut b = [0u8; 16];
        hk.expand(b"mailbox-a", &mut a).map_err(|_| Error::Crypto)?;
        hk.expand(b"mailbox-b", &mut b).map_err(|_| Error::Crypto)?;
        // Both sides compute the same two mailboxes; roles are assigned in `with_role`.
        Ok(Self {
            key,
            inbox_id: a,
            outbox_id: b,
            safety_number: safety_number(&key)?,
            send_counter: 0,
            recv_counter: 0,
        })
    }

    /// Roles must differ: the device that *showed* the code is `initiator = true`.
    /// Initiator writes to mailbox-b/reads mailbox-a; the other is the reverse.
    pub fn with_role(mut self, initiator: bool) -> Self {
        if !initiator {
            core::mem::swap(&mut self.inbox_id, &mut self.outbox_id);
        }
        self
    }

    /// Encrypt `plain`. Output = 24-byte nonce || ciphertext. The counter is
    /// authenticated so replayed/reordered old messages are rejected.
    pub fn seal(&mut self, plain: &[u8]) -> Result<Vec<u8>, Error> {
        self.send_counter += 1;
        let ctr = self.send_counter.to_be_bytes();
        let mut nonce = [0u8; 24];
        getrandom::getrandom(&mut nonce).map_err(|_| Error::Random)?;
        let c = XChaCha20Poly1305::new((&self.key).into());
        let ct = c
            .encrypt(XNonce::from_slice(&nonce), Payload { msg: plain, aad: &ctr })
            .map_err(|_| Error::Crypto)?;
        let mut out = Vec::with_capacity(24 + 8 + ct.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ctr);
        out.extend_from_slice(&ct);
        Ok(out)
    }

    /// Decrypt a message from the other device. Rejects tampering and replays.
    pub fn open(&mut self, data: &[u8]) -> Result<Vec<u8>, Error> {
        if data.len() < 24 + 8 + 16 {
            return Err(Error::Malformed);
        }
        let (nonce, rest) = data.split_at(24);
        let (ctr, ct) = rest.split_at(8);
        let n = u64::from_be_bytes(ctr.try_into().map_err(|_| Error::Malformed)?);
        if n <= self.recv_counter {
            return Err(Error::Replay);
        }
        let c = XChaCha20Poly1305::new((&self.key).into());
        let pt = c
            .decrypt(XNonce::from_slice(nonce), Payload { msg: ct, aad: ctr })
            .map_err(|_| Error::Crypto)?;
        self.recv_counter = n;
        Ok(pt)
    }

    /// Serialize for secure storage: key | send | recv | inbox | outbox.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(32 + 16 + 32);
        v.extend_from_slice(&self.key);
        v.extend_from_slice(&self.send_counter.to_be_bytes());
        v.extend_from_slice(&self.recv_counter.to_be_bytes());
        v.extend_from_slice(&self.inbox_id);
        v.extend_from_slice(&self.outbox_id);
        v
    }

    pub fn from_bytes(b: &[u8]) -> Result<Self, Error> {
        if b.len() != 32 + 8 + 8 + 16 + 16 {
            return Err(Error::Malformed);
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&b[..32]);
        let send = u64::from_be_bytes(b[32..40].try_into().unwrap());
        let recv = u64::from_be_bytes(b[40..48].try_into().unwrap());
        let mut inbox = [0u8; 16];
        let mut outbox = [0u8; 16];
        inbox.copy_from_slice(&b[48..64]);
        outbox.copy_from_slice(&b[64..80]);
        Ok(Self {
            key,
            inbox_id: inbox,
            outbox_id: outbox,
            safety_number: safety_number(&key)?,
            send_counter: send,
            recv_counter: recv,
        })
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(code_a: &str, code_b: &str) -> Result<(Session, Session), Error> {
        let (pa, ma) = Pairing::start(code_a)?;
        let (pb, mb) = Pairing::start(code_b)?;
        Ok((pa.finish(&mb)?.with_role(true), pb.finish(&ma)?.with_role(false)))
    }

    #[test]
    fn code_shape() {
        let c = generate_code().unwrap();
        assert_eq!(c.len(), 8);
        assert!(normalize_code(&c).is_ok());
        assert_eq!(normalize_code("abcd efg2").unwrap(), "ABCDEFG2");
        assert!(normalize_code("ABC").is_err());
    }

    #[test]
    fn roundtrip_and_mailboxes() {
        let (mut a, mut b) = pair("ABCD2345", "abcd-2345").unwrap();
        assert_eq!(a.outbox_id, b.inbox_id);
        assert_eq!(a.inbox_id, b.outbox_id);
        assert_ne!(a.inbox_id, a.outbox_id);
        assert_eq!(a.safety_number, b.safety_number);
        let m = a.seal(b"lat,lon,battery").unwrap();
        assert_eq!(b.open(&m).unwrap(), b"lat,lon,battery");
    }

    #[test]
    fn wrong_code_cannot_read() {
        let (mut a, mut b) = pair("ABCD2345", "ZZZZ2345").unwrap();
        assert_ne!(a.safety_number, b.safety_number);
        let m = a.seal(b"secret").unwrap();
        assert_eq!(b.open(&m), Err(Error::Crypto));
    }

    #[test]
    fn replay_and_tamper_rejected() {
        let (mut a, mut b) = pair("ABCD2345", "ABCD2345").unwrap();
        let m = a.seal(b"x").unwrap();
        assert!(b.open(&m).is_ok());
        assert_eq!(b.open(&m), Err(Error::Replay));
        let mut m2 = a.seal(b"y").unwrap();
        let last = m2.len() - 1;
        m2[last] ^= 1;
        assert_eq!(b.open(&m2), Err(Error::Crypto));
    }

    #[test]
    fn file_roundtrip() {
        let (k, ct) = file_encrypt(b"photo bytes").unwrap();
        assert_eq!(file_decrypt(&k, &ct).unwrap(), b"photo bytes");
        let mut bad = ct.clone();
        let l = bad.len() - 1;
        bad[l] ^= 1;
        assert_eq!(file_decrypt(&k, &bad), Err(Error::Crypto));
    }

    #[test]
    fn persist() {
        let (mut a, mut b) = pair("ABCD2345", "ABCD2345").unwrap();
        let mut a2 = Session::from_bytes(&a.to_bytes()).unwrap();
        let m = a2.seal(b"hi").unwrap();
        assert_eq!(b.open(&m).unwrap(), b"hi");
        let _ = &mut a;
    }
}
