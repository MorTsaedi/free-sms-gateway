# Free SMS Gateway

Self-hosted SMS gateway with VM server (Python/FastAPI) + Android app (APK auto-built on VM). Phone polls VM for outbound SMS via HTTP. No Firebase, no paid services. Only cost = SMS charges on mobile plan.

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

## Features

- **VM Server**: FastAPI with SQLite database
- **Android App**: Kotlin app with foreground service for background polling
- **Admin UI**: Minimal HTMX + Tailwind dashboard
- **APK Builder**: Builds custom APKs with embedded configuration
- **Docker Deployment**: Single `docker compose up` command
- **No External Dependencies**: No Firebase, no paid services

## Quick Start

### 1. Clone and Configure

```bash
git clone <repo>
cd sms-gateway

# Copy example env and edit
cp .env.example .env
# Edit .env with your values
```

### 2. Start with Docker Compose

```bash
docker compose up -d
```

This starts:
- FastAPI server on port 8000
- Nginx reverse proxy on ports 80/443
- Automatic HTTPS with Let's Encrypt (certbot)

### 3. Access Admin UI

Open `https://your-domain.com/admin` in browser.

First time: Enter your admin API key (from `.env` `API_KEY`).

### 4. Create a Device

1. Go to **Devices** page
2. Click **Add Device**
3. Enter a name (e.g., "My Phone")
4. **Save the API key shown** - you'll need it for the APK

### 5. Build APK

1. Go to **Build APK** page
2. Fill in:
   - **VM URL**: Your public URL (e.g., `https://sms.example.com`)
   - **Device API Key**: The key from step 4
3. Click **Build APK**
4. Download the APK when ready (link expires in 1 hour)

### 6. Install on Android

1. Transfer APK to phone (download link, USB, etc.)
2. Install (allow "Unknown sources" if prompted)
3. Open app, grant SMS permission
4. App will show "Configured ✓" and start polling

### 7. Send SMS

1. Go to **Send SMS** page in admin UI
2. Select device, enter phone number and message
3. Click **Send SMS**
4. Phone will pick it up on next poll (within 15s default)

## Configuration

### Environment Variables (`.env`)

| Variable | Description | Default |
|----------|-------------|---------|
| `API_KEY` | Admin API key for UI access | Required |
| `SECRET_KEY` | Session secret | Required |
| `VM_PUBLIC_URL` | Public URL for APK downloads | `http://localhost` |

### Build Config (`build_config.yaml`)

```yaml
vm:
  url: "https://your-domain.com"
  api_key: "auto-generated-key"
app:
  package_name: "com.smsgateway"
  app_name: "SMS Gateway"
  poll_interval_seconds: 15
  heartbeat_interval_seconds: 60
build:
  keystore: "debug"
```

## API Endpoints

### Device Endpoints (Device API Key)
- `GET /api/v1/device/poll` - Poll for pending SMS
- `POST /api/v1/device/register` - Register device
- `POST /api/v1/device/heartbeat` - Update device status
- `POST /api/v1/device/sms/{id}/result` - Report SMS result

### SMS Endpoints (Admin API Key)
- `POST /api/v1/sms/send` - Queue SMS
- `GET /api/v1/sms/queue` - List SMS queue
- `POST /api/v1/sms/retry/{id}` - Retry failed SMS
- `DELETE /api/v1/sms/{id}` - Delete from queue

### Admin Endpoints (Admin API Key)
- `GET /api/v1/admin/devices` - List devices
- `POST /api/v1/admin/devices` - Create device
- `DELETE /api/v1/admin/devices/{id}` - Delete device
- `GET /api/v1/admin/api-keys` - List admin API keys
- `POST /api/v1/admin/api-keys` - Create admin API key
- `GET /api/v1/admin/stats` - Get statistics
- `POST /api/v1/admin/test-sms` - Send test SMS
- `POST /api/v1/admin/apk/build` - Build APK
- `GET /api/v1/apk/build-config` - Get build config
- `GET /api/v1/apk/download/{token}` - Download APK

### Public
- `GET /health` - Health check

## Security

- API keys: 32-char random, stored as bcrypt hash
- HTTPS via Nginx + Let's Encrypt
- Rate limiting on `/sms/send` (10/min per device)
- Only outbound SMS - no reading of messages
- APK is debug-signed (user accepts "unknown source" warning)

## Development

### Local Development

```bash
# Install dependencies
pip install -r requirements.txt

# Run server
uvicorn server.main:app --reload

# Run Android build (requires Android SDK)
cd android && ./gradlew assembleDebug
```

### Project Structure

```
sms-gateway/
├── docker-compose.yml
├── Dockerfile
├── requirements.txt
├── build_config.yaml
├── nginx.conf
├── server/
│   ├── main.py
│   ├── config.py
│   ├── database.py
│   ├── auth.py
│   ├── routes/
│   │   ├── device.py
│   │   ├── sms.py
│   │   ├── admin.py
│   │   └── apk.py
│   ├── services/
│   │   ├── apk_builder.py
│   │   └── sms_queue.py
│   └── templates/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/smsgateway/
│   │   │   └── res/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── README.md
```

## Troubleshooting

### APK Build Fails
- Check Docker logs: `docker compose logs app`
- Ensure Android SDK is properly installed in Dockerfile
- Try building locally: `cd android && ./gradlew assembleDebug`

### Phone Not Receiving SMS
- Check device status in admin UI (should be "online")
- Verify SMS permission granted on phone
- Check phone logs in app (Logs section)
- Ensure VM URL is accessible from phone (not localhost)

### Nginx SSL Issues
- For local testing, use HTTP (edit nginx.conf to remove SSL redirect)
- For production, ensure domain points to server IP
- Check certbot logs: `docker compose logs certbot`

## License

MIT License - Feel free to use and modify.