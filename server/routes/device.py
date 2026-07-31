from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from pydantic import BaseModel
from datetime import datetime

from server.database import get_db, Device, SMSQueue, SMSStatus
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
        status="online",
        last_seen=datetime.utcnow(),
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)

    return DeviceRegisterResponse(device_id=device.id, name=device.name, api_key=api_key)


@router.get("/poll", response_model=PollResponse)
async def poll_sms(device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    device.last_seen = datetime.utcnow()
    device.status = "online"
    await db.commit()

    result = await db.execute(
        select(SMSQueue)
        .where(SMSQueue.device_id == device.id)
        .where(SMSQueue.status == SMSStatus.PENDING)
        .order_by(SMSQueue.created_at)
        .limit(10)
    )
    sms_list = result.scalars().all()

    return PollResponse(sms_list=[
        {
            "id": sms.id,
            "to_number": sms.to_number,
            "message": sms.message,
        }
        for sms in sms_list
    ])


@router.post("/heartbeat")
async def heartbeat(request: HeartbeatRequest, device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    device.last_seen = datetime.utcnow()
    device.status = request.status
    if request.battery_level is not None:
        device.config_json = device.config_json or "{}"
    await db.commit()
    return {"status": "ok"}


@router.post("/sms/{sms_id}/result")
async def sms_result(sms_id: int, success: bool, error: str | None = None, device: Device = Depends(get_device_api_key), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(SMSQueue).where(SMSQueue.id == sms_id, SMSQueue.device_id == device.id))
    sms = result.scalar_one_or_none()
    
    if not sms:
        raise HTTPException(status_code=404, detail="SMS not found")
    
    sms.status = SMSStatus.SENT if success else SMSStatus.FAILED
    sms.sent_at = datetime.utcnow()
    sms.error_message = error
    await db.commit()
    
    return {"status": "ok"}