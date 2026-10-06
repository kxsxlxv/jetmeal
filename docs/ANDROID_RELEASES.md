# Автоматические APK-релизы JetMeal

Каждый `push` в `master` запускает `.github/workflows/android-release.yml`.
Также можно открыть **Actions → Android release → Run workflow → master**.
Workflow проверяет debug-сборку, все JVM unit-тесты и lint, собирает release APK,
проверяет подпись постоянного ключа и публикует обычный GitHub Release с APK.
PR в `master` запускает `android-ci.yml`: компиляция приложения/Android-тестов,
unit-тесты и lint, без signing Secrets и публикации.

Используются существующие зависимости проекта: JDK 25, Gradle 9.8.0,
AGP 9.4.1, Kotlin 2.4.20, SDK 37.1 / build-tools 36.0.0.
`applicationId` остаётся **com.kxsxlxv.jetmeal**.

## Версии и публикация

Локальные версии остаются `1.1.0` / `versionCode=3`. CI передаёт отдельные версии
через `JETMEAL_VERSION_CODE` и `JETMEAL_VERSION_NAME` (также поддерживаются Gradle
properties `ciVersionCode`/`ciVersionName`).

CI-код равен максимуму из `1000000 + run_number × 100 + run_attempt` и предыдущего
кода в тегах `v1.1.<код>` плюс один. Поэтому повторный запуск, восстановление
workflow-счётчика и иной порядок запуска не повторяют опубликованный код.
Предел Android — 2 100 000 000; превышение завершает сборку ошибкой.
Аллокатор работает под общей блокировкой release workflow, читая актуальные
удалённые теги. **Не удаляйте release-теги:** они сохраняют историю версий.

Пример первой сборки: версия `1.1.1000101`, тег `v1.1.1000101`, файл
**JetMeal-v1.1.1000101.apk**. Формат `0.0.N` не используется, чтобы не понижать
смысловую версию относительно установленной `1.1.0`. Obtainium получает один
универсальный APK; ABI-фильтры не нужны.

Release сначала создаётся закрытым draft для загрузки/проверки размера и digest
APK, затем публикуется без prerelease и отмечается Latest. Ошибка загрузки не
публикует неполный Release. Оставшийся draft/тег можно проверить в Releases;
новый запуск выделит следующий код. Не заменяйте уже опубликованные APK.

## Первоначальная настройка ключа — один раз

Постоянный приватный ключ нельзя менять: Android разрешает обновление приложения
только совместимой подписью. Потеря ключа лишит пользователей обычных обновлений.
GitHub Secrets не заменяют резервную копию — прочитать сохранённый ключ обратно
из Secrets невозможно.

Для создания и резервного копирования ключа используйте JDK `keytool` и Python 3:

```bash
python3 scripts/initialize_android_signing.py \
  --directory "$HOME/.local/share/jetmeal-signing" \
  --backup-directory "$HOME/.local/share/jetmeal-signing-backup" \
  --github-repo kxsxlxv/jetmeal \
  --client-config jetmeal.local.properties
```

Скрипт генерирует RSA-3072 JKS и случайные пароли, сохраняет их **вне репозитория**,
создаёт отдельную копию и устанавливает Secrets через авторизованный `gh`.
Повторный запуск использует уже существующий ключ; другой ключ не перезаписывается.
На Unix каталоги доступны только владельцу (0700), файлы — 0600. На Windows
дополнительно ограничьте NTFS ACL каталога своим пользователем.
Без `--github-repo` скрипт только создаёт/копирует ключ для ручной настройки.

**Сохраните весь каталог** (JKS, `signing-credentials.json`, публичный fingerprint)
в зашифрованном архиве на отдельном устройстве либо в защищённом хранилище.
Файл credentials содержит пароли: храните его как приватный ключ. Две папки на
одном компьютере защищают от случайного удаления, но не от потери компьютера.
Не создавайте новый ключ при смене компьютера: восстановите существующий.

## GitHub Secrets и Variables

**Settings → Secrets and variables → Actions → Secrets**:

| Secret | Содержимое |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 постоянного `jetmeal-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль хранилища |
| `ANDROID_KEY_ALIAS` | `jetmeal`, если ключ создан скриптом |
| `ANDROID_KEY_PASSWORD` | Пароль приватного ключа |

В **Variables**:

| Variable | Содержимое |
| --- | --- |
| `ANDROID_SIGNING_CERT_SHA256` | SHA-256 сертификата, 64 hex-символа без двоеточий |
| `SUPABASE_URL` | HTTPS URL текущего проекта |
| `SUPABASE_PUBLISHABLE_KEY` | Клиентский publishable key или legacy anon key |

Две Supabase-переменные нужны, поскольку локальный developer-файл исключён из Git.
Это клиентская конфигурация, которая в любом случае входит в APK; не используйте
`service_role`, secret key, пароль БД или пользовательские токены.
Скрипт настройки читает существующий ignored developer-файл без вывода значений.
Дополнительный PAT не нужен: публикация использует стандартный `GITHUB_TOKEN`.

В job JKS существует только в `$RUNNER_TEMP`, удаляется шагом `always()` и не входит
в artifacts. Configuration cache отключён; signing-job не выгружает Gradle cache.
Ни пароли, ни base64 не передаются параметрами команд или в логи.
Release-сборка с `-PrequireReleaseSigning=true` завершится ошибкой без signing
или реальной HTTPS клиентской конфигурации. Обычный локальный debug Secrets
не требует. Локальный release без signing остаётся unsigned для проверки R8;
такой APK нельзя публиковать для Obtainium.

## Obtainium и первое обновление

1. Установите Obtainium и разрешите ему установку приложений из этого источника.
2. Добавьте приложение по URL **https://github.com/kxsxlxv/jetmeal**.
3. Оставьте обычные Releases; draft/prerelease не нужны. При необходимости
   фильтруйте assets регулярным выражением `^JetMeal-v.*\.apk$`.
4. Установите APK из последнего Release. Следующие версии ставятся поверх него
   с сохранением Android-данных приложения, без Android Studio и USB.

**Переход с прежнего debug APK:** его подпись отличается от нового постоянного
release-ключа. Первый release нельзя установить поверх debug: потребуется один
раз удалить debug-приложение и установить release через Obtainium. Локальная
сессия будет удалена; потребуется снова войти. Данные питания в Supabase остаются.
Последующие release-обновления удаления не требуют. Скрипт не удаляет приложение
и не выполняет этот переход автоматически.

## Диагностика

- **Actions → Android release → неудачный run → красный step** показывает точную
  причину; unit/lint отчёты также создаются Gradle в `app/build/reports/`.
- `Missing Actions secret/variable` — проверьте имя, scope и значение в Actions.
- `Signing certificate changed` — восстановите прежний JKS/alias и fingerprint;
  не обходите проверку сменой fingerprint ради другого ключа.
- `Resource not accessible by integration` / 403 на Release — проверьте, что
  workflow содержит `permissions: contents: write`, Actions включены, и policy
  организации не запрещает write-токен. PAT обычно не нужен.
- Workflow отсутствует или ручной запуск недоступен — workflow должен быть в
  default branch `master`. Push в другую ветку release не запускает.
- SDK/JDK/Gradle ошибка — проверяйте шаг установки SDK и исходные pinned версии;
  не меняйте зависимости приложения ради обхода ошибки signing.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — разные подписи (обычно переход с debug).
  `VERSION_DOWNGRADE` — APK ниже установленного кода; нужен более новый Release.

Официальные источники: [Android signing](https://developer.android.com/studio/publish/app-signing),
[GitHub run variables](https://docs.github.com/en/actions/reference/workflows-and-actions/variables),
[Obtainium](https://github.com/ImranR98/Obtainium).
