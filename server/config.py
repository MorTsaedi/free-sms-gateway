from pydantic_settings import BaseSettings
from pydantic import Field
from typing import Optional
import yaml


class Settings(BaseSettings):
    api_key: str = Field(..., alias="API_KEY")
    secret_key: str = Field(..., alias="SECRET_KEY")
    vm_public_url: str = Field(default="http://localhost", alias="VM_PUBLIC_URL")
    database_url: str = Field(default="sqlite:///data/sms_gateway.db", alias="DATABASE_URL")

    class Config:
        env_file = ".env"
        case_sensitive = False


class BuildConfig:
    def __init__(self, path: str = "build_config.yaml"):
        self.path = path
        self._config = {}
        self.load()

    def load(self):
        try:
            with open(self.path, "r") as f:
                self._config = yaml.safe_load(f) or {}
        except FileNotFoundError:
            self._config = {}

    def get(self, key: str, default=None):
        keys = key.split(".")
        val = self._config
        for k in keys:
            if isinstance(val, dict):
                val = val.get(k)
            else:
                return default
            if val is None:
                return default
        return val

    def set(self, key: str, value):
        keys = key.split(".")
        val = self._config
        for k in keys[:-1]:
            val = val.setdefault(k, {})
        val[keys[-1]] = value
        self.save()

    def save(self):
        with open(self.path, "w") as f:
            yaml.dump(self._config, f)

    @property
    def vm_url(self) -> str:
        return self.get("vm.url", "http://localhost:8000")

    @property
    def vm_api_key(self) -> str:
        return self.get("vm.api_key", "auto-generated-key")

    @property
    def package_name(self) -> str:
        return self.get("app.package_name", "com.smsgateway")

    @property
    def app_name(self) -> str:
        return self.get("app.app_name", "SMS Gateway")

    @property
    def poll_interval_seconds(self) -> int:
        return self.get("app.poll_interval_seconds", 15)

    @property
    def heartbeat_interval_seconds(self) -> int:
        return self.get("app.heartbeat_interval_seconds", 60)

    @property
    def keystore(self) -> str:
        return self.get("build.keystore", "debug")


settings = Settings()
build_config = BuildConfig()