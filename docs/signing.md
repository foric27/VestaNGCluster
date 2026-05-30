# Signing Guide

## Локальная подпись

### 1. Создайте keystore

```bash
keytool -genkey -v \
  -keystore release-keystore.jks \
  -alias release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### 2. Создайте `keystore.properties`

```properties
storeFile=/path/to/release-keystore.jks
storePassword=your_keystore_password
keyAlias=release
keyPassword=your_key_password
```

### 3. Соберите signed APK

```bash
./gradlew assembleRelease
```

**Важно**: `keystore.properties` добавлен в `.gitignore` — не коммитьте его!

## CI/CD подпись (GitHub Actions)

### 1. Подготовьте keystore

```bash
# Закодируйте keystore в base64
base64 -w 0 release-keystore.jks
```

### 2. Добавьте Secrets в репозиторий

В Settings → Secrets and variables → Actions добавьте:

- `ANDROID_KEYSTORE_BASE64` — base64-кодированный keystore
- `ANDROID_KEYSTORE_PASSWORD` — пароль keystore
- `ANDROID_KEY_ALIAS` — alias ключа
- `ANDROID_KEY_PASSWORD` — пароль ключа

### 3. Проверьте workflow

Workflow `.github/workflows/android-release.yml` автоматически:
- Декодирует keystore
- Собирает signed APK
- Публикует в GitHub Releases

### 4. Проверьте результат

В логах workflow должно быть:
```
Signing configuration prepared successfully
```

## Безопасность

- **Никогда** не коммитьте keystore или пароли
- **Никогда** не публикуйте keystore в открытом доступе
- Используйте разные keystores для debug и release
- При компрометации ключа сразу сгенерируйте новый

## Устранение неполадок

### "Signing secrets are not configured"

Secrets не настроены. APK будет собран, но **не подписан**.

### "Failed to decode keystore file"

Проверьте, что `ANDROID_KEYSTORE_BASE64` содержит корректный base64.

### "Keystore file does not exist"

Проверьте путь в `keystore.properties` или убедитесь, что secret `ANDROID_KEYSTORE_BASE64` корректен.
