/**
 * Minimal Firebase Cloud Messaging (HTTP v1) client.
 *
 * Authenticates with a service-account JSON by minting a short-lived OAuth2
 * access token from a self-signed RS256 JWT. No SDK dependency.
 */

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

export interface PushData {
  [key: string]: string;
}

export type PushResult = { ok: true } | { ok: false; unregistered: boolean; error: string };

export class Fcm {
  private account: ServiceAccount;
  private key: Promise<CryptoKey>;
  private accessToken: { value: string; expiresAt: number } | null = null;

  constructor(account: ServiceAccount) {
    this.account = account;
    this.key = importPrivateKey(account.private_key);
  }

  static async fromFile(path: string): Promise<Fcm> {
    return Fcm.fromJson(await Bun.file(path).text(), path);
  }

  /** Parses a base64-encoded service-account JSON (handy for env vars on PaaS hosts). */
  static fromBase64(encoded: string): Fcm {
    return Fcm.fromJson(Buffer.from(encoded.trim(), "base64").toString("utf8"), "FIREBASE_SERVICE_ACCOUNT_B64");
  }

  static fromJson(text: string, label: string): Fcm {
    let account: ServiceAccount;
    try {
      account = JSON.parse(text) as ServiceAccount;
    } catch {
      throw new Error(`${label} is not valid JSON`);
    }
    if (!account.project_id || !account.client_email || !account.private_key) {
      throw new Error(`${label} is not a Firebase service-account JSON`);
    }
    return new Fcm(account);
  }

  get projectId(): string {
    return this.account.project_id;
  }

  /** Sends a high-priority data-only message; the app renders the notification itself. */
  async sendData(token: string, data: PushData): Promise<PushResult> {
    const accessToken = await this.getAccessToken();
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            data,
            android: { priority: "HIGH" },
          },
        }),
      },
    );
    if (res.ok) return { ok: true };
    const text = await res.text();
    const unregistered =
      res.status === 404 || text.includes("UNREGISTERED") || text.includes("registration-token-not-registered");
    return { ok: false, unregistered, error: `FCM ${res.status}: ${text}` };
  }

  private async getAccessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.accessToken && this.accessToken.expiresAt - 60 > now) return this.accessToken.value;

    const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = b64url(
      JSON.stringify({
        iss: this.account.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      }),
    );
    const unsigned = `${header}.${claims}`;
    const signature = await crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      await this.key,
      new TextEncoder().encode(unsigned),
    );
    const jwt = `${unsigned}.${b64url(new Uint8Array(signature))}`;

    const res = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: jwt,
      }),
    });
    if (!res.ok) throw new Error(`OAuth token exchange failed: ${res.status} ${await res.text()}`);
    const json = (await res.json()) as { access_token: string; expires_in: number };
    this.accessToken = { value: json.access_token, expiresAt: now + json.expires_in };
    return json.access_token;
  }
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const base64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

function b64url(input: string | Uint8Array): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  return Buffer.from(bytes).toString("base64url");
}
