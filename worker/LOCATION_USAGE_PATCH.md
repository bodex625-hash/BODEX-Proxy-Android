# Worker patch — location switching and usage reporting

Apply to the Worker source pasted in the BODEX conversation. This file is a patch guide, not a deployed Worker. Do not deploy until tested against a staging Proxy-Seller package.

## A. Add helpers after ensureOrderGeoTable()

```js
async function ensureLocationSwitchTable(db) {
  await db.prepare(`CREATE TABLE IF NOT EXISTS location_switches (
    order_id TEXT PRIMARY KEY, switch_id TEXT, status TEXT NOT NULL,
    started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, error TEXT
  )`).run();
}
function providerItems(r) {
  if (Array.isArray(r?.data)) return r.data;
  if (Array.isArray(r?.data?.items)) return r.data.items;
  if (Array.isArray(r?.items)) return r.items;
  return [];
}
function packageUsage(p) {
  if (!p) return {traffic_limit_gb:null,traffic_usage_gb:null,traffic_left_gb:null,expired_at:null,provider_active:null};
  const gb = n => Number.isFinite(Number(n)) ? +(Number(n)/1073741824).toFixed(4) : null;
  return {
    traffic_limit_gb:gb(p.traffic_limit), traffic_usage_gb:gb(p.traffic_usage),
    traffic_left_gb:gb(p.traffic_left),
    expired_at:typeof p.expired_at==="object" ? (p.expired_at?.date || null) : (p.expired_at || null),
    provider_active:typeof p.is_active==="boolean" ? p.is_active : null
  };
}
async function changeCustomerLocation(env, customerId, b) {
  const db=env.DB;
  await ensureOrderGeoTable(db);
  await ensureLocationSwitchTable(db);
  const orderId=safeText(b.order_id,80), country=safeText(b.country,2).toUpperCase();
  const region=safeText(b.region,160), city=safeText(b.city,160), isp=safeText(b.isp,180);
  if (!orderId || !/^[A-Z]{2}$/.test(country) || !region || !city || !isp)
    throw new Error("أدخل الطلب والدولة والمنطقة والمدينة ومزوّد الإنترنت");
  const o=await db.prepare(`SELECT o.id,o.customer_id,o.status,o.country,p.payment_status,
    pr.status AS provisioning_status,pr.package_key,pr.provider_order_id
    FROM orders o JOIN payments p ON p.order_id=o.id
    JOIN provisioning pr ON pr.order_id=o.id
    WHERE o.id=? AND o.customer_id=?`).bind(orderId,customerId).first();
  if (!o) throw new Error("الطلب غير موجود أو لا يخص هذا الحساب");
  if (o.status!=="active" || o.payment_status!=="finished" ||
      o.provisioning_status!=="completed" || !o.package_key)
    throw new Error("تغيير الموقع متاح للطلبات النشطة والمدفوعة فقط");
  const tree=await getGeoTree(env), c=tree.find(x=>String(x.code).toUpperCase()===country);
  const r=(c?.regions||[]).find(x=>x.name===region);
  const ct=(r?.cities||[]).find(x=>x.name===city);
  if (!ct || !Array.isArray(ct.isps) || !ct.isps.includes(isp))
    throw new Error("الموقع غير متاح لدى Proxy-Seller");
  const switchId=crypto.randomUUID(), now=Math.floor(Date.now()/1000);
  const claim=await db.prepare(`INSERT INTO location_switches
    (order_id,switch_id,status,started_at,updated_at) VALUES (?,?,'creating',?,?)
    ON CONFLICT(order_id) DO UPDATE SET switch_id=excluded.switch_id,status='creating',
    started_at=excluded.started_at,updated_at=excluded.updated_at
    WHERE location_switches.status IN ('completed','failed')`)
    .bind(orderId,switchId,now,now).run();
  if (!claim.meta || claim.meta.changes!==1)
    throw new Error("يوجد تغيير موقع قيد التنفيذ؛ انتظر ثم أعد المحاولة");
  const title=`BODEX-${orderId}-LOC-${switchId}`, geo={country,region,city,isp};
  try {
    let list=null;
    try {
      const made=await proxyRequest(env,"residentsubuser/list/add","POST",{
        title,whitelist:"",geo,export:{ports:1,ext:"txt"},rotation:-1,package_key:o.package_key
      });
      list=made.data;
    } catch(postError) {
      // Ambiguous POST: read inventory only; never send the create POST twice.
      const inv=await proxyRequest(env,`residentsubuser/lists?package_key=${encodeURIComponent(o.package_key)}`);
      const found=providerItems(inv).filter(x=>x.title===title);
      if(found.length===1) list=found[0]; else throw postError;
    }
    if(!list?.id || !list?.login || !list?.password) {
      const inv=await proxyRequest(env,`residentsubuser/lists?package_key=${encodeURIComponent(o.package_key)}`);
      const found=providerItems(inv).filter(x=>x.title===title);
      if(found.length===1 && found[0].login && found[0].password) list=found[0];
    }
    if(!list?.id || !list?.login || !list?.password)
      throw new Error("تعذر التحقق من القائمة الجديدة؛ العملية تحتاج مراجعة");
    // Same package_key = same subscription traffic pool and expiry.
    await db.prepare(`UPDATE provisioning SET proxy_login=?,proxy_password=?,provider_order_id=?
      WHERE order_id=? AND package_key=?`)
      .bind(String(list.login),String(list.password),String(list.id),orderId,o.package_key).run();
    await db.prepare("UPDATE order_geo SET region=?,city=?,isp=? WHERE order_id=?")
      .bind(region,city,isp,orderId).run();
    await db.prepare("UPDATE orders SET country=? WHERE id=? AND customer_id=?")
      .bind(country,orderId,customerId).run();
    let cleanup="not_needed";
    if(o.provider_order_id && String(o.provider_order_id)!==String(list.id)) {
      try {
        await proxyRequest(env,"residentsubuser/list/delete","DELETE",
          {id:String(o.provider_order_id),package_key:o.package_key});
        cleanup="deleted";
      } catch { cleanup="pending"; }
    }
    await db.prepare("UPDATE location_switches SET status='completed',updated_at=?,error=NULL WHERE order_id=? AND switch_id=?")
      .bind(Math.floor(Date.now()/1000),orderId,switchId).run();
    return {success:true,order_id:orderId,geo,proxy_host:SOCKS_HOST,proxy_port:SOCKS_PORT,
      proxy_login:String(list.login),proxy_password:String(list.password),old_list_cleanup:cleanup};
  } catch(e) {
    await db.prepare("UPDATE location_switches SET status='failed',updated_at=?,error=? WHERE order_id=? AND switch_id=?")
      .bind(Math.floor(Date.now()/1000),safeText(e?.message||"switch failed",250),orderId,switchId).run();
    throw e;
  }
}
```

## B. Add route inside handleRequest(), before the final 404

```js
if (path === "/api/change-location" && request.method === "POST") {
  const customerId=await getCustomer(request,env);
  if (!customerId) return json({error:"سجّل الدخول أولاً"},401);
  let b; try { b=await request.json(); } catch { return json({error:"بيانات غير صحيحة"},400); }
  try { return json(await changeCustomerLocation(env,customerId,b)); }
  catch(e) { return json({error:safeText(e?.message||"تعذر تغيير الموقع",300)},400); }
}
```

## C. Replace the current /api/orders route

```js
if (path === "/api/orders" && request.method === "GET") {
  const customerId=await getCustomer(request,env);
  if (!customerId) return json({error:"سجّل الدخول أولاً"},401);
  await ensureOrderGeoTable(env.DB);
  const result=await env.DB.prepare(`SELECT o.id,o.traffic_gb,o.status,o.country,g.region,g.city,g.isp,
    p.payment_status,pr.proxy_login,pr.proxy_password,pr.package_key
    FROM orders o JOIN payments p ON p.order_id=o.id
    LEFT JOIN order_geo g ON g.order_id=o.id LEFT JOIN provisioning pr ON pr.order_id=o.id
    WHERE o.customer_id=? ORDER BY o.created_at DESC`).bind(customerId).all();
  let map=new Map(), providerError=null;
  try {
    const response=await proxyRequest(env,"residentsubuser/packages");
    for(const p of providerItems(response)) if(p.package_key!=null) map.set(String(p.package_key),p);
  } catch(e) { providerError=safeText(e?.message||"provider unavailable",180); }
  const orders=(result.results||[]).map(o=>({
    ...o,proxy_host:SOCKS_HOST,proxy_port:SOCKS_PORT,
    ...packageUsage(map.get(String(o.package_key))),
    usage_status:providerError?"temporarily_unavailable":
      (map.has(String(o.package_key))?"available":"not_available")
  }));
  return json({orders,usage_error:providerError});
}
```

## D. Customer UI

The existing customerPage already loads the full `GEO` tree. In its `orders()` renderer, add a location selector for each active order (country → region → city → ISP), then call:

```js
await api('/api/change-location',{order_id:o.id,country,region,city,isp});
await orders();
```

Show `traffic_usage_gb`, `traffic_left_gb`, `traffic_limit_gb`, and `expired_at` from each order. Never show package_key or internal provider IDs. Do not change payment code.

## Before production deployment

1. Confirm the provider's DELETE endpoint accepts a JSON body in staging.
2. Test a paid order and verify package_key, traffic_left, traffic_usage and expired_at are unchanged after switching.
3. Verify the new list credentials work before considering the old list cleanup complete.
4. This patch is not deployed. The existing production Worker remains unchanged.
