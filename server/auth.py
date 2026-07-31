from fastapi import HTTPException, Security, Depends
from fastapi.security import APIKeyHeader
from sqlalchemy import select
from passlib.context import CryptContext
import secrets

from server.database import AsyncSessionLocal, APIKey, Device
from server.config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)
device_api_key_header = APIKeyHeader(name="X-Device-API-Key", auto_error=False)


def hash_key(key: str) -> str:
    return pwd_context.hash(key)


def verify_key(plain_key: str, hashed_key: str) -> bool:
    return pwd_context.verify(plain_key, hashed_key)


def generate_api_key() -> str:
    return secrets.token_urlsafe(32)


async def get_admin_api_key(api_key: str = Security(api_key_header)) -> APIKey:
    if not api_key:
        raise HTTPException(status_code=401, detail="API key required")
    
    async with AsyncSessionLocal() as db:
        result = await db.execute(select(APIKey))
        for key_obj in result.scalars().all():
            if verify_key(api_key, key_obj.key_hash):
                return key_obj
    
    raise HTTPException(status_code=401, detail="Invalid API key")


async def get_device_api_key(api_key: str = Security(device_api_key_header)) -> Device:
    if not api_key:
        raise HTTPException(status_code=401, detail="Device API key required")
    
    async with AsyncSessionLocal() as db:
        result = await db.execute(select(Device))
        for device in result.scalars().all():
            if verify_key(api_key, device.api_key_hash):
                return device
    
    raise HTTPException(status_code=401, detail="Invalid device API key")


async def get_optional_device(device: Device = Depends(get_device_api_key)) -> Device:
    return device