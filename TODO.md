# TalkClock — план работ

Приложение «говорящие часы»: периодическое озвучивание времени через TTS по правилам-расписанию.
Стек: Kotlin (built-in AGP Kotlin), Coroutines, Jetpack Compose (MD3/Material You), Hilt, DataStore, AlarmManager + WorkManager.

## Архитектура
- `data/` — модели (TtsSettings, ScheduleRule), репозиторий на DataStore Preferences + org.json
- `domain/` — ScheduleEvaluator (следующий триггер: правила/молчание/полночь/fallback), SpeechComposer (префикс/постфикс/шаблон/regex)
- `speech/` — TtsManager (движок/поток Media|Notification/скорость), SpeakService (FGS shortService)
- `scheduling/` — AlarmScheduler, SpeakAlarmReceiver, BootReceiver, KeepAliveWorker (пропуски + извинение), SchedulingCoordinator
- `diag/` — DiagLogger (лог в files/diag.log — ColorOS прячет logcat приложений)
- `ui/` — Compose MD3: Главная / Правила / Настройки голоса, dynamic color

## Решения (согласовано с пользователем)
- Имя: TalkClock
- Пропущенная озвучка: Worker произносит извинение + время, опция в настройках (по умолчанию ВКЛ)
- Поток озвучки: Media (дефолт) / Notification
- Правила: [start, end), интервал, молчание, через полночь, вкл/выкл; fallback вне правил (5 мин)

## Статус
- [x] Анализ шаблона, версии (AGP 9.2.1 built-in KGP 2.2.10, KSP 2.3.12, Hilt 2.60.1, BOM 2026.09.00, compileSdk 37!)
- [x] Gradle-конфиг, иконка (adaptive вектор + monochrome + PNG API24-25)
- [x] Код: данные, домен, TTS, планировщик, UI, манифест
- [x] Юнит-тесты (22 теста — зелёные)
- [x] Сборка, установка, запуск на смартфоне (adb wireless 10.45.227.133:5555)
- [x] Фикс озвучки 3rd-party движков: `<queries>` TTS_SERVICE (package visibility API 30+)
- [x] Фикс UI: редактирование времени в правилах (клик-оверлей), FlowRow чипов интервала
- [x] Фикс «время молчания не работает»: границы суток считались по UTC-сетке, а правила — локальные; ScheduleEvaluator переписан на зонную арифметику (LocalDate+ZoneId, 2 регрессионных теста на Europe/Moscow)
- [x] Проверка живьём: ночь 01:25–07:47 без единой озвучки, будильник стоит на 09:10 (тик правила 09:10–09:20/4мин)
- [x] Свой интервал: чипы пресетов + «Своё…» (диалог 1–1440) и в правилах, и в fallback; склонения интервалов по-русски
- [x] Сортировка списка правил по времени начала (дисплей + хранилище при сохранении)
- [x] Фикс навигации: кнопка «Правила озвучки» и нижний навигатор — единый хелпер (popUpTo+saveState/restoreState); возврат через нижнюю панель работает всегда
- [x] GitHub: https://github.com/Igoryan94/android-talking-clock.git
- [x] Проверка живьём: будильники точные, озвучка ок (diag.log: utterance ok=true), Worker активен
- [ ] Подтверждение от пользователя: MultiTTS выбран в приложении и озвучивает (проверить diag.log)

## Текущий шаг
Все задачи сессии закрыты и подтверждены пользователем (сортировка, навигация, свой интервал, молчание, MultiTTS).
