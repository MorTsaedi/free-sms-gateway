# SMS Gateway — API Usage Guide

Use this guide to send SMS from your own apps, scripts, or services through
your self-hosted SMS Gateway. The gateway relays your HTTP request to an
Android phone (your "device"), which sends the actual SMS from its SIM.

- **Base URL (this server):** `http://185.214.101.206`
- **Interactive docs:** `http://185.214.101.206/docs` (OpenAPI/Swagger)
- **Health check:** `GET /health`

All request/response bodies are JSON.

---

## 1. Authentication — the two key types

| Key | Header | What it's for |
|-----|--------|---------------|
| **API key** | `X-API-Key` | Your apps send SMS, manage devices, check the queue. **Use this one in your other apps.** |
| **Device API key** | `X-Device-API-Key` | Only the Android phone uses this, to poll/send. You don't use it from your apps. |

### Get an API key for your app

Use an existing API key to create a dedicated key per app/service:

```bash
curl -X POST http://185.214.101.206/api/v1/admin/api-keys \
  -H "X-API-Key: test-admin-key-123456789012345678901234" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-order-service"}'
```

Response (the plain `key` is shown **only once** — save it):

```json
{ "id": 2, "name": "my-order-service", "key": "Gf8x...", "created_at": "2026-08-04T..." }
```

> **Bootstrap note:** if you have no API key yet, the admin UI prompts for one.
> On this server the bootstrap key is `test-admin-key-123456789012345678901234`.
> After logging in, the **API Keys** page lets you create per-app keys.
> Keep each app on its own key so you can revoke one without breaking the others.

---

## 2. Quick start — send your first SMS

Sending an SMS is a single `POST`. The phone must be **online** (the Android
app running and connected — check the Dashboard).

```bash
curl -X POST http://185.214.101.206/api/v1/sms/send \
  -H "X-API-Key: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "device_id": 1,
        "to_number": "+1234567890",
        "message": "Hello from my app"
      }'
```

Success response (`status: "pending"` means queued and waiting for the phone):

```json
{
  "id": 12,
  "device_id": 1,
  "to_number": "+1234567890",
  "message": "Hello from my app",
  "status": "pending",
  "created_at": "2026-08-04T04:00:00.000000"
}
```

**`device_id` is the physical phone that will actually deliver the SMS.** Find
the id with `GET /api/v1/admin/devices` (below) or in the admin UI.

---

## 3. Common operations

### List devices (to get `device_id` and check online status)

```bash
curl http://185.214.101.206/api/v1/admin/devices \
  -H "X-API-Key: YOUR_API_KEY"
```

```json
[
  { "id": 1, "name": "1349", "status": "online",
    "last_seen": "2026-08-04T03:55:47.062054", "created_at": "..." }
]
```

`status` is one of: `online`, `offline`, `never_connected`.
Only `online` devices can accept SMS.

### Create a device (a new phone)

```bash
curl -X POST http://185.214.101.206/api/v1/admin/devices \
  -H "X-API-Key: YOUR_API_KEY" -H "Content-Type: application/json" \
  -d '{"name": "office-phone-2"}'
```

Returns the device with its **device API key** (`api_key`) shown **once** —
that key is what you put inside the Android app for that phone.

### Check the send queue / delivery status

```bash
# All messages
curl "http://185.214.101.206/api/v1/sms/queue" -H "X-API-Key: YOUR_API_KEY"

# Filter by status (pending | sent | failed | delivered) and page
curl "http://185.214.101.206/api/v1/sms/queue?status=sent&page=1&page_size=50" \
  -H "X-API-Key: YOUR_API_KEY"
```

```json
{
  "items": [ { "id": 12, "device_id": 1, "to_number": "+1234567890",
               "message": "Hello", "status": "sent",
               "created_at": "...", "sent_at": "..." } ],
  "total": 1, "page": 1, "page_size": 20
}
```

### Retry or delete a queued message

```bash
curl -X POST http://185.214.101.206/api/v1/sms/retry/12 -H "X-API-Key: YOUR_API_KEY"   # back to pending
curl -X DELETE http://185.214.101.206/api/v1/sms/12 -H "X-API-Key: YOUR_API_KEY"      # remove
```

### Dashboard stats

```bash
curl http://185.214.101.206/api/v1/admin/stats -H "X-API-Key: YOUR_API_KEY"
```

---

## 4. Language examples

### Python (httpx)

```python
import httpx

BASE = "http://185.214.101.206"
API_KEY = "YOUR_API_KEY"

def send_sms(device_id: int, to_number: str, message: str) -> dict:
    r = httpx.post(
        f"{BASE}/api/v1/sms/send",
        headers={"X-API-Key": API_KEY},
        json={"device_id": device_id, "to_number": to_number, "message": message},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()
```

### Node.js (fetch)

```javascript
const res = await fetch("http://185.214.101.206/api/v1/sms/send", {
  method: "POST",
  headers: { "X-API-Key": "YOUR_API_KEY", "Content-Type": "application/json" },
  body: JSON.stringify({ device_id: 1, to_number: "+1234567890", message: "Hi" }),
});
const data = await res.json();
```

---

## 5. Error handling

| HTTP | `detail` | Meaning / how to fix |
|------|----------|----------------------|
| 200 | — | Success |
| 400 | `"Device is offline"` | The phone isn't connected/online. Make sure the Android app is running. |
| 400 | `"Device name already exists"` | Name collision on create/register. |
| 401 | `"API key required"` / `"Invalid API key"` | Missing or wrong `X-API-Key`. |
| 404 | `"Device not found"` | Bad `device_id`. |
| 422 | Validation error | Body missing/invalid field, e.g. no `to_number`. |

Rule of thumb for retries: if the request **succeeded (200)** the SMS is queued
and will be delivered **exactly once** — do not re-send it on a timeout. If you
got an error response, safe to fix and retry.

---

## 6. Endpoint reference

### Apps / sending SMS — auth: `X-API-Key`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/sms/send` | Queue an SMS (body: `device_id`, `to_number`, `message`) |
| `GET` | `/api/v1/sms/queue` | List queue; filters `device_id`, `status`, `page`, `page_size` |
| `POST` | `/api/v1/sms/retry/{sms_id}` | Move a sent/failed message back to pending |
| `DELETE` | `/api/v1/sms/{sms_id}` | Delete a queued message |
| `GET` | `/api/v1/admin/stats` | Dashboard stats |
| `GET` | `/api/v1/admin/devices` | List devices |
| `POST` | `/api/v1/admin/devices` | Create a device (returns its API key once) |
| `DELETE` | `/api/v1/admin/devices/{device_id}` | Delete a device |
| `GET` | `/api/v1/admin/api-keys` | List your API keys |
| `POST` | `/api/v1/admin/api-keys` | Create an API key for an app (returns key once) |
| `DELETE` | `/api/v1/admin/api-keys/{key_id}` | Revoke an API key |
| `POST` | `/api/v1/admin/apk/build` | Build an APK (see Build APK page) |

### Device (Android phone) — auth: `X-Device-API-Key`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/device/register` | Phone self-registers (body: `name`, optional `api_key`) |
| `GET` | `/api/v1/device/poll` | Fetch SMS pending delivery to this phone |
| `POST` | `/api/v1/device/heartbeat` | Phone reports alive/status |
| `POST` | `/api/v1/device/sms/{sms_id}/result` | Phone reports send outcome (body: `success`, `error`) |
| `GET` | `/api/v1/apk/build-config` | Get build configuration |
| `GET` | `/api/v1/apk/download/{token}` | Download a built APK (token expires in 1h) |

---

### Simple-minded flow for an external app

1. Get an API key (`POST /api/v1/admin/api-keys`) for your app.
2. Determine the phone's `device_id` (`GET /api/v1/admin/devices`) and confirm
   it's `online`.
3. `POST /api/v1/sms/send` with `{device_id, to_number, message}`.
4. Watch `GET /api/v1/sms/queue?status=failed` and retry any that failed.

---

## 7. Notes & gotchas

- **The phone must be online.** SMS is only queued, not directly sent, if the
  device is online. A message queued to an offline phone waits until it reconnects.
- **At-most-once delivery.** Each queued message is handed to the phone exactly
  once, then marked `sent`. Don't re-send on success.
- **Cleartext HTTP.** This server uses plain HTTP on `:80`. If you connect over
  the internet / a different machine, consider fronting with HTTPS for production.
- **International numbers.** Use full international format with `+` (e.g.
  `+15551234567`). Delivery depends on your phone's carrier plan.