# workflow.md — TalkClock

## Project Structure
- Single module `:app`, namespace `com.dragonfly.talkingclock`, Kotlin через **built-in Kotlin AGP 9** (`org.jetbrains.kotlin.android` применять НЕЛЬЗЯ — сборка падает; KGP встроен: 2.2.10).
- Version catalog: `gradle/libs.versions.toml`. Compose-плагин обязан совпадать с встроенным KGP (2.2.10).
- compileSdk 37 (платформа android-37.0 в SDK; новые androidx 2026 требуют 37), minSdk 24, targetSdk 36, desugaring включён (java.time на API 24).

## Feature Development
- Правки UI: Compose M3; readOnly-поля «с кликом» делать через Box-оверлей `matchParentSize().clickable{}` поверх OutlinedTextField (сам TextField глотает клики).
- Списки чипов в карточках: FlowRow (ExperimentalLayoutApi), Row обрезается без скролла.
- Все фоновые правки проходят через `SchedulingCoordinator.apply()` → `AlarmScheduler.reschedule()`; ресивер после каждой озвучки перепланируется сам.
- Одноразовые правила (в т.ч. молчание): живут весь период [start,end) и удаляются через минуту после его конца, НЕ после первого срабатывания. `ScheduleEvaluator.expiredOneShotIds/nextOneShotExpiry`; `AlarmScheduler` чистит протухшие и ставит отдельный cleanup-будильник (requestCode 4002, extra `cleanup_only`), `SpeakAlarmReceiver` по нему только вызывает `reschedule()`.
- Новое правило по умолчанию: начало = текущее время, конец = +30 мин.
- Контекстное меню карточки правила (long-press): «Раздвоить», «Дублировать» (копия с `oneShot = true` и якорем = сейчас), «Объединить…».
- Одноразовое правило: якорь создания `oneShotAnchorMinute` (0=legacy); цель удаления = первое вхождение с концом строго после якоря + 1 мин. В JSON ключ пишется только при >0.
- Порог громкости: `VolumeGuardConfig` в `AppSettings` (DataStore prefs `volume_guard_*`); `speech/VolumeGuard.kt` поднимает STREAM_MUSIC+STREAM_NOTIFICATION ниже порога на время озвучки и возвращает; авто-выкл по истечении срока — в `AlarmScheduler.reschedule()`.
- ColorOS/Realme игнорирует `setStreamVolume` из фона (в dumpsys нет записи `from com.dragonfly.talkingclock`; есть `AudioHardening`). Поэтому `VolumeGuard` верифицирует результат `getStreamVolume`, ретраит 3× и фолбэчится `adjustStreamVolume(ADJUST_RAISE)`; всё на main-треде; восстановление под `NonCancellable`. Требует `MODIFY_AUDIO_SETTINGS`. Диагностика фактического срабатывания — в diag.log (`MEDIA: 0 -> 30 (raised=...)`).
- Логи фона: `DiagLogger` пишет в `files/diag.log` — ColorOS на устройстве прячет logcat сторонних приложений, diag-файл читается через `run-as`.

## Running And Logs
- Устройство: Realme/ColorOS, adb wireless `10.45.227.133:5555` (если пусто — скрипт `C:\Users\Igor\Desktop\ADB-connect.ps1`).
- **Всегда устанавливать свежий APK после правок** (`adb install -r ...app-debug.apk`) — это не автотест, противоречия с запретом нет; запуск приложения и управление смартфоном — только с явного разрешения пользователя, тесты вручную.
- UI-верификация без рук: `adb shell uiautomator dump /sdcard/wd.xml` + `exec-out cat` в файл, читать файл ОБЯЗАТЕЛЬНО как UTF-8 ([IO.File]::ReadAllText(path, UTF8)) — консольный конвейер PS 5.1 портит кириллицу (cp866), поиск по тексту дампа молча фейлится; тапы — `input tap x y` (скрипт-хелпер: `C:\Users\Igor\AppData\Local\Temp\opencode\tap.ps1`).
- Сборка+деплой+запуск: `cmd /c "gradlew.bat assembleDebug --console=plain"` → `adb -s <dev> install -r app\build\outputs\apk\debug\app-debug.apk` → `adb -s <dev> shell am start -n com.dragonfly.talkingclock/.MainActivity`.
- Тесты: `cmd /c "gradlew.bat testDebugUnitTest --console=plain"`.
- Диагностика рантайма: `adb -s <dev> shell "run-as com.dragonfly.talkingclock cat files/diag.log"`.
- Состояние будильников: `adb shell "dumpsys alarm | grep -A 3 talkingclock"`; Worker: `dumpsys jobscheduler | grep -A 2 talkingclock`.
- PowerShell: НЕ оборачивать gradlew в `powershell -Command` (падает ChildProcess.kill); использовать `cmd /c "gradlew.bat ..."`.

## Debugging And Error Fixes
- **Compose TextField + async DataStore-эхо ломает ввод** (курсор прыгает назад, «Время»→«ремяВ»): нельзя вешать `value = flow.value` (String) при персисте через Flow — отложенное эхо перезаписывает поле посреди набора. Лечение: локальное состояние `TextFieldValue` + `rememberSaveable(stateSaver = TextFieldValue.Saver)`, персист в onValueChange, эхо в поле не подавать (см. `PersistedTextField` в TtsSettingsScreen).
- **AudioFocusRequest.Builder**: метод называется `setOnAudioFocusChangeListener(listener, Handler)` (НЕ `setOnAudioFocusListener`); сверять реальный API через `javap -classpath android.jar android.media.AudioFocusRequest$Builder` (платформа android-37.0).
- **«Unknown: ChildProcess.kill» от обёртки gradlew** может прийти ПОСЛЕ успешной сборки — сначала проверять, что задача реально выполнилась (UP-TO-DATE/вывод), потом перезапускать.
- Скролл к элементу LazyColumn: индексы считаются по всем item'ам до целевого (timeline=0, заголовок=1, правила с 2) → `listState.animateScrollToItem(2 + index)`; если контент помещается на экран — скролл визуально ничего не делает.
- **Расписание «не работает» при рабочей записи в DataStore**: границы суток нельзя вычислять как `floorDiv(epochMinute, 1440)` — это UTC-сетка, а правила (start/end минуты суток) — локальные. В зоне ≠ UTC молчание ночью не совпадает и fallback тикает каждую минуту. Чинится зонной арифметикой: `Instant.ofEpochSecond(m*60).atZone(zone).toLocalDate()` → `date.atStartOfDay(zone)`; у `nextSpeakTrigger` параметр `zone` (для тестов — UTC). Тесты обязательно включают кейс с зоной ≠ UTC (Europe/Moscow).
- **org.json в JVM-тестах** — заглушка ("not mocked"): в `app/build.gradle.kts` добавлять `testImplementation("org.json:json:20260814")`; JSON-логику выносить в чистые функции (`data/RulesJson.kt`), не внутрь репозитория.
- Потеря импорта при «безобидной» правке imports (например, `javax.inject.Singleton`) даёт загадочный KSP error `InjectProcessingStep was unable to process ... could not be resolved` — проверить импорты над классом.
- **3rd-party TTS движок не инициализируется** (MultiTTS): в манифесте нужен `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>` — package visibility с API 30. Проверять: `aapt2 dump xmltree --file AndroidManifest.xml <apk>`.
- AAR metadata «requires compileSdk 37» → поднять compileSdk (платформа должна стоять в SDK).
- `EngineInfo` — вложенный класс: `TextToSpeech.EngineInfo`.
- TTS init блокирующий — вызывать только из Dispatchers.IO (OnInit приходит в main thread).
- `floorDiv` для Long из `kotlin.math` — unresolved; писать вручную.
- FGS `shortService` стартует и из фона через будильник (temp allowlist) — работает.

## Tests And Validation
- Юнит-тесты домена: `ScheduleEvaluatorTest` (сценарий пользователя: 00:30-08:00 молчать и т.д.), `SpeechComposerTest`.
- Проверка живьём: diag.log должен показывать `alarm fired → SpeakService onStart → init OK → utterance finished ok=true` и перепостановку будильника.
