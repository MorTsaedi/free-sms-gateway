from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from datetime import datetime
import os

from server.database import get_db, DownloadToken
from server.config import settings
from server.auth import get_admin_api_key

router = APIRouter(prefix="/api/v1/apk", tags=["apk"])


@router.get("/download/{token}")
async def download_apk(token: str, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(DownloadToken).where(DownloadToken.token == token))
    download_token = result.scalar_one_or_none()
    
    if not download_token:
        raise HTTPException(status_code=404, detail="Invalid or expired download token")

    if download_token.expires_at < datetime.utcnow():
        raise HTTPException(status_code=410, detail="Download token expired")

    if not os.path.exists(download_token.apk_path):
        raise HTTPException(status_code=404, detail="APK file not found")

    return FileResponse(
        download_token.apk_path,
        media_type="application/vnd.android.package-archive",
        filename="sms-gateway.apk"
    )


@router.get("/build-config")
async def get_build_config(admin: str = Depends(get_admin_api_key)):
    from server.config import build_config
    return {
        "vm": {
            "url": build_config.vm_url,
            "api_key": build_config.vm_api_key,
        },
        "app": {
            "package_name": build_config.package_name,
            "app_name": build_config.app_name,
            "poll_interval_seconds": build_config.poll_interval_seconds,
            "heartbeat_interval_seconds": build_config.heartbeat_interval_seconds,
        },
        "build": {
            "keystore": build_config.keystore,
        },
    }