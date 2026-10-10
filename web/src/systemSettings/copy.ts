export const groups: Record<string, [string, string, string, string]> = {
  firebase: [
    "Firebase и push",
    "Firebase and push",
    "Доставка уведомлений и учётные данные сервера. Пользовательские подписки настраиваются отдельно.",
    "Notification delivery and server credentials. User subscriptions are managed separately.",
  ],
  ai: [
    "AI и провайдеры",
    "AI and providers",
    "Общие параметры выполнения и резервный ключ. Настройки AI организаций остаются отдельными.",
    "Runtime limits and a fallback key. Organization AI preferences remain separate.",
  ],
  ocr: [
    "Распознавание чеков",
    "Receipt recognition",
    "Параметры распознавания и ограничения файлов.",
    "Recognition settings and file limits.",
  ],
  runtime: [
    "Работа сервера",
    "Server runtime",
    "Общие ограничения и параметры выполнения.",
    "Shared limits and execution settings.",
  ],
  infrastructure: [
    "Инфраструктура",
    "Infrastructure",
    "Данные для проверки состояния. Параметры развёртывания здесь не изменяются.",
    "Status information. Deployment settings cannot be changed here.",
  ],
};
export const fields: Record<string, [string, string, string, string]> = {
  push_enabled: [
    "Доставка push-уведомлений",
    "Push notification delivery",
    "Включает серверную доставку. Подписки пользователей и разрешение Android остаются обязательными.",
    "Enables server delivery. User subscriptions and Android permission are still required.",
  ],
  push_worker_enabled: [
    "Обработка очереди push",
    "Push queue worker",
    "Разрешает обработку очереди на границе следующей задачи.",
    "Allows queued work at the next task boundary.",
  ],
  push_fcm_project_id: [
    "Firebase Project ID",
    "Firebase Project ID",
    "Должен совпадать с проектом сервисного аккаунта и Android-приложения.",
    "Must match the service account and Android app project.",
  ],
  firebase_service_account: [
    "Сервисный аккаунт Firebase · JSON",
    "Firebase service account · JSON",
    "Учётные данные только для сервера. После сохранения значение не возвращается в интерфейс.",
    "Server credentials only. The saved value is never returned to the interface.",
  ],
  global_openai_key: [
    "Резервный API-ключ OpenAI",
    "Fallback OpenAI API key",
    "Используется только организациями с выбранным OpenAI, у которых нет собственного ключа.",
    "Only used by organizations that selected OpenAI and have no organization key.",
  ],
  ai_timeout: [
    "Таймаут AI · секунды",
    "AI timeout · seconds",
    "Максимальное время ожидания ответа провайдера.",
    "Maximum time to wait for a provider response.",
  ],
  ai_cpu_threads: [
    "Потоки CPU для AI",
    "AI CPU threads",
    "Количество потоков для локальных AI-задач.",
    "Thread count for local AI work.",
  ],
  max_upload_mb: [
    "Максимальный размер загрузки · МБ",
    "Maximum upload size · MB",
    "Ограничение размера файла; ограничения прокси могут быть ниже.",
    "File size limit; reverse proxy limits may be lower.",
  ],
  mev_browser: [
    "Браузер для распознавания MEV",
    "MEV recognition browser",
    "Разрешает серверу использовать установленный браузер для распознавания MEV.",
    "Allows the server to use its installed browser for MEV recognition.",
  ],
};
