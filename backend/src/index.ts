export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  PROXY_SELLER_API_KEY: string;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function isAdmin(request: Request, env: Env): boolean {
  const supplied = request.headers.get("authorization") || "";
  const expected = `Bearer ${env.ADMIN_TOKEN || ""}`;
  return Boolean(env.ADMIN_TOKEN) && safeEqual(supplied, expected);
}

async function sellerGet(env: Env, endpoint: string): Promise<any> {
  const key = env.PROXY_SELLER_API_KEY;
  if (!key) throw new Error("PROXY_SELLER_API_KEY is not configured");
  const response = await fetch(
    `https://proxy-seller.com/personal/api/v2/${encodeURIComponent(key)}/${endpoint}`,
    { method: "GET", headers: { accept: "application/json" } }
  );
  const body: any = await response.json().catch(() => null);
  if (!response.ok || !body || body.status !== "success" || (body.errors || []).length) {
    const messages = Array.isArray(body?.errors)
      ? body.errors.map((item: any) => String(item?.message || "Supplier API error"))
      : [];
    throw new Error(messages.join("; ") || `Proxy-Seller HTTP ${response.status}`);
  }
  return body.data;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "BODEX Proxy API", stage: "supplier-readonly" });
    }

    if (url.pathname.startsWith("/admin/")) {
      if (!isAdmin(request, env)) return json({ error: "Unauthorized" }, 401);
      if (request.method !== "GET") return json({ error: "Method not allowed" }, 405);

      if (url.pathname === "/admin/seller/status") {
        if (!env.PROXY_SELLER_API_KEY) {
          return json({ error: "Proxy-Seller API key is not configured on the server" }, 503);
        }
        try {
          const [balance, resident] = await Promise.all([
            sellerGet(env, "balance/get"),
            sellerGet(env, "resident/package").catch(() => null)
          ]);
          return json({
            ok: true,
            supplier: "Proxy-Seller",
            balanceUsd: Number(balance?.summ ?? 0),
            residential: resident ? {
              active: Boolean(resident.is_active),
              trafficLimitBytes: Number(resident.traffic_limit ?? 0),
              trafficUsedBytes: Number(resident.traffic_usage ?? 0),
              trafficLeftBytes: Number(resident.traffic_left ?? 0),
              expiresAt: resident.expired_at?.date || resident.expired_at || null
            } : null
          });
        } catch (error) {
          return json({
            ok: false,
            error: error instanceof Error ? error.message : "Supplier API request failed"
          }, 502);
        }
      }
      return json({ error: "Not found" }, 404);
    }

    return json({ error: "Not found" }, 404);
  }
};
