from fastapi import APIRouter, Depends, HTTPException, Request, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
from pydantic import BaseModel
from datetime import datetime, timedelta
from typing import Optional

from server.database import get_db, Device, SMSQueue, SMSStatus, APIKey, DownloadToken, DeviceStatus
from server.auth import get_admin_api_key, hash_key, generate_api_key
from server.config import settings, build_config
from server.services.apk_builder import build_apk
from server.services.sms_queue import get_queue_stats

router = APIRouter(prefix="/admin", tags=["admin"])
templates = Jinja2Templates(directory="server/templates")


@router.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    return templates.TemplateResponse("dashboard.html", {"request": request})


@router.get("/devices", response_class=HTMLResponse)
async def devices_page(request: Request):
    return templates.TemplateResponse("devices.html", {"request": request})


@router.get("/send", response_class=HTMLResponse)
async def send_page(request: Request):
    return templates.TemplateResponse("send.html", {"request": request})


@router.get("/build", response_class=HTMLResponse)
async def build_page(request: Request):
    return templates.TemplateResponse("build.html", {"request": request})


@router.get("/logs", response_class=HTMLResponse)
async def logs_page(request: Request):
    return templates.TemplateResponse("logs.html", {"request": request})


class DeviceCreateRequest(BaseModel):
    name: str


class DeviceResponse(BaseModel):
    id: int
    name: str
    status: str
    last_seen: Optional[datetime]
    created_at: datetime


class APIKeyCreateRequest(BaseModel):
    name: str


class APIKeyResponse(BaseModel):
    id: int
    name: str
    key: str
    created_at: datetime


class BuildAPKRequest(BaseModel):
    vm_url: Optional[str] = None
    api_key: Optional[str] = None
    package_name: Optional[str] = None
    app_name: Optional[str] = None
    poll_interval_seconds: Optional[int] = None
    heartbeat_interval_seconds: Optional[int] = None


@router.get("/devices", response_model=list[DeviceResponse])
async def list_devices(admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Device).order_by(Device.created_at.desc()))
    devices = result.scalars().all()
    return [
        DeviceResponse(
            id=d.id,
            name=d.name,
            status=d.status.value,
            last_seen=d.last_seen,
            created_at=d.created_at,
        )
        for d in devices
    ]


@router.post("/devices", response_model=DeviceResponse)
async def create_device(request: DeviceCreateRequest, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    existing = await db.execute(select(Device).where(Device.name == request.name))
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=400, detail="Device name already exists")

    api_key = generate_api_key()
    api_key_hash = hash_key(api_key)

    device = Device(name=request.name, api_key_hash=api_key_hash)
    db.add(device)
    await db.commit()
    await db.refresh(device)

    return DeviceResponse(
        id=device.id,
        name=device.name,
        status=device.status.value,
        last_seen=device.last_seen,
        created_at=device.created_at,
    )


@router.delete("/devices/{device_id}")
async def delete_device(device_id: int, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    device = await db.get(Device, device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    await db.delete(device)
    await db.commit()
    return {"status": "ok"}


@router.get("/api-keys", response_model=list[APIKeyResponse])
async def list_api_keys(admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(APIKey).order_by(APIKey.created_at.desc()))
    keys = result.scalars().all()
    return [
        APIKeyResponse(id=k.id, name=k.name, key="***", created_at=k.created_at)
        for k in keys
    ]


@router.post("/api-keys", response_model=APIKeyResponse)
async def create_api_key(request: APIKeyCreateRequest, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    api_key = generate_api_key()
    api_key_hash = hash_key(api_key)

    key = APIKey(name=request.name, key_hash=api_key_hash)
    db.add(key)
    await db.commit()
    await db.refresh(key)

    return APIKeyResponse(id=key.id, name=key.name, key=api_key, created_at=key.created_at)


@router.delete("/api-keys/{key_id}")
async def delete_api_key(key_id: int, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    key = await db.get(APIKey, key_id)
    if not key:
        raise HTTPException(status_code=404, detail="API key not found")
    await db.delete(key)
    await db.commit()
    return {"status": "ok"}


@router.get("/stats")
async def get_stats(admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    stats = await get_queue_stats(db)
    return stats


@router.post("/test-sms")
async def send_test_sms(
    device_id: int = Form(...),
    to_number: str = Form(...),
    message: str = Form(...),
    admin: str = Depends(get_admin_api_key),
    db: AsyncSession = Depends(get_db)
):
    device = await db.get(Device, device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    
    if device.status != DeviceStatus.ONLINE:
        raise HTTPException(status_code=400, detail="Device is offline")

    sms = SMSQueue(
        device_id=device_id,
        to_number=to_number,
        message=message,
        status=SMSStatus.PENDING,
    )
    db.add(sms)
    await db.commit()
    
    return {"status": "queued", "sms_id": sms.id}


@router.post("/apk/build")
async def build_apk_endpoint(request: BuildAPKRequest, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    if request.vm_url:
        build_config.set("vm.url", request.vm_url)
    if request.api_key:
        build_config.set("vm.api_key", request.api_key)
    if request.package_name:
        build_config.set("app.package_name", request.package_name)
    if request.app_name:
        build_config.set("app.app_name", request.app_name)
    if request.poll_interval_seconds:
        build_config.set("app.poll_interval_seconds", request.poll_interval_seconds)
    if request.heartbeat_interval_seconds:
        build_config.set("app.heartbeat_interval_seconds", request.heartbeat_interval_seconds)

    apk_path = await build_apk(build_config)
    
    token = generate_api_key()
    download_token = DownloadToken(
        token=token,
        apk_path=apk_path,
        expires_at=datetime.utcnow() + timedelta(hours=1),
    )
    db.add(download_token)
    await db.commit()
    
    download_url = f"{settings.vm_public_url}/api/v1/apk/download/{token}"
    return {"download_url": download_url, "expires_in": 3600}