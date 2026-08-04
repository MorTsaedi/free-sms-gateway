from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession
from pydantic import BaseModel
from datetime import datetime

from server.database import get_db, Device, SMSQueue, SMSStatus, DeviceStatus
from server.auth import get_device_api_key, hash_key, generate_api_key

router = APIRouter(prefix="/api/v1/device", tags=["device"])


class DeviceRegisterRequest(BaseModel):
    name: str
    api_key: str


class DeviceRegisterResponse(BaseModel):
    device_id: int
    name: str
    api_key: str


class PollResponse(BaseModel):
    sms_list: list[dict]


class HeartbeatRequest(BaseModel):
    status: str = "online"
    battery_level: int | None = None
    signal_strength: int | None = None


class SmsResultPayload(BaseModel):
    success: bool
    error: str | None = None


@router.post("/register", response_model=DeviceRegisterResponse)
async def register_device(request: DeviceRegisterRequest, db: AsyncSession = Depends(get_db)):
    existing = await db.execute(select(Device).where(Device.name == request.name))
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=400, detail="Device name already exists")

    api_key = request.api_key or generate_api_key()
    api_key_hash = hash_key(api_key)

    device = Device(
        name=request.name,
        api_key_hash=api_key_hash,
        status=DeviceStatus.ONLINE,
        last_seen=datetime.utcnow(),
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)

    return DeviceRegisterResponse(device_id=device.id, name=device.name, api_key=api_key)


@router.get("/poll", response_model=PollResponse)
async def poll_sms(device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    # get_device_api_key returns a detached instance (its session is already closed),
    # so mutations would not be tracked by this session. Merge it in first.
    device = await db.merge(device)
    device.last_seen = datetime.utcnow()
    device.status = DeviceStatus.ONLINE
    await db.commit()

    # Atomically CLAIM pending SMS for this device in a single statement. Each message is
    # handed to a device exactly once: if the app polls with two loops (or polls repeatedly),
    # the second poll finds nothing left to claim, so the same SMS can't be sent twice.
    # The app reports the actual outcome afterwards via /sms/{id}/result (SENT vs FAILED).
    claimed = (await db.execute(
        text(
            "UPDATE sms_queue SET status='SENT', sent_at=:now "
            "WHERE id IN ("
            "  SELECT id FROM sms_queue "
            "  WHERE device_id=:did AND status='PENDING' ORDER BY created_at LIMIT :lim"
            ") "
            "RETURNING id, to_number, message"
        ),
        {"now": datetime.utcnow(), "did": device.id, "lim": 20},
    )).mappings().all()
    await db.commit()

    return PollResponse(sms_list=[
        {"id": r["id"], "to_number": r["to_number"], "message": r["message"]}
        for r in claimed
    ])


@router.post("/heartbeat")
async def heartbeat(request: HeartbeatRequest, device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    # Same detached-instance caveat as poll_sms: merge into this session first.
    device = await db.merge(device)
    device.last_seen = datetime.utcnow()
    try:
        device.status = DeviceStatus(request.status)
    except ValueError:
        device.status = DeviceStatus.ONLINE
    if request.battery_level is not None:
        device.config_json = device.config_json or "{}"
    await db.commit()
    return {"status": "ok"}


@router.post("/sms/{sms_id}/result")
async def sms_result(sms_id: int, payload: SmsResultPayload, device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(SMSQueue).where(SMSQueue.id == sms_id, SMSQueue.device_id == device.id))
    sms = result.scalar_one_or_none()

    if not sms:
        raise HTTPException(status_code=404, detail="SMS not found")

    sms.status = SMSStatus.SENT if payload.success else SMSStatus.FAILED
    sms.error_message = payload.error
    await db.commit()

    return {"status": "ok"}