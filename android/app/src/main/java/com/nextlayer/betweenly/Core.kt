package com.nextlayer.betweenly

/**
 * JNI bridge to the Rust core (core/src/jni_api.rs). All crypto lives in Rust.
 * Session state is a 80-byte blob; seal/open return `newSession(80) + payload`.
 * DATA-FLOW: pure computation; no network, no disk.
 */
object Core {
    init { System.loadLibrary("betweenly_core") }

    const val SESSION_LEN = 80

    external fun generateCode(): String
    /** Canonical code or "" if invalid. */
    external fun normalizeCode(code: String): String
    /** 32-hex relay rendezvous id derived (one-way) from the code, or "". */
    external fun rendezvousHex(code: String): String
    /** 8-byte big-endian handle + PAKE message; empty on error. */
    external fun pairStart(code: String): ByteArray
    /** New session bytes (80) or empty on failure. Initiator = the device that showed the code. */
    external fun pairFinish(handle: Long, theirs: ByteArray, initiator: Boolean): ByteArray
    external fun seal(session: ByteArray, plain: ByteArray): ByteArray
    external fun open(session: ByteArray, data: ByteArray): ByteArray
    /** which: 0 = safety number "123 456", 1 = my inbox id (hex), 2 = my outbox id (hex). */
    external fun sessionInfo(session: ByteArray, which: Long): String

    /** One-time file encryption: returns 32-byte key + (nonce||ciphertext); empty on error. */
    external fun fileEncrypt(plain: ByteArray): ByteArray
    /** Decrypts what fileEncrypt produced; empty on error. */
    external fun fileDecrypt(key: ByteArray, data: ByteArray): ByteArray
}
