# Contributing to Vesta NG Cluster

Благодарим за интерес к проекту! Этот документ описывает правила и рекомендации для вкладов.

## Как внести вклад

1. **Fork** репозитория
2. Создайте **feature branch** (`git checkout -b feature/amazing-feature`)
3. Внесите изменения
4. Убедитесь, что проект собирается:
   ```bash
   ./gradlew assembleRelease
   ./gradlew :app:testDebugUnitTest
   ./gradlew lintDebug
   ```
5. Сделайте **commit** с понятным сообщением
6. Отправьте **Pull Request**

## Стиль кода

- Язык: Kotlin (основной), XML (resources)
- Комментарии: русский язык
- Коммиты: semantic style (`fix:`, `feat:`, `refactor:`, `chore:`)
- Минимальные изменения — не переписывайте весь проект без необходимости
- Сохраняйте обратную совместимость

## Тестирование

- Unit tests: `app/src/test/java/ru/foric27/cluster/`
- Перед PR запустите тесты локально
- Убедитесь, что `lintDebug` проходит без ошибок

## Сообщения о проблемах

- Используйте GitHub Issues
- Опишите шаги воспроизведения
- Укажите версию Android и устройство
- Прикрепите логи при необходимости

## Безопасность

- **Никогда** не коммитьте secrets, keystore, пароли
- Сообщайте о security issues приватно (см. SECURITY.md)
