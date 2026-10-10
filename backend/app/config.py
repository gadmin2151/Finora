from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    database_url: str
    secret_key: str = Field(min_length=32)
    app_url: str = "http://localhost:8088"
    cookie_secure: bool = False
    data_dir: Path = Path("/data")
    ollama_url: str = "http://ollama:11434"
    ai_timeout: int = Field(default=480, ge=15, le=600)
    ai_cpu_threads: int = Field(default=4, ge=1, le=64)
    admin_username: str = "admin"
    admin_password_file: str = "/run/secrets/admin_password"
    session_days: int = 14
    max_upload_mb: int = 15
    mev_browser: bool = True
    push_enabled: bool = False
    push_worker_enabled: bool = False
    push_fcm_project_id: str = ""
    openai_api_key: str | None = Field(default=None, repr=False)
    google_application_credentials: str | None = Field(default=None, repr=False)
    testing: bool = False


@lru_cache
def settings() -> Settings:
    return Settings()
