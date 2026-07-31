from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from server.database import Device, SMSQueue, SMSStatus, DeviceStatus


async def get_queue_stats(db: AsyncSession) -> dict:
    total_devices = await db.execute(select(func.count(Device.id)))
    online_devices = await db.execute(select(func.count(Device.id)).where(Device.status == DeviceStatus.ONLINE))
    offline_devices = await db.execute(select(func.count(Device.id)).where(Device.status == DeviceStatus.OFFLINE))
    never_connected = await db.execute(select(func.count(Device.id)).where(Device.status == DeviceStatus.NEVER_CONNECTED))
    
    total_sms = await db.execute(select(func.count(SMSQueue.id)))
    pending_sms = await db.execute(select(func.count(SMSQueue.id)).where(SMSQueue.status == SMSStatus.PENDING))
    sent_sms = await db.execute(select(func.count(SMSQueue.id)).where(SMSQueue.status == SMSStatus.SENT))
    failed_sms = await db.execute(select(func.count(SMSQueue.id)).where(SMSQueue.status == SMSStatus.FAILED))
    delivered_sms = await db.execute(select(func.count(SMSQueue.id)).where(SMSQueue.status == SMSStatus.DELIVERED))
    
    return {
        "devices": {
            "total": total_devices.scalar(),
            "online": online_devices.scalar(),
            "offline": offline_devices.scalar(),
            "never_connected": never_connected.scalar(),
        },
        "sms": {
            "total": total_sms.scalar(),
            "pending": pending_sms.scalar(),
            "sent": sent_sms.scalar(),
            "failed": failed_sms.scalar(),
            "delivered": delivered_sms.scalar(),
        },
    }


async def cleanup_old_sms(db: AsyncSession, days: int = 30):
    from datetime import datetime, timedelta
    cutoff = datetime.utcnow() - timedelta(days=days)
    result = await db.execute(
        select(SMSQueue).where(SMSQueue.created_at < cutoff).where(SMSQueue.status.in_([SMSStatus.SENT, SMSStatus.FAILED, SMSStatus.DELIVERED]))
    )
    for sms in result.scalars().all():
        await db.delete(sms)
    await db.commit()