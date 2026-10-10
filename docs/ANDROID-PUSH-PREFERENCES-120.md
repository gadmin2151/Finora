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

Контролируемый UI baseline-v3 с точными `setUpdates` и API contract из commit d66ce09 (1.19), сохранив только test seams, воспроизвёл дефект: rendered row enabled, настоящий performClick и generation assertion прошли, затем ожидание PUT/persist на строке119 завершилось timeout. Независимый DB readback оставался updates=false. На исправленном коде тот же тест прошёл. Signed APK связан с production source commit `53cc23c886bae5b2aea9464ece7206af5ce4305f`; baseline debug code восстановлен после сборки, working tree чист.

## Поставка

[Signed APK1.20.0/code34](https://finora.gadmin.work/assets/mobile/finora-1.20.0.apk), 172682067bytes, SHA256 `5ec789d5d5b4de81b54156b86dfb733052586c8ce57f0f11f74927ccaba400e5`. Прежний сертификат сохранён. Точный signed upgrade1.19→1.20 без удаления между версиями, offline login/MLKit3/OpenCV PASS. Full public HTTPS download hash/size и manifest code34 совпали, health200, старыйAPK1.19 доступен. Signed build/checkKotlinFormat PASS; 236unit0fail/errors/skips; lint0errors/24existingwarnings.

Backend1.18, контейнеры/startedAt, данные/секреты и push activation не менялись. Перед static replacement сохранён manifest1.19; известный encrypted DB/docs backup1.18 повторно проверен по SHA256 (новый DB snapshot для APK-only публикации не создавался). Rollback: `python3 /opt/finora-ui-release-1.20.0-20261010/restore-manifest.py` возвращает manifest1.19, сохраняя APK и не трогая БД. Release attestation/update push не создавались, реальный push не отправлялся.

Evidence: `.local/push-settings-120/{fixed-v3-first-ui-test.log,fixed-http-matrix.log,baseline-v3-ui-test.log,baseline-v3-db-readback.json,exact-signed-upgrade.json,source-apk-binding.json,release-network-policy.txt,publication.json,public-release-120-verification.json,backend-final-evidence.json}`. QA emulator5580 и loopback fixture18083 остановлены. Физическая FCM delivery и семейный end-to-end сценарий не тестировались.
