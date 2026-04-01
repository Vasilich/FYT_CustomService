# FYT custom service

`FYT custom service` — это Android-приложение для головных устройств на базе FYT с Android 13 (класс UIS7870). Оно запускает foreground-сервис с sticky-поведением перезапуска и может выполнять пользовательские ветки логики по числовым параметрам, полученным через intents.

## Что делает приложение
- Запускает постоянный foreground-сервис (`START_STICKY` + постоянное уведомление).
- Использует headless launcher activity для FYT autostart-триггеров (экран настроек не показывается, если сервис уже запущен).
- Сохраняет ручной доступ к настройкам через отдельный ярлык: `FYT custom service settings`.
- Слушает пользовательские broadcast intents с числовыми параметрами.
- Выполняет логику по коду команды в `FytForegroundService.executeCommand(...)`.
- Обрабатывает ACC-события FYT:
  - `com.fyt.boot.ACCOFF`: сохраняет пакет текущего медиаплеера, затем отправляет `PAUSE`.
  - `com.fyt.boot.ACCON`: запускает сохранённый плеер, ждёт настраиваемую задержку (по умолчанию 2 секунды), отправляет `PLAY`, затем восстанавливает приложение, которое было на переднем плане до запуска плеера.
- Поддерживает список целевых запусков при ACCON (сохраняется на диск):
  - Настраиваемый порядок запуска приложений/активностей.
  - Пауза после каждого запуска.
  - Необязательная явная activity (можно использовать activity по умолчанию у приложения).
  - Пропуск цели, если пакет уже запущен.
- В GUI есть:
  - Старт/стоп сервиса.
  - Показ статуса сервиса (`running`/`stopped`) и времени последнего полученного ACCON/ACCOFF.
  - Изменение action для командного broadcast.
  - Переключатель автозапуска при boot.
  - Настройка задержки ACCON PLAY в миллисекундах (500-10000 мс).
  - Настройка списка запусков при ACCON.
- Планирует watchdog worker (проверка каждые 15 минут), который перезапускает сервис, если его убили.
- Пишет ACC-логи в публичную папку Documents (`Documents/FYTService/FYTCustomService-acc.log`).

## Контракт Intent
Action по умолчанию:
- `dev.igor.fytcustomservice.ACTION_COMMAND`

Extras:
- `extra_command_code` (`Int`) — обязательный селектор команды.
- `extra_arg1` (`Int`) — дополнительный числовой параметр (опционально).

Пример ADB-команды:

```bash
adb shell am broadcast \
  -a dev.igor.fytcustomservice.ACTION_COMMAND \
  --ei extra_command_code 1 \
  --ei extra_arg1 123
```

## Подробности ACC-логики
Входящие broadcast-события от системы FYT:
- `com.fyt.boot.ACCON`
- `com.fyt.boot.ACCOFF`

Сценарий ACCOFF:
1. Чтение текущей активной media session.
2. Синхронное сохранение `packageName`.
3. Отправка media-кода `PAUSE`.
4. Сохранение времени последнего ACCOFF.
5. Запись диагностики ACCOFF в лог-файл.

Примечание по обработке play-state:
- Детекция play-state на ACCOFF больше намеренно не используется.
- Причина: на протестированной FYT-прошивке медиаплеер часто уже находится в paused/stopped до получения ACCOFF broadcast, поэтому сохранённый state оказывался ненадёжным.
- По декомпилированному коду подтверждён вызов ACCOFF broadcast (`C2444q0.m7483b(0)`), а в upstream-обработчике перехода ACC (`C2692d.m10441i`) перед этим выполняются mute/LCDC переходы.
- Прямого явного вызова media `PAUSE/STOP` именно в этой цепочке отправки ACCOFF broadcast не обнаружено; это вывод по проверенным участкам кода.
- Использованные ссылки на декомпилированные исходники:
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\m1\q0.java` (`b(int i2)`, строки ~447-454): отправка ACC power broadcast из слоя FYT-сервиса.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p043m1\C2444q0.java` (`m7483b(int i2)`, строки ~512-519): аналогичная отправка ACCON/ACCOFF в обфусцированном namespace.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p058s0\C2692d.java` (`m10441i(int i2)`, строки ~3258-3291): путь обновления ACC, вызовы mute/LCDC и затем `C2444q0.m7483b(i2)`.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p043m1\C2444q0.java` (`m7494m(int i2)`, строки ~586-617; `m7506y(int i2)`, строки ~993-1013): управление LCDC и mute AMP при ACC-переходах.

Что означает mute AMP logic:
- В FYT это muting на уровне аппаратного аудиовыхода, а не управление воспроизведением конкретного медиаприложения.
- В ACC-цепочке (`C2692d.m10441i`) вызывается `C2444q0.m7506y(...)`, который отправляет команды mute/unmute через JNI/native слой (`ToolsJni.cmd_6_mute_amp(...)` или `ControlNative.fytMuteAMP(...)` в отдельных вариантах).
- Практический эффект: глобальное приглушение/разглушение звукового тракта на уровне AMP/MCU.
- Это отдельно от media-session transport-команд (`PLAY/PAUSE/STOP`) и само по себе не доказывает переход состояния конкретного плеера.

Сценарий ACCON:
1. Загрузка сохранённого пакета из ACCOFF.
2. Определение пакета приложения, которое было на переднем плане.
3. Запуск сохранённого плеера.
4. Ожидание настроенной задержки (`ACC ON play delay`, по умолчанию `2000` мс).
5. Отправка media-кода `PLAY` в сохранённый плеер.
6. Последовательный запуск настроенных целей ACCON (с паузами).
7. Восстановление предыдущего foreground-приложения.
   - Если определить предыдущее foreground-приложение не удалось, выполняется fallback на HOME.
8. Очистка сохранённого пакета ACCOFF.
9. Сохранение времени последнего ACCON.
10. Запись диагностики ACCON в лог-файл.

## Список запусков ACCON
Настраивается через кнопку `ACC ON startup targets` в экране настроек приложения.

Для каждой цели сохраняется:
- Имя пакета
- Необязательное имя activity (пусто = activity по умолчанию у пакета)
- Пауза после запуска (мс)

Цели сохраняются в `SharedPreferences` (JSON) и автоматически загружаются при ACCON.

Поведение редактора:
- Один прокручиваемый список (без второго экрана управления).
- Одиночный выбор элемента.
- Действия: Add / Edit / Delete / Move up / Move down.
- Для Delete требуется подтверждение.
- Add/Edit сценарий:
  1. Выбор приложения.
  2. В одном комбинированном диалоге выбор activity (`Default launcher activity` предвыбрана) и задержки.

## Важные заметки по Android 13 / FYT
Обычное стороннее приложение **не может гарантировать абсолютную неубиваемость** на стандартном Android 13. В проекте используется максимально надёжный (без root) шаблон:
- Foreground-сервис с постоянным уведомлением.
- Поведение `START_STICKY`.
- Автозапуск через boot/package-replaced receiver.
- Headless launcher entry для FYT-триггера «autostart after sleep».
- Проверки и перезапуск через WorkManager watchdog.

Для максимальной надёжности на FYT ГУ настройте вручную:
- FYT «autostart after sleep» на запуск `FYT custom service` (headless starter).
- Для открытия UI используйте ярлык `FYT custom service settings`.
- Разрешите auto-start/background start в vendor-настройках FYT.
- Зафиксируйте приложение в recent apps, если launcher это поддерживает.
- Оставьте разрешение/видимость уведомлений включёнными.

Если нужна почти системная «daemon»-надёжность, обычно требуется одно из:
- Установка как system app в `/system/priv-app`.
- Интеграция на уровне vendor firmware.
- Root + watchdog-скрипт/сервис-менеджер.

## Требуемые разрешения и доступы
В `AndroidManifest` объявлены:
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.RECEIVE_BOOT_COMPLETED`
- `android.permission.WAKE_LOCK`
- `android.permission.PACKAGE_USAGE_STATS`

Также в системных настройках для полной ACC-логики нужны:
- Notification access для `FYT custom service` (для надёжного чтения активных media sessions).
- Usage access для `FYT custom service` (для определения/восстановления foreground-приложения).

Также может потребоваться на уровне прошивки устройства:
- Vendor-specific whitelist для автозапуска.
- Vendor-specific keep-alive whitelist / исключение в startup manager.

## Логирование ACC-событий
Расположение лог-файла:
- `Documents/FYTService/FYTCustomService-acc.log`

Формат времени в каждой строке:
- `yyyy-MM-dd HH:mm:ss.SSS`

Что пишется в лог:
- Получение ACCON/ACCOFF.
- Определение активного плеера на ACCOFF и отправка pause.
- Попытка/результат запуска сохранённого плеера на ACCON.
- Лог обнаруженного foreground-приложения перед запуском startup targets.
- Отправка PLAY в сохранённый плеер.
- Действия по каждой цели автозапуска: запущена или пропущена с причиной (например, `already_running`).
- Попытка/результат восстановления предыдущего foreground-приложения.

## Changelog
### 2026-04-01
- Обновлено поведение boot receiver:
  - `android.intent.action.BOOT_COMPLETED` теперь запускает `ACTION_ACC_ON` (тот же путь логики, что и ACCON).
  - `android.intent.action.LOCKED_BOOT_COMPLETED` и `android.intent.action.MY_PACKAGE_REPLACED` оставлены на обычном `ACTION_START`.

### 2026-03-29 (update 2)
- Логирование теперь пишет только в один требуемый путь:
  - `Documents/FYTService/FYTCustomService-acc.log`
- На главном экране добавлена отдельная строка с полным активным путём к логу:
  - `Log file: ...`
- Усилено определение foreground-приложения для восстановления:
  - увеличено окно просмотра usage events,
  - добавлена поддержка события `ACTIVITY_RESUMED`,
  - добавлен fallback на `UsageStats` `lastTimeUsed`, если нет свежих transition events.
- Захват `Last active app before targets` в ACCON теперь не исключает package этого приложения, что повышает надёжность ручных тестов из экрана настроек.
- Если в ACCON для восстановления не найдено предыдущее foreground-приложение, сервис теперь запускает HOME вместо пропуска восстановления.

### 2026-03-29
- Убрана обработка GLSX ACC-алиасов в receiver и manifest; обрабатываются только FYT actions:
  - `com.fyt.boot.ACCON`
  - `com.fyt.boot.ACCOFF`
- Убран runtime ACC receiver из foreground-сервиса, оставлен один путь приёма ACC.
- Убраны debounce-блокировки дубликатов и wake-lock-обёртка вокруг ACC-обработчиков.
- На главном экране маркеры плеера теперь без суффикса состояния (`(playing|paused|stopped|unknown)`), только package name.
- Добавлена диагностическая строка GUI: `Last active app before targets`.
- В ACCON добавлен лог обнаруженного foreground-приложения перед startup targets и восстановление по этому зафиксированному значению.

### 2026-03-28 (update 2)
- Улучшена обработка debounce ACC-событий: `ACCON` больше не блокируется ошибочно после недавнего `ACCOFF` (и наоборот).
- Обновлён сценарий ACCON: после запуска сохранённого плеера команда `PLAY` отправляется всегда (сохранённый play-state с ACCOFF больше не используется для решения).
- Усилено восстановление foreground после запуска startup targets:
  - используются более жёсткие флаги bring-to-front,
  - добавлены отложенные повторные попытки восстановления для обхода FYT race condition.
- В GUI изменён порядок строк timestamp в левой колонке:
  - строка 1: `Last ACC OFF`
  - строка 2: `Last ACC ON`

### 2026-03-28
- Кнопки действий в редакторе целей ACC ON переведены на реальные иконки AppCompat (без буквенных fallback), добавлены day/night ресурсы для видимости в светлой и тёмной теме.
- В GUI добавлена кнопка сброса ACC-состояния (`Reset ACC state`) в той же строке, что и тестовые ACC-кнопки.
- Добавлено немедленное обновление UI после тестовых ACC-действий, чтобы метки ACC/плеера обновлялись без переоткрытия экрана.
- Блок статуса переработан в формат 2 строки x 2 колонки:
  - слева: `Last ACC ON`, `Last ACC OFF`
  - справа: `Last saved player`, `Last started player`
- Маркеры плеера расширены отображением состояния для saved/started плеера:
  - `playing`, `paused`, `stopped`, `unknown`
- Обновлена ACC-обработка:
  - debounce-дедупликация теперь 30 секунд по типу события (ACCON только против ACCON, ACCOFF только против ACCOFF),
  - ACCON теперь всё равно выполняет сценарий startup targets, даже если нет сохранённого ACCOFF-снимка,
  - усилено восстановление foreground-приложения после выполнения startup targets.
- Переработан backend ACC-логирования:
  - один активный лог `Documents/FYTService/FYTCustomService-acc.log`,
  - ротация по размеру при 100 kB с архивом в файлы с timestamp.

### 2026-03-27
- Добавлен runtime ACC receiver внутри foreground-сервиса как дополнительный путь приёма FYT/GLSX ACC broadcast с логированием пересылки.
- На главный экран добавлены две кнопки эмуляции ACC-событий: `Emulate ACC ON` и `Emulate ACC OFF`.
- Усилена совместимость кнопок действий в редакторе целей ACC ON для тем/прошивок HU:
  - сохранены theme-aware векторные иконки,
  - добавлен совместимый `drawableLeft`,
  - добавлены fallback-подписи с символами.
- Размер иконки приложения в диалоге редактирования цели (activity + delay) увеличен до `64dp x 64dp`.

### 2026-03-26
- Кнопки в редакторе целей ACC ON переведены на собственные векторные пиктограммы с theme-aware tint (видимы в светлой и тёмной теме).
- В диалоге выбора приложения теперь отображаются иконки приложений рядом с именем и пакетом.
- Редактирование существующей цели ACC ON теперь меняет только activity и задержку без повторного выбора приложения.
- В диалоге activity/задержки теперь показываются иконка и имя выбранного приложения.
- На главном экране добавлены поля `Last saved player` и `Last started player` рядом с отметками времени ACC.

## Куда добавлять ваш код
Пользовательскую логику команд добавляйте в:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Метод:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helper-файлы:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`
