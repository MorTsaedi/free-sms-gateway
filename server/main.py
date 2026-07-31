from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
import structlog

from server.config import settings
from server.database import init_db
from server.routes import device, sms, admin, admin_api, apk


structlog.configure(
    processors=[
        structlog.stdlib.filter_by_level,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.stdlib.PositionalArgumentsFormatter(),
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.UnicodeDecoder(),
        structlog.processors.JSONRenderer(),
    ],
    wrapper_class=structlog.stdlib.BoundLogger,
    logger_factory=structlog.stdlib.LoggerFactory(),
    cache_logger_on_first_use=True,
)

logger = structlog.get_logger()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting SMS Gateway server")
    await init_db()
    logger.info("Database initialized")
    yield
    logger.info("Shutting down SMS Gateway server")


app = FastAPI(
    title="SMS Gateway",
    description="Self-hosted SMS gateway with Android app",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.mount("/static", StaticFiles(directory="static"), name="static")

app.include_router(device.router)
app.include_router(sms.router)
app.include_router(admin.router)
app.include_router(admin_api.router)
app.include_router(apk.router)


@app.get("/health")
async def health_check():
    return {"status": "healthy", "service": "sms-gateway"}


@app.get("/")
async def root():
    return {"service": "SMS Gateway", "version": "1.0.0", "docs": "/docs"}