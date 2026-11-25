export interface Env {
  DB: D1Database;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" }
  });

export default {
  async fetch(request: Request, _env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "BODEX Proxy API", stage: "scaffold" });
    }
    return json({
      error: "Not implemented",
      message: "Authentication, payment verification, supplier orders, and usage endpoints are not implemented yet."
    }, 501);
  }
};
