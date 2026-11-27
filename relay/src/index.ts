/**
 * Betweenly relay. A dumb, blind mailbox.
 *
 * DATA-FLOW: this server stores ONLY
 *   - opaque encrypted blobs (it cannot decrypt them: keys exist only on the phones)
 *   - one FCM device token per mailbox (used to send a content-free wake-up ping)
 *   - short-lived pairing handshake messages (PAKE; reveal nothing about the code)
 * It has no accounts, no logs of content, no analytics. Everything expires (TTL).
 * Mailbox / rendezvous ids are 128-bit random-looking hex strings that act as
 * capabilities: only the two paired phones know them.
 *
 * Routes (all ids are 32 hex chars):
 *   PUT  /pair/{rv}/{role}      role = a|b   store my PAKE message (TTL 10 min)
 *   GET  /pair/{rv}/{role}                    fetch the other side's message
 *   POST /box/{mailbox}[?wake=1]              append blob; wake=1 also sends an FCM ping
 *   GET  /box/{mailbox}                       fetch and clear all blobs
 *   PUT  /token/{mailbox}                     register my FCM token for wake-ups
 *   PUT  /file/{id}                           upload an encrypted attachment (<= 24 MB, kept 7 days)
 *   GET  /file/{id}                           download it (id is a 128-bit secret known to the pair)
 *   GET  /update/latest                       public JSON about the newest app release
 *   GET  /update/apk                          public APK of the newest release
 * (Updates are published by the developer with `wrangler kv key put`; nothing user-specific.)
 */

import { DurableObject } from "cloudflare:workers";

export interface Env {
  STORE: KVNamespace;
  MAILBOX: DurableObjectNamespace<Mailbox>;
  FCM_SERVICE_ACCOUNT?: string;
}

const HEX32 = /^[0-9a-f]{32}$/;
const MAX_BLOB = 96 * 1024; // avatar (~64 KB) + overhead
const MAX_QUEUE = 20;
const MAX_FILE = 24 * 1024 * 1024; // KV value limit is 25 MiB
const FILE_TTL = 7 * 86400;
const PAIR_TTL = 600; // 10 min
const BOX_TTL = 86400; // 24 h
const PAIR_MAX_JOINS = 3; // the joiner (role b) may write at most 3 times: a wrong guess burns the code

const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));

const b64url = (u: Uint8Array) => b64(u).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname.split("/").filter(Boolean);
    const json = (o: unknown, s = 200) =>
      new Response(JSON.stringify(o), { status: s, headers: { "content-type": "application/json" } });

    try {
      // ---- pairing -------------------------------------------------------
      if (p[0] === "pair" && HEX32.test(p[1] ?? "") && (p[2] === "a" || p[2] === "b")) {
        const rv = p[1];
        const role = p[2];
        const other = role === "a" ? "b" : "a";
        if (req.method === "PUT") {
          const body = await req.text();
          if (body.length > 512) return json({ error: "too large" }, 413);
          if (role === "b") {
            // Online-guess limit: each attempt to join is one write as role b.
            const joins = Number((await env.STORE.get(`pairjoins:${rv}`)) ?? 0);
            if (joins >= PAIR_MAX_JOINS) return json({ error: "burned" }, 429);
            await env.STORE.put(`pairjoins:${rv}`, String(joins + 1), { expirationTtl: PAIR_TTL });
          }
          await env.STORE.put(`pair:${rv}:${role}`, body, { expirationTtl: PAIR_TTL });
          return json({ ok: true });
        }
        if (req.method === "GET") {
          const m = await env.STORE.get(`pair:${rv}:${other}`);
          return m === null ? json({ error: "not yet" }, 404) : json({ msg: m });
        }
      }

      // ---- mailbox (one Durable Object per mailbox id: strongly consistent queue) ----
      if (p[0] === "box" && HEX32.test(p[1] ?? "")) {
        const box = env.MAILBOX.get(env.MAILBOX.idFromName(p[1]));
        if (req.method === "POST") {
          const blob = await req.arrayBuffer();
          if (blob.byteLength === 0 || blob.byteLength > MAX_BLOB) return json({ error: "bad size" }, 413);
          await box.push(blob);
          // The wake-up ping goes out after the reply, so sending never waits for Google.
          const woke = url.searchParams.get("wake") === "1";
          if (woke) ctx.waitUntil(wake(env, p[1]).catch(() => false));
          return json({ ok: true, woke });
        }
        if (req.method === "GET") {
          return json({ blobs: await box.drain() });
        }
      }

      // ---- encrypted attachments ----------------------------------------
      // DATA-FLOW: opaque ciphertext only; the key is inside the end-to-end encrypted chat message.
      if (p[0] === "file" && HEX32.test(p[1] ?? "")) {
        if (req.method === "PUT") {
          const data = await req.arrayBuffer();
          if (data.byteLength === 0 || data.byteLength > MAX_FILE) return json({ error: "bad size" }, 413);
          if (await env.STORE.get(`file:${p[1]}`, "stream")) return json({ error: "exists" }, 409);
          await env.STORE.put(`file:${p[1]}`, data, { expirationTtl: FILE_TTL });
          return json({ ok: true });
        }
        if (req.method === "GET") {
          const f = await env.STORE.get(`file:${p[1]}`, "arrayBuffer");
          return f === null ? json({ error: "not found" }, 404) : new Response(f, { headers: { "content-type": "application/octet-stream" } });
        }
      }

      // ---- app updates (public, developer-published) -------------------
      if (p[0] === "update" && req.method === "GET") {
        if (p[1] === "latest") {
          const j = await env.STORE.get("upd:json");
          return j === null ? json({ error: "none" }, 404) : new Response(j, { headers: { "content-type": "application/json" } });
        }
        if (p[1] === "apk") {
          const a = await env.STORE.get("upd:apk", "arrayBuffer");
          return a === null
            ? json({ error: "none" }, 404)
            : new Response(a, { headers: { "content-type": "application/vnd.android.package-archive" } });
        }
      }

      // ---- FCM token -----------------------------------------------------
      if (p[0] === "token" && HEX32.test(p[1] ?? "") && req.method === "PUT") {
        const t = (await req.text()).trim();
        if (t.length < 20 || t.length > 400) return json({ error: "bad token" }, 400);
        await env.STORE.put(`tok:${p[1]}`, t, { expirationTtl: 60 * 86400 });
        return json({ ok: true });
      }
      return json({ error: "not found" }, 404);
    } catch {
      return json({ error: "server error" }, 500);
    }
  },
};

/** DATA-FLOW: sends an EMPTY high-priority data ping to Google FCM for the device that owns `mailbox`. */
async function wake(env: Env, mailbox: string): Promise<boolean> {
  if (!env.FCM_SERVICE_ACCOUNT) return false;
  const token = await env.STORE.get(`tok:${mailbox}`);
  if (!token) return false;
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const access = await accessToken(sa);
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: { token, android: { priority: "HIGH", ttl: "3600s" }, data: { w: "1" } },
    }),
  });
  return r.ok;
}

// A Google access token lasts an hour. Keep one per Worker instance instead of signing a new one per message.
let cachedAccess: { token: string; until: number } | null = null;

async function accessToken(sa: { client_email: string; private_key: string }): Promise<string> {
  if (cachedAccess && cachedAccess.until > Date.now() + 60_000) return cachedAccess.token;
  const enc = new TextEncoder();
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64url(
    enc.encode(
      JSON.stringify({
        iss: sa.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      }),
    ),
  );
  const der = Uint8Array.from(
    atob(sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${head}.${claim}`)),
  );
  const jwt = `${head}.${claim}.${b64url(sig)}`;
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  const tok = ((await r.json()) as { access_token: string; expires_in?: number });
  cachedAccess = { token: tok.access_token, until: Date.now() + (tok.expires_in ?? 3000) * 1000 };
  return tok.access_token;
}

/**
 * DATA-FLOW: holds up to 20 opaque encrypted blobs for one mailbox, deleted when read
 * or after 24 h. One Durable Object per mailbox makes concurrent writes safe.
 */
export class Mailbox extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS q (id INTEGER PRIMARY KEY AUTOINCREMENT, b BLOB NOT NULL)");
  }
  async push(blob: ArrayBuffer): Promise<void> {
    const sql = this.ctx.storage.sql;
    sql.exec("INSERT INTO q (b) VALUES (?)", blob);
    sql.exec("DELETE FROM q WHERE id <= (SELECT MAX(id) FROM q) - ?", MAX_QUEUE);
    await this.ctx.storage.setAlarm(Date.now() + BOX_TTL * 1000);
  }
  async drain(): Promise<string[]> {
    const sql = this.ctx.storage.sql;
    const rows = sql.exec("SELECT b FROM q ORDER BY id").toArray();
    sql.exec("DELETE FROM q");
    return rows.map((r) => b64(new Uint8Array(r.b as ArrayBuffer)));
  }
  async alarm(): Promise<void> {
    this.ctx.storage.sql.exec("DELETE FROM q");
  }
}
