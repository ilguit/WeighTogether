# Подписанная сборка для RuStore

Публикация требует постоянного ключа владельца. Сохраните keystore и резервную
копию вне checkout; для обновлений используйте тот же ключ. Не подставляйте
временный или debug-ключ. Этот режим не создаёт ключей и не публикует приложение.

Перед финальным APK увеличьте `versionCode` и закоммитьте изменение по регламенту.
`versionName` меняется только по отдельному решению владельца о релизе.

Задайте в окружении `RUSTORE_KEYSTORE_FILE` (абсолютный путь вне checkout),
`RUSTORE_KEYSTORE_PASSWORD`, `RUSTORE_KEY_ALIAS`, `RUSTORE_KEY_PASSWORD`.
Передавайте секреты через локальный менеджер секретов или интерактивный ввод;
не записывайте их в Git, командную строку, логи или снимки экрана.

```bash
# JDK 17, Android SDK Platform 36; sdk.dir в untracked local.properties.
# Очистить только предыдущие APK целевого build type.
find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' -delete 2>/dev/null
./gradlew --no-configuration-cache -PrustoreSigning=true :app:assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

В примере используется установленный Android Build Tools 35.0.0; при другой
версии укажите её каталог. SDK Platform 36 и версия Build Tools — разные параметры.

Сверьте SHA-256 сертификата с сохранённым сертификатом владельца, package name
`com.palixander.weightogether` и увеличенный `versionCode` перед загрузкой.
Пароли и сертификаты здесь не приводятся. Не включайте Gradle debug logging или
build scans при работе с ключом. Configuration cache отключён в команде, чтобы
не сохранять signing-конфигурацию на диск.

С `-PrustoreSigning=true` неполное окружение, относительный путь, ключ внутри
checkout или недоступный файл останавливают конфигурацию. Ошибочные пароли,
формат keystore или alias отклоняет Android signing task. Перехода на debug
подпись нет. Без флага прежние debug workflows и unsigned release сохраняются;
unsigned APK для публикации непригоден.
