# FYT custom service

`FYT custom service` — это Android-приложение для головных устройств на базе FYT с Android 13 (класс UIS7870). Оно запускает foreground-сервис с sticky-поведением перезапуска и может выполнять пользовательские ветки логики по числовым параметрам, полученным через intents.

## Что делает приложение
- Запускает постоянный foreground-сервис (`START_STICKY` + постоянное уведомление).
- Использует headless launcher activity для FYT autostart-триггеров (экран настроек не показывается, если сервис уже запущен).
- Сохраняет ручной доступ к настройкам через отдельный ярлык: `FYT custom service settings`.
- Слушает пользовательские broadcast intents с числовыми параметрами.
- Выполняет логику по коду команды в `FytForegroundService.executeCommand(...)`.
- Обрабатывает ACC-события FYT:
  - `com.fyt.boot.ACCOFF`: сохраняет пакет текущего медиаплеера + состояние воспроизведения, затем отправляет `PAUSE`.
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
2. Синхронное сохранение `packageName` + `wasPlaying`.
3. Отправка media-кода `PAUSE`.
4. Защита от дублей ACCOFF (часто встречается на некоторых прошивках).
5. Сохранение времени последнего ACCOFF.
6. Запись диагностики ACCOFF в лог-файл.

Сценарий ACCON:
1. Загрузка сохранённого состояния из ACCOFF.
2. Определение пакета приложения, которое было на переднем плане.
3. Запуск сохранённого плеера.
4. Ожидание настроенной задержки (`ACC ON play delay`, по умолчанию `2000` мс).
5. Если до ACCOFF плеер играл — отправка media-кода `PLAY`.
6. Последовательный запуск настроенных целей ACCON (с паузами).
7. Восстановление предыдущего foreground-приложения.
8. Очистка сохранённого состояния ACCOFF.
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
- Получение ACCON/ACCOFF и игнор дубликатов.
- Определение активного плеера на ACCOFF и отправка pause.
- Попытка/результат запуска сохранённого плеера на ACCON.
- Отправка/пропуск PLAY с указанием причины.
- Действия по каждой цели автозапуска: запущена или пропущена с причиной (например, `already_running`).
- Попытка/результат восстановления предыдущего foreground-приложения.

## Куда добавлять ваш код
Пользовательскую логику команд добавляйте в:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Метод:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helper-файлы:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`
