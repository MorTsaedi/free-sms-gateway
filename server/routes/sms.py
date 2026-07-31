from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
from pydantic import BaseModel
from datetime import datetime
from typing import Optional

from server.database import get_db, Device, SMSQueue, SMSStatus
from server.auth import get_admin_api_key, hash_key, generate_api_key

router = APIRouter(prefix="/api/v1/sms", tags=["sms"])


class SendSMSRequest(BaseModel):
    device_id: int
    to_number: str
    message: str


class SendSMSResponse(BaseModel):
    id: int
    device_id: int
    to_number: str
    message: str
    status: str
    created_at: datetime


class SMSListResponse(BaseModel):
    items: list[SendSMSResponse]
    total: int
    page: int
    page_size: int


@router.post("/send", response_model=SendSMSResponse)
async def send_sms(request: SendSMSRequest, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    device = await db.get(Device, request.device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    
    if device.status != "online":
        raise HTTPException(status_code=400, detail="Device is offline")

    sms = SMSQueue(
        device_id=request.device_id,
        to_number=request.to_number,
        message=request.message,
        status=SMSStatus.PENDING,
    )
    db.add(sms)
    await db.commit()
    await db.refresh(sms)

    return SendSMSResponse(
        id=sms.id,
        device_id=sms.device_id,
        to_number=sms.to_number,
        message=sms.message,
        status=sms.status.value,
        created_at=sms.created_at,
    )


@router.get("/queue", response_model=SMSListResponse)
async def list_sms_queue(
    device_id: Optional[int] = None,
    status: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    admin: str = Depends(get_admin_api_key),
    db: AsyncSession = Depends(get_db)
):
    query = select(SMSQueue)
    
    if device_id:
        query = query.where(SMSQueue.device_id == device_id)
    if status:
        query = query.where(SMSQueue.status == SMSStatus(status))
    
    query = query.order_by(SMSQueue.created_at.desc())
    
    total_result = await db.execute(select(func.count()).select_from(query.subquery()))
    total = total_result.scalar()
    
    query = query.offset((page - 1) * page_size).limit(page_size)
    result = await db.execute(query)
    items = result.scalars().all()

    return SMSListResponse(
        items=[
            SendSMSResponse(
                id=sms.id,
                device_id=sms.device_id,
                to_number=sms.to_number,
                message=sms.message,
                status=sms.status.value,
                created_at=sms.created_at,
            )
            for sms in items
        ],
        total=total,
        page=page,
        page_size=page_size,
    )


@router.post("/retry/{sms_id}")
async def retry_sms(sms_id: int, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    sms = await db.get(SMSQueue, sms_id)
    if not sms:
        raise HTTPException(status_code=404, detail="SMS not found")
    
    if sms.status == SMSStatus.PENDING:
        raise HTTPException(status_code=400, detail="SMS is already pending")
    
    sms.status = SMSStatus.PENDING
    sms.sent_at = None
    sms.error_message = None
    await db.commit()
    
    return {"status": "ok"}


@router.delete("/{sms_id}")
async def delete_sms(sms_id: int, admin: str = Depends(get_admin_api_key), db: AsyncSession = Depends(get_db)):
    sms = await db.get(SMSQueue, sms_id)
    if not sms:
        raise HTTPException(status_code=404, detail="SMS not found")
    
    await db.delete(sms)
    await db.commit()
    
    return {"status": "ok"}