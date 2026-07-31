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
5. **Phone** polls VM every 15s (configurable) → receives pending SMS → sends via `SmsManager` → reports result
6. **Admin** sees delivery status in the logs

### Key Design Decisions

| Decision | Reason |
|----------|--------|
| **HTTP polling** (not push/Firebase) | No Google dependencies; works through NAT/firewalls; simplest architecture |
| **VM builds APK** | App gets correct config baked in; user doesn't need to enter sensitive keys manually |
| **APK is debug-signed** | No Play Store required; direct install; no build complexity |
| **Foreground service** | Android 13+ requires foreground service for background SMS sending |
| **WorkManager backup** | If foreground service is killed, WorkManager still retries polling |
| **SQLite on VM** | No external DB required; everything containerized with Docker Compose |
| **bCrypt hashed API keys** | Keys never stored in plaintext; compromised DB won't leak usable keys |

---

## Features

- **VM Server (FastAPI)**
  - Device registration with auto-generated API keys
  - HTTP polling endpoint for SMS delivery
  - SMS queue with status tracking (pending → sent → delivered/failed)
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
| `GET` | `/api/v1/device/poll` | Poll for pending SMS |
| `POST` | `/api/v1/device/heartbeat` | Update device status |
| `POST` | `/api/v1/device/sms/{id}/result` | Report SMS send result |

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
- Admin UI uses **Jinja2** + **HTMX** (no React/Vue/Angular)

---

## Troubleshooting

### APK build fails in Docker
- Check logs: `docker compose logs app`
- The Android SDK install can take 5+ minutes on first run
- Ensure your machine has enough disk space (Android SDK + Gradle cache ~2GB)

### Phone not receiving SMS
- Check device status in admin UI — should show "online"
- Verify SMS permission is granted on the Android app
- Check that the VM URL is accessible from your phone (not `localhost`)
- Review logs in the app — they show connection status and errors

### Nginx SSL issues
- First-time Let's Encrypt setup takes a few minutes
- Check certificate status: `docker compose run certbot certificates`
- For local testing, temporarily comment out the SSL redirect in `nginx.conf`

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