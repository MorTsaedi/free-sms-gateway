from sqlalchemy import (
    Column, Integer, String, DateTime, Text, ForeignKey, Enum, Boolean, Index
)
from sqlalchemy.orm import relationship, declarative_base, sessionmaker
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.sql import func
from datetime import datetime
import enum
import os

from server.config import settings

Base = declarative_base()


class DeviceStatus(str, enum.Enum):
    ONLINE = "online"
    OFFLINE = "offline"
    NEVER_CONNECTED = "never_connected"


class SMSStatus(str, enum.Enum):
    PENDING = "pending"
    SENT = "sent"
    FAILED = "failed"
    DELIVERED = "delivered"


class Device(Base):
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    api_key_hash = Column(String(255), nullable=False, unique=True, index=True)
    last_seen = Column(DateTime, nullable=True)
    status = Column(Enum(DeviceStatus), default=DeviceStatus.NEVER_CONNECTED, nullable=False)
    config_json = Column(Text, nullable=True)
    created_at = Column(DateTime, default=func.now(), nullable=False)

    sms_queue = relationship("SMSQueue", back_populates="device", cascade="all, delete-orphan")


class SMSQueue(Base):
    __tablename__ = "sms_queue"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("devices.id", ondelete="CASCADE"), nullable=False, index=True)
    to_number = Column(String(50), nullable=False)
    message = Column(Text, nullable=False)
    status = Column(Enum(SMSStatus), default=SMSStatus.PENDING, nullable=False, index=True)
    created_at = Column(DateTime, default=func.now(), nullable=False)
    sent_at = Column(DateTime, nullable=True)
    error_message = Column(Text, nullable=True)

    device = relationship("Device", back_populates="sms_queue")


class APIKey(Base):
    __tablename__ = "api_keys"

    id = Column(Integer, primary_key=True, index=True)
    key_hash = Column(String(255), nullable=False, unique=True, index=True)
    name = Column(String(100), nullable=False)
    created_at = Column(DateTime, default=func.now(), nullable=False)


class DownloadToken(Base):
    __tablename__ = "download_tokens"

    id = Column(Integer, primary_key=True, index=True)
    token = Column(String(64), nullable=False, unique=True, index=True)
    apk_path = Column(String(255), nullable=False)
    created_at = Column(DateTime, default=func.now(), nullable=False)
    expires_at = Column(DateTime, nullable=False)
    used = Column(Boolean, default=False, nullable=False)


engine = create_async_engine(
    settings.database_url.replace("sqlite://", "sqlite+aiosqlite://"),
    echo=False,
    future=True
)

AsyncSessionLocal = sessionmaker(
    engine, class_=AsyncSession, expire_on_commit=False
)


async def get_db():
    async with AsyncSessionLocal() as session:
        try:
            yield session
        finally:
            await session.close()


async def init_db():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)