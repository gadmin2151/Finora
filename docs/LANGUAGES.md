# English and Russian · Русский и английский

[English guide](#en) · [Инструкция на русском](#ru) · [Screenshots / Скриншоты](SHOWCASE.md)

<a id="en"></a>

## English

Finora's web and native Android interfaces support **English** and **Russian**. You can choose a language before signing in; no administrator permission is needed.

### Choose a language

| Where | How |
| --- | --- |
| Web sign-in | Use **Language / Язык** above the sign-in form. |
| Web after sign-in | Open **My profile → Interface language**. |
| Android sign-in | Open the language selector at the top of the sign-in screen. |
| Android after sign-in | Tap your avatar and open **Language** in the profile. |

Choose **English**, **Русский**, or **Device language**. The interface updates when you select an option. You do not need to sign out or switch organizations.

### Automatic selection and memory

**Device language** is the default for a new browser or app installation. Finora checks the primary language reported by the browser or phone:

- Russian (`ru`, including regional variants): Russian interface.
- English or any other language: English interface.

For example, a phone set to Romanian opens Finora in English. If English is the primary language and Russian is a secondary language, the default remains English. Select **Русский** to override it.

A manual choice is saved **on that device**: in the web browser's local storage or the Android app's preferences. It is independent of the signed-in account and selected organization and remains after signing out. Changing one browser does not change a different browser or phone. Clearing site data or app data removes the saved choice. If browser storage is unavailable, the choice works for the current visit.

Choose **Device language** again to return to automatic selection. Language and light/dark appearance are separate preferences.

### What changes, and what stays yours

Buttons, menus, forms, accessibility labels, built-in messages and date/number formatting follow the selected language. The clients send the language with API requests so the server can return localized messages and reports.

**Your information is not translated:** organization and account names, category names, products, store names, notes, receipt originals and existing chat messages keep their original content. Changing language does not change currencies, exchange rates, financial amounts, permissions or records. Receipt recognition still reads the receipt's content independently of the interface language.

[English screenshot gallery](SHOWCASE.md#en) · [Russian screenshot gallery](SHOWCASE.md#ru) · [Back to README](../README.md)

### For contributors

Web messages use `t(...)` with Russian source keys and the English catalog in `web/src/locales/en.json`. Keep full phrases together and use numbered placeholders for user-supplied values. Static labels must be evaluated when rendered so changing language updates the screen. Canonical identifiers, units and stored financial data must remain independent of translated labels.

Android messages and language preferences live in `android/app/src/main/java/work/gadmin/finora/localization/`; `LanguageEnvironment` supplies the selected locale to the native interface. Keep preference storage separate from session and organization data.

The language preference requires no database migration. Check both languages when changing a screen, including longer labels, small screens and accessibility descriptions. Use fictional fixtures for screenshots; keep English images under `docs/assets/showcase/en/` and Russian images under `docs/assets/showcase/ru/`.

Run the web catalog and fallback checks with:

```bash
cd web
pnpm test:i18n
pnpm lint
pnpm typecheck
pnpm build
```

Also run the existing Android localization/unit tests and release checks when changing native screens. See the [developer guide](../CONTRIBUTING.md) for the full verification workflow.

<a id="ru"></a>

## Русский

Веб-интерфейс и нативное Android-приложение Finora поддерживают **русский** и **английский**. Выбрать язык можно до входа; права администратора для этого не нужны.

### Где переключить язык

| Где | Как |
| --- | --- |
| Вход на сайте | **Язык / Language** над формой входа. |
| Сайт после входа | **Мой профиль → Язык интерфейса**. |
| Вход на Android | Переключатель в верхней части экрана входа. |
| Android после входа | Аватар → **Язык** в профиле. |

Доступны **Русский**, **English** и **Язык устройства**. Интерфейс обновляется после выбора. Выходить из аккаунта или менять организацию не нужно.

### Автоматический выбор и сохранение

При первом открытии выбран **Язык устройства**. Finora проверяет основной язык, который сообщает браузер или телефон:

- Русский (`ru`, включая региональные варианты) — интерфейс на русском.
- Английский или любой другой язык — интерфейс на английском.

Например, на телефоне с румынским языком Finora откроется на английском. Если основным выбран английский, а русский указан вторым, автоматически используется английский. Для ручного переключения выберите **Русский**.

Выбор сохраняется **на этом устройстве**: в локальном хранилище браузера или настройках Android-приложения. Он не зависит от аккаунта и организации и остаётся после выхода. Настройка в одном браузере не меняет язык другого браузера или телефона. Очистка данных сайта или приложения удаляет сохранённый выбор. Если браузер блокирует локальное хранилище, переключение работает в течение текущего посещения.

Чтобы снова следовать устройству, выберите **Язык устройства**. Язык и светлая/тёмная тема настраиваются независимо.

### Что переводится

На выбранном языке отображаются кнопки, меню, формы, подписи доступности, встроенные сообщения, даты и числа. Клиенты передают язык в API-запросах, чтобы сервер мог вернуть соответствующие сообщения и отчёты.

**Ваши данные не переводятся:** названия организаций, счетов, категорий, товаров и магазинов, примечания, оригиналы чеков и существующие сообщения чата остаются в исходном виде. Переключение не меняет валюты, курсы, суммы, права доступа или записи учёта. Язык интерфейса не определяет язык распознаваемого чека.

[Скриншоты на русском](SHOWCASE.md#ru) · [Скриншоты на английском](SHOWCASE.md#en) · [Вернуться к инструкции](../README.ru.md)

### Для разработчиков

Веб использует `t(...)` с русским исходным текстом и английский словарь `web/src/locales/en.json`. Переводите цельные фразы; пользовательские значения передавайте через нумерованные параметры. Подписи должны вычисляться при отображении, чтобы реагировать на смену языка. Идентификаторы, единицы хранения и финансовые данные не должны зависеть от перевода.

Сообщения и настройки Android находятся в `android/app/src/main/java/work/gadmin/finora/localization/`; `LanguageEnvironment` передаёт выбранную локаль нативному интерфейсу. Хранение языка отделено от сеанса и организации. Для настройки языка миграция БД не требуется.

Проверяйте оба языка, длинные подписи, узкие экраны и описания доступности. Скриншоты создавайте с вымышленными данными: английские — в `docs/assets/showcase/en/`, русские — в `docs/assets/showcase/ru/`. Команды проверок веба приведены выше; для Android используйте проверки локализации, unit-тесты и release-проверки из [инструкции разработчика](../CONTRIBUTING.md).
