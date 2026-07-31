// ===========================================================================
// Karur SDO — FCM push Edge Function (Deno).
// Triggered by a Supabase Database Webhook on INSERT into app_messages and
// app_programmes. Sends an FCM v1 push to every registered device except the
// author's, so new chat messages / MO programmes arrive instantly even when the
// app is closed.
//
// Secrets required (set with `supabase secrets set`):
//   FCM_SERVICE_ACCOUNT  = the full Firebase service-account JSON (one line)
// Automatically available inside the function:
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
//
// Deploy:  supabase functions deploy push-notify --no-verify-jwt
// ===========================================================================

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const serviceAccount = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT") ?? "{}");
const PROJECT_ID = serviceAccount.project_id as string;

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

// ---- Google OAuth2 access token from the service account (RS256 JWT) ----

function b64url(bytes: Uint8Array): string {
  let s = btoa(String.fromCharCode(...bytes));
  return s.replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function b64urlJson(obj: unknown): string {
  return b64url(new TextEncoder().encode(JSON.stringify(obj)));
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return await crypto.subtle.importKey(
    "pkcs8",
    der.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

async function getAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claim = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64urlJson(header)}.${b64urlJson(claim)}`;
  const key = await importPrivateKey(serviceAccount.private_key);
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const jwt = `${unsigned}.${b64url(new Uint8Array(sig))}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body:
      `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  const json = await res.json();
  return json.access_token as string;
}

async function sendToToken(accessToken: string, token: string, data: Record<string, string>) {
  await fetch(
    `https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data,
          android: { priority: "high" },
        },
      }),
    },
  );
}

Deno.serve(async (req) => {
  try {
    const payload = await req.json();
    const table: string = payload.table ?? "";
    const record = payload.record ?? {};

    // Build the notification content from the inserted row.
    let data: Record<string, string>;
    if (table === "app_programmes") {
      data = {
        type: "programme",
        title: "New Mail Overseer programme",
        body: `${record.date ?? ""} — ${record.details ?? ""}`.trim(),
      };
    } else {
      data = {
        type: "chat",
        title: `New message · ${record.display_name ?? "Staff"}`,
        body: String(record.body ?? ""),
      };
    }
    const author: string | null = record.username ?? null;

    // Fetch all device tokens except the author's own devices.
    const { data: tokens } = await supabase
      .from("app_push_tokens")
      .select("token, username");
    const targets = (tokens ?? []).filter((t) => t.username !== author);
    if (targets.length === 0) return new Response("no targets", { status: 200 });

    const accessToken = await getAccessToken();
    await Promise.all(targets.map((t) => sendToToken(accessToken, t.token, data)));

    return new Response(`sent to ${targets.length}`, { status: 200 });
  } catch (e) {
    return new Response(`error: ${e}`, { status: 500 });
  }
});
