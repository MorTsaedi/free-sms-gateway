# Free SMS Gateway

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Python](https://img.shields.io/badge/python-3.12-blue?logo=python&logoColor=white)
![FastAPI](https://img.shields.io/badge/fastapi-0.111-009485?logo=fastapi&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-1.9-7F52B5?logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/android-34-3DDC84?logo=android&logoColor=white)
![Docker](https://img.shields.io/badge/docker-ready-2496ed?logo=docker&logoColor=white)
![Platform](https://img.shields.io/badge/platform-self--hosted-orange)
![PRs](https://img.shields.io/badge/PRs-welcome-brightgreen)

> **Never pay Twilio, Plivo, or any SMS API again.** Run your own SMS gateway on a $5/month VPS and pay only for the SMS charges on your existing mobile plan.

---

## Why I Made This

After years of paying **$20–$50/month** for Twilio and other SMS-as-a-Service providers, I realized I was being charged a massive markup for a service that essentially just forwards HTTP → SMS on a phone that's already sitting in my pocket.

The math is simple:

| Solution | Cost |
|----------|------|
| Twilio SMS ($0.0075/SMS) | ~$0.0075 per message |
| AWS SNS | ~$0.005 per message |
| **This project (self-hosted)** | **$0.00** (just your mobile plan) |

The only real cost is the SMS charge from your carrier — which you're already paying for. The app runs on your Android phone, polls your server for messages, and sends them via `SmsManager`. No Firebase, no cloud provider fees, no subscription traps.

Perfect for:
- **Personal notifications** — Get alerts from your servers, apps, and services
- **Business notifications** — Send order confirmations, appointment reminders, alerts
- **Two-factor delivery** — Reliable SMS delivery without third-party providers
- **International SMS** — Use local SIM cards to send SMS globally at local rates
- **Privacy-focused** — Your SMS content never touches a third-party server

This is the tool I always wished existed. Now it does.

---

## Architecture

```
┌─────────────┐     HTTP Polling      ┌─────────────┐
│   VM Server │◄──────────────────────│  Android    │
│ (FastAPI)   │  SMS Queue + Config   │  App (APK)  │
└─────────────┘                       └─────────────┘
      │                                      │
      ▼                                      ▼
┌─────────────┐                       ┌─────────────┐
│  SQLite DB  │                       │  SMS Manager│
│  (queue,    │                       │  (send SMS, │
│   devices)  │                       │   permissions)│
└─────────────┘                       └─────────────┘
```

### How It Works

1. **Admin** creates a device in the admin UI → API key is generated
2. **Admin** triggers an APK build with that API key → APK is compiled on the VM
3. **User** installs the APK on their phone → app registers with VM using its key
4. **Admin** queues SMS via admin UI → message sits in SQLite queue
5. **Phone** polls VM every 15s (configurable) → claims pending SMS → sends via `SmsManager` → reports result
6. **Phone** reports delivery confirmation (if the carrier supports it) → status becomes `delivered`
7. **Admin** sees delivery status in the logs

### Key Design Decisions

| Decision | Reason |
|----------|--------|
| **HTTP polling** (not push/Firebase) | No Google dependencies; works through NAT/firewalls; simplest architecture |
| **VM builds APK** | App gets correct config baked in; user doesn't need to enter sensitive keys manually |
| **APK is debug-signed** | No Play Store required; direct install; no build complexity |
| **Foreground service** | Android 13+ requires foreground service for background SMS sending |
| **WorkManager backup** | If foreground service is killed, WorkManager claims **and sends** SMS — a message is never left stuck in `claimed` |
| **At-most-once delivery** | The poll query atomically claims SMS, and stale claims (crashed phones) are released after 5 minutes so a message is never sent twice or lost |
| **SQLite on VM** | No external DB required; runs on any VPS with just Python + a web server |
| **bCrypt hashed API keys** | Keys never stored in plaintext; compromised DB won't leak usable keys |

---

## Features

- **VM Server (FastAPI)**
  - Device registration with auto-generated API keys
  - HTTP polling endpoint for SMS delivery
  - SMS queue with status tracking (pending → claimed → sent → delivered/failed)
  - Admin API + web UI (HTMX + Tailwind, no JS framework needed)
  - On-demand APK building with config injection
  - One-time download tokens (1-hour expiry) for APK downloads
  - Rate limiting on SMS send endpoint
  - Structured JSON logging

- **Android App (Kotlin)**
  - Foreground service for persistent background operation
  - WorkManager fallback for periodic polling
  - Sends SMS via `SmsManager` (native Android API)
  - Boot receiver — auto-starts after device reboot
  - Battery and signal strength reporting
  - Config stored securely (device API key baked in at build time)

- **Admin UI**
  - Device management (register, view status, delete)
  - SMS queue with filtering and pagination
  - One-click APK builder with live config preview
  - Activity logs with real-time stats
  - Responsive design — works on mobile too

- **Deployment**
  - Single `docker compose up` command
  - **Native install** (no Docker) — plain Python + uvicorn behind nginx, no Android SDK needed on the server unless you build APKs on-machine
  - Automatic HTTPS with Let's Encrypt (certbot)
  - Nginx reverse proxy with static file serving
  - Health checks for container orchestration

---

## Quick Start

### 1. Clone and Configure

```bash
git clone https://github.com/MorTsaedi/free-sms-gateway.git
cd free-sms-gateway
cp .env.example .env
# Edit .env — set API_KEY, SECRET_KEY, VM_PUBLIC_URL
```

### 2. Deploy

```bash
docker compose up -d
```

Navigate to `https://your-domain.com/admin` in your browser.

### 3. Create a Device

1. Go to **Devices** → **Add Device**
2. Enter a name (e.g., "My Phone")
3. Copy the generated device API key — you'll need it next

### 4. Build & Install APK

1. Go to **Build APK** page
2. Paste your VM URL and device API key
3. Click **Build APK** → wait ~2 minutes
4. Click the download link → install on Android
5. Grant SMS permissions when prompted

### 5. Send SMS

1. Go to **Send SMS** page
2. Select device, enter phone number + message
3. Phone receives it on next poll (within 15s)

---

## Run Without Docker (Native Install)

No Docker required. The gateway is just a FastAPI app; it runs on any Linux VPS with Python 3.10+. The Android SDK is only needed if you want the server to build APKs for you — for plain operation you can skip it entirely and build APKs elsewhere.

### 1. Install the app

```bash
git clone https://github.com/MorTsaedi/free-sms-gateway.git
cd free-sms-gateway

# Create a venv and install dependencies
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env — set API_KEY, SECRET_KEY, VM_PUBLIC_URL
#   API_KEY:     admin key for the web UI
#   SECRET_KEY:  any random string
#   VM_PUBLIC_URL: your server's public URL (used for APK download links)
```

### 3. Start the server

```bash
uvicorn server.main:app --host 0.0.0.0 --port 8000
```

Open `http://your-server:8000/admin` and enter the admin key from `.env`.

### 4. Run as a systemd service (recommended)

Create `/etc/systemd/system/sms-gateway.service`:

```ini
[Unit]
Description=Free SMS Gateway (FastAPI + uvicorn)
After=network.target

[Service]
Type=simple
WorkingDirectory=/root/free-sms-gateway
EnvironmentFile=/root/free-sms-gateway/.env
ExecStart=/root/free-sms-gateway/.venv/bin/uvicorn server.main:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now sms-gateway
sudo systemctl status sms-gateway
```

### 5. Put nginx in front (for HTTPS + static files)

```nginx
# /etc/nginx/sites-available/sms-gateway
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

Add HTTPS with `sudo certbot --nginx -d your-domain.com`, then update `VM_PUBLIC_URL` in `.env` to `https://your-domain.com` and restart the service.

> **Static files note:** if the nginx `www-data` user can't read the `static/downloads/` directory under a restricted home folder, proxy `/static/` to FastAPI in the nginx config instead of serving it from disk.

### 6. (Optional) On-machine APK builds

To let the server compile APKs you need **JDK 17** and the **Android SDK**. See the Dockerfile or [Android section](#building-apk-locally) for the toolchain, then adjust `server/services/apk_builder.py` paths if your SDK lives elsewhere. Without the SDK, build the APK on any machine with Android Studio and either upload it to `static/downloads/` or point `build_config.yaml` at it.

### Native vs. Docker — what's the difference?

| | Docker | Native |
|---|---|---|
| Dependencies | Docker + Compose | Python 3.10+ (everything else via pip) |
| APK builds on server | Included (SDK in image, ~2GB) | Optional — needs JDK 17 + Android SDK |
| Memory at idle | ~50–100MB in container | ~50MB (just the Python process) |
| Best for | One-shot reproducible deploy | Cheap/low-RAM VPS, easy to edit & debug |

---

## Project Structure

```
free-sms-gateway/
├── docker-compose.yml          # Multi-service deployment
├── Dockerfile                  # Python + Android SDK image
├── requirements.txt
├── build_config.yaml           # APK build configuration
├── nginx.conf                  # Reverse proxy + SSL
├── .env.example                # Environment template
├── server/                     # FastAPI backend
│   ├── main.py                 # App entry point
│   ├── config.py               # Settings + build config
│   ├── database.py             # SQLAlchemy models
│   ├── auth.py                 # API key auth (bcrypt)
│   ├── routes/
│   │   ├── device.py           # Device endpoints
│   │   ├── sms.py              # SMS queue endpoints
│   │   ├── admin.py            # Admin UI (HTML pages)
│   │   ├── admin_api.py        # Admin API endpoints
│   │   └── apk.py              # APK download
│   ├── services/
│   │   ├── apk_builder.py      # Gradle build wrapper
│   │   └── sms_queue.py        # Queue management + stats
│   └── templates/              # Jinja2 + HTMX templates
├── android/                    # Android app source
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/smsgateway/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── SmsService.kt
│   │   │   │   ├── SmsStatusReceiver.kt   # SMS sent/delivery status (manifest-registered)
│   │   │   │   ├── SmsResultSender.kt     # Reports send result (works if service is killed)
│   │   │   │   ├── DeliveryReporter.kt    # Reports delivery status (works if service is killed)
│   │   │   │   ├── PollingWorker.kt
│   │   │   │   ├── ApiClient.kt
│   │   │   │   ├── BootReceiver.kt
│   │   │   │   └── SmsGatewayApplication.kt
│   │   │   └── res/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
└── README.md
```

---

## API Reference

### Device Endpoints (Device API Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/device/register` | Register a new device |
| `GET` | `/api/v1/device/poll` | Poll for & claim pending SMS |
| `POST` | `/api/v1/device/heartbeat` | Update device status |
| `POST` | `/api/v1/device/sms/{id}/result` | Report SMS send result |
| `POST` | `/api/v1/device/sms/{id}/delivery` | Report SMS delivery status |

### SMS Endpoints (Admin API Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/sms/send` | Queue an SMS |
| `GET` | `/api/v1/sms/queue` | List SMS queue (with filtering) |
| `POST` | `/api/v1/sms/retry/{id}` | Retry a failed SMS |
| `DELETE` | `/api/v1/sms/{id}` | Delete from queue |

### Admin API Endpoints (Admin API Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/admin/devices` | List devices |
| `POST` | `/api/v1/admin/devices` | Create device |
| `DELETE` | `/api/v1/admin/devices/{id}` | Delete device |
| `GET` | `/api/v1/admin/api-keys` | List admin API keys |
| `POST` | `/api/v1/admin/api-keys` | Create admin API key |
| `GET` | `/api/v1/admin/stats` | Get statistics |
| `POST` | `/api/v1/admin/test-sms` | Send test SMS |
| `POST` | `/api/v1/admin/apk/build` | Build APK |

### Admin UI Pages

| Route | Description |
|-------|-------------|
| `/admin` | Dashboard with device status + stats |
| `/admin/devices` | Device management |
| `/admin/send` | SMS queue form |
| `/admin/build` | APK builder |
| `/admin/logs` | SMS activity logs |

### Public

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Health check |
| `GET` | `/api/v1/apk/download/{token}` | Download APK (one-time token) |

---

## Configuration

### Environment Variables (`.env`)

| Variable | Description | Required |
|----------|-------------|----------|
| `API_KEY` | Admin API key for backend access | Yes |
| `SECRET_KEY` | Random secret for sessions | Yes |
| `VM_PUBLIC_URL` | Public URL for APK download links | Yes |
| `DATABASE_URL` | SQLite path (usually default) | No |

### Build Config (`build_config.yaml`)

```yaml
vm:
  url: "https://your-domain.com"
  api_key: "device-api-key"
app:
  package_name: "com.smsgateway"
  app_name: "SMS Gateway"
  poll_interval_seconds: 15
  heartbeat_interval_seconds: 60
build:
  keystore: "debug"
```

---

## Security

- **API keys**: 32-char random tokens, stored as bcrypt hashes
- **HTTPS**: Nginx + Let's Encrypt automatic SSL
- **APK downloads**: One-time tokens that expire after 1 hour
- **No SMS reading**: Only outbound sending via `SmsManager`
- **Rate limiting**: 10 SMS/min per device on the send endpoint
- **APK is debug-signed**: User installs via "Unknown Sources" — no Play Store needed

---

## Development

### Prerequisites

- Python 3.12+
- Android SDK (for APK builds)
- Java 17

### Local Development

```bash
pip install -r requirements.txt
uvicorn server.main:app --reload
```

### Building APK Locally

```bash
cd android
./gradlew assembleDebug
```

### Project Conventions

- Python backend uses **FastAPI** async with SQLAlchemy 2.0
- API auth uses **API key headers** (`X-API-Key` for admin, `X-Device-API-Key` for devices)
- Android app uses **Kotlin** with **WorkManager** + **Foreground Service**
- Admin UI uses **Jinja2** + **Tailwind CSS** + vanilla JS (no React/Vue/Angular)

---

## Troubleshooting

### APK build fails
- Docker: check logs with `docker compose logs app`
- Native: run the build from the server log: `journalctl -u sms-gateway -f` or start uvicorn in the foreground
- The Android SDK install can take 5+ minutes on first run
- Ensure your machine has enough disk space (Android SDK + Gradle cache ~2GB)

### SMS stuck in "claimed" status
- A message is `claimed` when a phone polls for it but hasn't reported a result yet
- The poll endpoint **releases stale claims after 5 minutes**, so a crashed/killed phone's message becomes available to any online device again — check that at least one device is online
- If the phone app was updated, confirm the new APK is installed (old builds that only polled — without sending — could claim without reporting)

### Phone not receiving SMS
- Check device status in admin UI — should show "online"
- Verify SMS permission is granted on the Android app
- Check that the VM URL is accessible from your phone (not `localhost`)
- Review logs in the app — they show connection status and errors

### Nginx SSL issues
- First-time Let's Encrypt setup takes a few minutes
- Docker: check certificate status with `docker compose run certbot certificates`
- Native: check with `sudo certbot certificates`, then `sudo systemctl reload nginx`
- For local testing, temporarily comment out the SSL redirect in the server block

### Container Resource Usage

The Docker image includes the JDK + Android SDK for on-demand APK builds. At idle, the container uses only ~50–100MB RAM (just the Python FastAPI process). The build tools consume **no RAM** until an APK build is triggered — they run as a short-lived subprocess within the same container.

**Disk overhead**: ~2GB for the JDK + Android SDK in the image. If you're on a disk-constrained VPS and don't need on-demand APK builds, you can:
1. Build the APK once on another machine with the Android SDK
2. Place the APK in `./static/downloads/`
3. Create a download token via the API: `POST /api/v1/admin/apk/build` (with the pre-built APK path)
- Admin keys go in the `api_keys` table (created via POST `/api/v1/admin/api-keys`)
- Device keys are generated when you create a device and shown once
- The initial admin key must be bootstrapped manually — seed it into the database:
  ```sql
  INSERT INTO api_keys (key_hash, name, created_at) VALUES ('<bcrypt-hash-of-your-key>', 'default', NOW());
  ```

---

## License

MIT License — Feel free to fork, modify, and use this for any purpose, personal or commercial.

---

## Acknowledgments

- The Android SMS sending approach is inspired by various open-source SMS gateway projects
- The FastAPI patterns are based on modern async Python best practices
- The HTMX admin UI is intentionally minimal — it's a tool, not a showcase