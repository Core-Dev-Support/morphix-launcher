# Morphix Launcher

Android-лаунчер с «жидким стеклом» (glassmorphism) в стиле Xiaomi HyperOS. Приложение
регистрируется системой как домашний экран (`CATEGORY_HOME`).

## Возможности

- Рабочий стол на `ViewPager2` с сеткой иконок, перетаскиванием между страницами
- Папки: создание, переименование, изменение размера (1×1 / 2×2 / XXL), растворение
- Режим редактирования: обои, виджеты, настройки, мультивыбор и перетаскивание группой
- Поддержка системных виджетов через `AppWidgetHost` + собственный виджет часов
- Стеклянные док-панели, всплывающие меню действий, шторка приложений
- Два рабочих пространства, второе защищено биометрией
- Скрытые приложения (Vault), блокировка экрана по тапу (device admin + accessibility)
- Бейджи непрочитанных уведомлений через `NotificationListenerService`
- Формы иконок: squircle, circle, rounded square, teardrop, original
- Форматирование иконок под текущую тему оформления (иконки Xiaomi/MIUI)

## Стек

| Слой | Технология |
|---|---|
| Язык | Kotlin 1.9.22 |
| Сборка | Gradle 8.2, AGP 8.2.2, Kotlin DSL |
| Android | minSdk 26, target/compileSdk 34 |
| UI | ViewBinding, Material 3, ConstraintLayout, RecyclerView, ViewPager2 |
| Эффекты | Свой `LiquidGlassView` (blur на GPU) |
| Хранение | `SharedPreferences` + JSON (`PreferencesManager`) |

## Структура

```
app/src/main/java/com/naua_morphix_launcher/app/
├── MainActivity.kt              # основная логика: сетка, drag&drop, папки, виджеты
├── MorphixApp.kt
├── model/                       # AppItem, FolderItem, WidgetItem, LauncherSettings, enum'ы
├── ui/
│   ├── DesktopPagerAdapter.kt   # страницы рабочего стола
│   ├── FolderPagerAdapter.kt    # страницы внутри папки
│   ├── AppsAdapter.kt           # ячейки иконок (drawing, тени, форма)
│   ├── WidgetsAdapter.kt        # список виджетов в пикере
│   ├── SettingsDialog.kt        # диалог настроек
│   ├── SettingsSubDialogs.kt    # вложенные диалоги настроек
│   └── MorphixAppWidgetHost*.kt # хость системных виджетов
├── util/
│   ├── PreferencesManager.kt    # настройки, порядок иконок, папки, виджеты
│   ├── AppLoader.kt             # загрузка установленных приложений
│   ├── IconMaskUtil.kt          # маски иконок под тему MIUI/HyperOS
│   └── ThemeUtils.kt            # применение темы к диалогам
├── views/
│   ├── LiquidGlassView.kt       # GPU-блюр и скруглённые углы
│   └── ViewOutlineProviderRounded.kt
├── service/
│   ├── MorphixAccessibilityService.kt
│   └── MorphixNotificationListenerService.kt
└── receiver/
    └── MorphixDeviceAdminReceiver.kt
```

## Сборка

Сборка выполняется в GitHub Actions. Workflow `.github/workflows/android.yml`:

1. `lintDebug` — статический анализ
2. `assembleDebug` — debug APK, артефакт `morphix-launcher-debug`
3. `assembleRelease` — подписанный release APK, артефакт `morphix-launcher-release`
   (собирается, только если заданы секреты подписи, иначе в лог падает warning)

Артефакты скачиваются в разделе Actions нужного запуска.

### Секреты подписи (для release APK)

В `Settings → Secrets and variables → Actions` репозитория нужны:

| Секрет | Значение |
|---|---|
| `KEYSTORE_BASE64` | base64 от бинарника `.jks` |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | алиас ключа |
| `KEY_PASSWORD` | пароль ключа |

Получить base64 из keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("my-release.jks"))
```

Создать новый keystore:

```bash
keytool -genkey -v -keystore my-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias morphix
```

Без секретов workflow соберёт только debug APK, ошибки не возникнет.

## Разрешения

| Разрешение | Зачем |
|---|---|
| `QUERY_ALL_PACKAGES` | список всех установленных приложений |
| `EXPAND_STATUS_BAR` | свайп вниз открывает шторку уведомлений |
| `USE_BIOMETRIC` | вход во второе пространство и Vault |
| `REQUEST_DELETE_PACKAGES` | удаление приложений из лаунчера |
| `BIND_APPWIDGET` | привязка системных виджетов |
| `SET_WALLPAPER` | размытие обоев под стеклом |

Дополнительно: AccessibilityService (блокировка по тапу), DeviceAdminReceiver (мгновенная
блокировка), NotificationListenerService (бейджи).

## Установка

```bash
adb install -r app-debug.apk
```

После установки выбрать Morphix Launcher как домашний экран в системных настройках.