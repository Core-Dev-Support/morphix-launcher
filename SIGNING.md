# Ключ подписи release-сборок Morphix Launcher

Файл `morphix-release.jks` — самоподписанный корневой сертификат для подписи APK.
Он лежит **вне git** (исключён в `.gitignore`) и хранится только в секретах
репозитория, поэтому в CI передаётся через `KEYSTORE_BASE64`.

> Ключ нельзя потерять или заменить: без него Android откажется обновлять уже
> установленное приложение — придётся удалять старую версию перед установкой новой.

## Параметры

| Параметр | Значение |
|---|---|
| Тип хранилища | PKCS12 (расширение `.jks` оставлено для совместимости) |
| Alias | `morphix-launcher` |
| Пароль хранилища и ключа | `CDS2026@&$` |
| Алгоритм | RSA 2048, подпись SHA256withRSA |
| Срок действия | 10000 дней (~27 лет) |
| Subject / Issuer | `CN=Core Dev Support, OU=Mobile Apps, O=Core Dev Support, L=Washington, ST=Washington, C=US` |
| SHA-256 отпечатка | `13:F9:50:A9:B7:70:19:AE:D7:E8:40:50:67:73:56:41:4A:8E:71:EB:63:80:4B:3C:29:53:4F:BB:96:CB:7C:86` |

## Как собрать release локально

Нужен `keystore.properties` в корне проекта (файл в git не попадает):

```properties
storeFile=morphix-release.jks
storePassword=CDS2026@&
keyAlias=morphix-launcher
keyPassword=CDS2026@&
```

Затем:

```bash
./gradlew assembleRelease
```

Без этого файла `assembleRelease` соберётся, но APK останется неподписанным —
`build.gradle.kts` проверяет существование хранилища и пропускает `signingConfig`.

## Как восстановить доступ к CI

Секреты в репозитории (Settings → Secrets and variables → Actions):

| Имя | Значение |
|---|---|
| `KEYSTORE_BASE64` | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("morphix-release.jks"))` |
| `KEYSTORE_PASSWORD` | `CDS2026@&$` |
| `KEY_ALIAS` | `morphix-launcher` |
| `KEY_PASSWORD` | `CDS2026@&$` |

Если файл утерян, но старые APK уже выпущены, генерировать новый ключ нельзя:
подпись должна совпадать. Единственный выход — удалить установленное приложение
и распространять новую подпись как самостоятельную.

## Проверка подписи собранного APK

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

В выводе должен быть `Signer #1 certificate DN: CN=Core Dev Support, OU=Mobile Apps,
O=Core Dev Support, L=Washington, ST=Washington, C=US`.