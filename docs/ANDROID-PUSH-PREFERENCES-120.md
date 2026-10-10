# Уведомления обновления APK: исправление 1.20

## Причина

В 1.19 `PushHost.setUpdates` сначала вызывает `fencePreferences`: локальная session generation меняется для защиты от устаревших уведомлений. `PushApiContract` хранит capabilities по полному `PushScope`, включая generation. Проверка `supportsUpdateNotifications` для нового scope возвращает false из пустого кеша; обработчик молча завершался до PUT. Поэтому видимый переключатель обновлений не включался. Такой же cache miss при изменении другой категории мог декодировать updates как false и потерять это поле при сериализации.

Deployed backend 1.18 проверен отдельно: capability handler вернул schema1, delivery_configured=true, provider=fcm, update_notifications=true. Изолированные HTTP tests подтверждают PUT→новая DB session→GET, сохранение остальных полей, legacy omission, отключение и CSRF/org ограничения. Семейные preferences не читались и не менялись; реальный push не отправлялся.

## Исправление

Security fence сохраняется. Перед проверкой updates и чтением/сериализацией preferences приложение получает capabilities нового scope. Запросы и публикация состояния повторно проверяют scope после suspend, stale responses не открывают gate прежней сессии. Ошибка последнего чтения preferences показывает ERROR/retry, а не READY со старым значением.

## Проверки

Реальное нажатие на доступную строку `PushSettingsCard` с проверкой смены generation прошло: Host→authenticated HTTP PUT→независимое чтение SQLite→две новые GET при reload→пересозданный Host. `fixed-v3-first-ui-test.log`: **1 PASS, 3.517 sec**. `fixed-http-matrix.log`: **2 PASS, 27.555 sec** — остальные категории/master сохраняют updates, настоящий PUT500 не сохраняет и retry работает, denied/granted, регистрация code34, org/all-org, ошибка последнего GET, delayed response при смене org и logout не открывают прежний gate. Во всех случаях provider sends и финансовые операции равны нулю.

Изолированные backend tests: 2 PASS (полный payload, independent DB session/readback, legacy omission, CSRF/org). Unit-проверка исправления: 236 tests, 0 failures/errors/skips. Baseline API-контракт из1.19 с новой unit-регрессией: 52 tests / 1 expected failure; исправленный вариант52PASS.

Первые UI attempts не являются доказательством бизнес-ошибки: без профильно-подобной прокрутки tap не доставлялся; затем assertEnabled совпал с transient REGISTERING при ON_RESUME. Ожидание rendered enabled row и generation assertion исправили fixture. Эти неуспешные запуски отделены от трёх PASS.

Локальные свидетельства: `.local/push-settings-120/prod-capabilities.json`, `prod-readonly.log`, `investigation.json`, `backend-http-smoke.json`, `backend-fixture-v3-smoke.json`. Изолированный backend работает с синтетическими пользователем/организациями и SQLite, запрещает внешний network и provider sends. Производственный backend patch не требуется по подтверждённому контракту.

Тест использует настоящий Host/Card/API contract и production FastAPI routes с cookie/CSRF через loopback test transport. HTTPS-only ApiClient не ослаблялся: HTTP127.0.0.1 разрешён отдельным debug-only XML. Из signed APK извлечена network policy: cleartextTrafficPermitted=false, trust anchors только system. Физическая FCM delivery этим не проверяется и не запускалась.
