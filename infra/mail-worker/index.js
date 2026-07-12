// tomoshibi-mail — Cloudflare Email Worker for tomoshibi@etzhayyim.com.
//
// Two halves, mirroring cloud-itonami's mail-inbound Worker (the proven
// pattern in this corpus) plus an authed pull API so the resident agent on
// the Murakumo fleet can drain WITHOUT wrangler credentials on the node:
//
//   email() — Email Routing hands us each inbound message; we parse
//             (postal-mime), stage a JSON record in KV under "inbox:<ts>-…"
//             and do nothing else. Staging-only: the JVM/bb side is the only
//             thing that decides, replies, or attests — a bad parse here can
//             never corrupt the actor's ledgers.
//
//   fetch() — Bearer-authed (PULL_TOKEN secret) pull API for the agent:
//             GET  /inbox?limit=N  → oldest-first staged records
//             POST /ack {key}      → delete one processed record
//
// KV entries carry a 60-day TTL: an unreachable agent degrades to "mail
// expires unanswered", never "KV grows forever".
import PostalMime from "postal-mime";

const MAX_BODY_CHARS = 65536;

function authResult(rawHeaders, mechanism) {
  const header = rawHeaders.get("authentication-results") || "";
  const match = header.match(new RegExp(mechanism + "=(\\w+)", "i"));
  return match ? match[1].toLowerCase() : "none";
}

function truncate(s) {
  if (typeof s !== "string") return null;
  return s.length > MAX_BODY_CHARS ? s.slice(0, MAX_BODY_CHARS) : s;
}

export default {
  async email(message, env, ctx) {
    const bytes = await new Response(message.raw).arrayBuffer();
    const parsed = await PostalMime.parse(bytes);

    const providerMessageId =
      parsed.messageId ||
      message.headers.get("message-id") ||
      `<${crypto.randomUUID()}@tomoshibi-mail.etzhayyim>`;

    const record = {
      provider: "cloudflare-email-routing",
      provider_message_id: providerMessageId,
      from: message.from,
      to: [message.to],
      cc: (parsed.cc || []).map((a) => a.address).filter(Boolean),
      subject: parsed.subject || "(no subject)",
      text: truncate(parsed.text),
      html: truncate(parsed.html),
      headers: Object.fromEntries(message.headers),
      received_at: new Date().toISOString(),
      spf: authResult(message.headers, "spf"),
      dkim: authResult(message.headers, "dkim"),
      dmarc: authResult(message.headers, "dmarc"),
      attachments: (parsed.attachments || []).map((a) => ({
        filename: a.filename,
        mimeType: a.mimeType,
        size: a.content ? a.content.byteLength : 0,
      })),
    };

    // Zero-padded epoch-ms prefix so KV's lexicographic list = oldest-first.
    const key = `inbox:${String(Date.now()).padStart(15, "0")}-${crypto
      .randomUUID()
      .slice(0, 8)}`;
    await env.TOMOSHIBI_INBOX.put(key, JSON.stringify(record), {
      expirationTtl: 60 * 24 * 60 * 60,
    });
  },

  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return Response.json({ ok: true, worker: "tomoshibi-mail" });
    }

    const auth = request.headers.get("authorization") || "";
    if (!env.PULL_TOKEN || auth !== `Bearer ${env.PULL_TOKEN}`) {
      return Response.json({ ok: false, error: "unauthorized" }, { status: 401 });
    }

    if (request.method === "GET" && url.pathname === "/inbox") {
      const limit = Math.min(
        Math.max(parseInt(url.searchParams.get("limit") || "5", 10) || 5, 1),
        25
      );
      const list = await env.TOMOSHIBI_INBOX.list({ prefix: "inbox:", limit });
      const messages = [];
      for (const k of list.keys) {
        const value = await env.TOMOSHIBI_INBOX.get(k.name, "json");
        if (value) messages.push({ key: k.name, value });
      }
      return Response.json({ ok: true, messages });
    }

    if (request.method === "POST" && url.pathname === "/ack") {
      let body;
      try {
        body = await request.json();
      } catch {
        return Response.json({ ok: false, error: "bad json" }, { status: 400 });
      }
      const key = body && body.key;
      if (typeof key !== "string" || !key.startsWith("inbox:")) {
        return Response.json({ ok: false, error: "bad key" }, { status: 400 });
      }
      await env.TOMOSHIBI_INBOX.delete(key);
      return Response.json({ ok: true });
    }

    return Response.json({ ok: false, error: "not found" }, { status: 404 });
  },
};
