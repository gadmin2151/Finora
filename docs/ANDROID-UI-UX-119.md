# Android 1.19 · проверка UI/UX

Версия 1.19.0 / code33 исправляет видимость и размещение существующих элементов. Backend и финансовые контракты остаются на 1.18. Проверки выполнялись на пустых disposable эмуляторах Android35 с вымышленными данными, без финансовых операций, входа под семейными аккаунтами и отправки push.

## Исправления

Выключенный switch в 1.18 использовал одинаковые роли для дорожки, бегунка и фона карточки: на actual screenshots он полностью исчезал. У переключателей теперь отдельные контрастные цвета, контур и галочка включённого состояния. Подпись переносится, switch сохраняет слот56dp, вся строка имеет минимум56dp и одну accessibility-семантику с названием, состоянием и disabled. Аналогичный toggle исправлен в коррекции баланса; checkbox закрытых долгов получил общую с подписью область нажатия. Outline интерактивных Material controls отделён от декоративной границы.

Название и остаток счёта в кошельке занимают полную ширину; действие коррекции расположено ниже. Нижняя навигация переносит подписи на две строки. В основном финансовом экране убрана повторная панель «Доходы/Долги», основная панель пяти разделов сохранена. Длинные подтверждения ledger, отмены дохода, чека, счёта и выхода прокручиваются, без обрезания суммы или предупреждения.

[До: выключенные элементы исчезли](images/ui-119/notifications-before.png) · [После: on/off различимы](images/ui-119/notifications-after.png) · [Кошелёк при крупном шрифте](images/ui-119/wallet-large.png) · [Сумма и предупреждение после прокрутки](images/ui-119/confirmation-large-scroll.png).

## Фактически проверенный scope

| Проверка | Подтверждение |
|---|---|
| Actual PushSettingsCard: Classic, White, Material Black, System day/night; шрифт1.0/1.8, ширина320dp; checked/unchecked/disabled checked+unchecked | 40 final screenshots в `.local/ui-ux-119/all-states`; визуально просмотрены цвета, контуры и переносы, callback/семантика проверены отдельно |
| Overview/счета, пять финансовых вкладок, empty Accounts/Reports/Purchases/Chat, loading/error, форма дохода с реальной IME и Back, длинный ledger confirmation | 57 final-v2 screenshots, 19 на каждую Classic/White/Black, 360dp/font1.8; `.local/ui-ux-119-worker/evidence/visual-audit` |
| Первоначальная матрица обычного и увеличенного шрифта | Исторические screenshots worker при font1.0/1.3; их Wallet/Nav дефекты исправлены и перепроверены final-v2 |
| Touch targets, одна подписанная toggle-семантика, disabled controls, explicit permission, theme persistence, dialog scroll | `final-controls-tests.log`: 9 PASS; расширенная state matrix `all-toggle-states-tests.log`: 3 PASS (повтор тех же трёх, не суммируются) |
| Native planning/roles/confirmations, navigation, updater, receipt summary | `native-regression-tests.log`: 27 PASS; все callbacks и данные локальные |
| Сборка и кодовые регрессии | 235 unit PASS, signed build/checkKotlinFormat PASS, release lint0errors/24existing warnings |
| Подпись/upgrade/startup | Прежний сертификат; установка1.18→1.19 Success; signed offline login/MLKit3/OpenCV PASS на обычном viewport |

На320dp начальный signed login требует прокрутки: smoke, ожидавший сразу видимую кнопку Sign in, завершился failure. Реальный swipe показал доступные поля и кнопку; повтор на обычном viewport прошёл. Это не успешный первоначальный smoke. Login не выполнялся.

Визуальные матрицы ActivityScenario не являются PASS instrumentation: ранний small Showcase failed из-за отсутствия прокрутки к offscreen строке и timeout DESTROYED; worker final-v2 получил exit255 после всех57 изображений без JUnit summary. Отдельный повтор на основном emulator воспроизвёл именно ActivityScenario.close timeout DESTROYED; platform dumpsys после finish показывает launcher и отсутствие Activity Finora, logcat содержит TestRunner teardown assertion. Причина рассогласования monitor lifecycle глубже не установлена; это не полный passing прогон. Эти результаты не включены в36 PASS отдельных checks. Pixel review и результаты тестов отделены. Screenshot fixture имитирует пустые/загруженные состояния; доступ к API и полнота реальных account/report данных этим не подтверждаются.

Не утверждается проверка всего APK: физический телефон/TalkBack speech, все формы и финансовые E2E с сервером, камера на реальном устройстве, offline write recovery и новые push delivery не проходили этот UI scope. Крупный шрифт может потребовать прокрутки и переноса слов в нижней навигации. Stub notification capture не проверяет системные панели; реальные MainActivity captures проверялись отдельно.

## Поставка и откат

Signed APK SHA256 `65ad18d5a6162a71114282079cd510b26f9972a9c57171c6436eba3a2f62a4ae`, 172682067 bytes, прежний signer `3a3c14d603cc9cfeff5092ece5101acc60512787e90f1330292d98e4f45ee6c0`.

Поставка меняет только публичные APK/manifest, с no-replace APK и atomic manifest. Manifest1.18 и hash APK сохраняются до замены; encrypted DB/docs backup1.18 проверяется по прежнему подтверждённому SHA256. Backend, services, schema, FCM/секреты и данные не меняются. Rollback manifest: `/opt/finora-ui-release-1.19.0-20261010/restore-manifest.py`; APK1.18 сохраняется. Новая release attestation для push scheduler не создаётся в этой UI задаче; push не отправляется.
