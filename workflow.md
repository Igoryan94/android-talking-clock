# workflow.md — TalkClock

## Project Structure
- Single module `:app`, namespace `com.dragonfly.talkingclock`, Kotlin через **built-in Kotlin AGP 9** (`org.jetbrains.kotlin.android` применять НЕЛЬЗЯ — сборка падает; KGP встроен: 2.2.10).
- Version catalog: `gradle/libs.versions.toml`. Compose-плагин обязан совпадать с встроенным KGP (2.2.10).
- compileSdk 37 (платформа android-37.0 в SDK; новые androidx 2026 требуют 37), minSdk 24, targetSdk 36, desugaring включён (java.time на API 24).

## Feature Development
- Правки UI: Compose M3; readOnly-поля «с кликом» делать через Box-оверлей `matchParentSize().clickable{}` поверх OutlinedTextField (сам TextField глотает клики).
- Списки чипов в карточках: FlowRow (ExperimentalLayoutApi), Row обрезается без скролла.
- Все фоновые правки проходят через `SchedulingCoordinator.apply()` → `AlarmScheduler.reschedule()`; ресивер после каждой озвучки перепланируется сам.
- Логи фона: `DiagLogger` пишет в `files/diag.log` — ColorOS на устройстве прячет logcat сторонних приложений, diag-файл читается через `run-as`.

## Running And Logs
- Устройство: Realme/ColorOS, adb wireless `10.45.227.133:5555` (если пусто — скрипт `C:\Users\Igor\Desktop\ADB-connect.ps1`).
- Сборка+деплой+запуск: `cmd /c "gradlew.bat assembleDebug --console=plain"` → `adb -s <dev> install -r app\build\outputs\apk\debug\app-debug.apk` → `adb -s <dev> shell am start -n com.dragonfly.talkingclock/.MainActivity`.
- Тесты: `cmd /c "gradlew.bat testDebugUnitTest --console=plain"`.
- Диагностика рантайма: `adb -s <dev> shell "run-as com.dragonfly.talkingclock cat files/diag.log"`.
- Состояние будильников: `adb shell "dumpsys alarm | grep -A 3 talkingclock"`; Worker: `dumpsys jobscheduler | grep -A 2 talkingclock`.
- PowerShell: НЕ оборачивать gradlew в `powershell -Command` (падает ChildProcess.kill); использовать `cmd /c "gradlew.bat ..."`.

## Debugging And Error Fixes
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
