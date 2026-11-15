# Muza

Muza — нативное Android-приложение для извлечения и загрузки аудиодорожек с YouTube в формате MP3 с выбором битрейта, поддержкой очереди, системного меню «Поделиться» (Share Target) и сохранением через Storage Access Framework.

Интерфейс спроектирован в строгом утилитарном минимализме с соблюдением стандартов Material Design 3 (монохромная палитра, адаптивная иконка, отсутствие лишних декоративных элементов).

## Внешний вид

<p align="center">
  <img src="screenshots/screenshot.jpg" width="320" alt="Интерфейс приложения Muza" />
</p>

## Архитектура и стек технологий

- **Платформа:** Нативный Android (Min SDK 26, Target SDK 35)
- **Язык программирования:** Kotlin 2.0.21
- **UI-фреймворк:** Jetpack Compose (Material 3 BOM 2024.11.00)
- **Движок извлечения медиа:** `io.github.junkfood02.youtubedl-android:library:0.17.4` (встроенный рантайм yt-dlp)
- **Конвертация аудио и тегирование:** `io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4` (FFmpeg)
- **Асинхронность и многопоточность:** Kotlin Coroutines, StateFlow, SupervisorJob
- **Файловая система и персистентность:**
  - Android Storage Access Framework (SAF) через `androidx.documentfile:documentfile`
  - Хранение настроек: `androidx.datastore:datastore-preferences`
- **Загрузка изображений:** Coil Compose (`io.coil-kt:coil-compose`)
- **Сетевой клиент:** OkHttp 4.12.0
- **Целевая архитектура ABI:** `arm64-v8a` (оптимизированная компоновка нативных библиотек `libpython` и `libffmpeg`, размер APK ~70 МБ)

## Ключевые функциональные особенности

1. **Изолированная система очереди (FIFO):**
   - Состояние очереди полностью отвязано от текстового поля ввода. Очистка поля поиска не затрагивает добавленные треки.
   - Мгновенная предзагрузка метаданных (название, обложка, длительность) с анимированным плейсхолдером (shimmer-скелетон).
   - Поддержка пакетного добавления ссылок и автоматическая нормализация любых форматов YouTube URL (включая Shorts, ссылки с метками времени и трекинговыми параметрами `si=`).

2. **Фоновый сервис загрузки (Foreground Service):**
   - Загрузка и конвертация выполняются в `DownloadService` с типом `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
   - Троттлинг обновлений уведомления (не чаще одного раза в 1.2 сек и при изменении прогресса >= 3%), что исключает мигание и перескакивание шторки уведомлений на кастомных прошивках (MIUI, HyperOS).
   - Корректный жизненный цикл уведомлений: единый идентификатор для прогресса и результата, гарантирующий отсутствие зависших плашек «Сохранение...» после завершения загрузки.

3. **Storage Access Framework (SAF):**
   - Прямой экспорт MP3 в выбранную пользователем директорию без запроса устаревших опасных разрешений на доступ ко всему хранилищу (`MANAGE_EXTERNAL_STORAGE`).
   - Сохранение постоянного URI-доступа через `takePersistableUriPermission`.

4. **Интеграция Share Target:**
   - Обработка интентов `ACTION_SEND` и `ACTION_VIEW`. Возможность отправлять ссылки напрямую из приложения YouTube или мобильного браузера без необходимости открывать Muza вручную.

5. **Авторизация YouTube:**
   - Встроенный изолированный компонент WebView для входа в Google/YouTube-аккаунт с автоматическим экспортом сессионных cookie в формат Netscape (`cookies.txt`) для загрузки приватных треков и обхода возрастных ограничений.

## Структура проекта

```
app/src/main/java/com/muza/app/
├── data/
│   ├── Config.kt                  # Константы, битрейты и параметры User-Agent
│   ├── PreferencesManager.kt      # Слой работы с Jetpack DataStore
│   └── UpdateChecker.kt           # Проверка обновлений движка yt-dlp и релизов
├── service/
│   ├── DownloadQueueManager.kt    # Реактивный менеджер очереди загрузок
│   ├── DownloadService.kt         # Foreground Service загрузки и конвертации
│   └── DownloadState.kt           # Модели состояний скачивания
├── ui/
│   ├── components/                # Компоненты Compose (карточки очереди, поле ввода, shimmer)
│   ├── screens/                   # Экраны (MainScreen, SettingsScreen, YouTubeAuthScreen)
│   └── theme/                     # Монохромная тема Material 3 (Theme, Color, Type)
└── util/
    ├── CookieNetscapeConverter.kt # Конвертер CookieManager в спецификацию Netscape
    ├── FileHelper.kt              # Операции экспорта SAF и очистки кэша
    └── UrlExtractor.kt            # Парсинг и валидация идентификаторов видео
```

## Сборка и установка

### Требования к окружению
- JDK 17 (OpenJDK 17 или Eclipse Temurin)
- Android SDK (API Level 35, Build Tools 35.0.0)
- Gradle 8.12 (поставляется через Gradle Wrapper)

### Команды компиляции

1. **Сборка Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`

2. **Сборка Release APK:**
   ```bash
   ./gradlew assembleRelease
   ```
   Готовый файл: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Установка на устройство через ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Лицензия

Проект распространяется под лицензией Apache License 2.0. Используемые библиотеки (`yt-dlp`, `FFmpeg`, `youtubedl-android`) регулируются собственными открытыми лицензиями.
