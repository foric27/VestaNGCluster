package ru.foric27.cluster

import android.graphics.Rect

/**
 * Единая рабочая конфигурация проекта.
 *
 * Все базовые параметры собраны здесь, чтобы сервис, энкодер, сетевой слой
 * и логика запуска целевой activity кластера использовали один источник значений.
 */
object ProductConfig {

    object TargetApp {
        /** Системный `action` для явного запуска activity. */
        const val ACTION_MAIN: String = "android.intent.action.MAIN"

        /** Категория навигации автомобильного кластера для целевой activity. */
        const val CATEGORY_CLUSTER_NAVIGATION: String = "android.car.cluster.NAVIGATION"

        /** Имя пакета целевого приложения Яндекс Навигатор Auto. */
        const val PACKAGE_NAME: String = "ru.yandex.yandexnavi"

        /** Полное имя cluster-activity, которую нужно запускать на `VirtualDisplay`. */
        const val CLUSTER_COMPONENT: String =
            "ru.yandex.yandexnavi/com.yandex.navi.cluster.ClusterNavigationActivity"
    }

    object VisibleArea {
        /** Левая граница `unobscured / visibleArea` в пикселях. */
        const val LEFT: Int = 712

        /** Верхняя граница `unobscured / visibleArea` в пикселях. */
        const val TOP: Int = 12

        /** Правая граница `unobscured / visibleArea` в пикселях. */
        const val RIGHT: Int = 1208

        /** Нижняя граница `unobscured / visibleArea` в пикселях. */
        const val BOTTOM: Int = 524

        /** Короткая строка для логов и диагностики. */
        val SHORT: String = "Rect($LEFT, $TOP, $RIGHT, $BOTTOM)"

        /** Готовый `Rect` для передачи в extras целевой cluster-activity. */
        fun rect(): Rect = Rect(LEFT, TOP, RIGHT, BOTTOM)
    }

    object Video {
        /** Ширина выходного видеопотока. */
        const val WIDTH: Int = 1920

        /** Высота выходного видеопотока. */
        const val HEIGHT: Int = 640

        /** Короткая строка размера видеокадра для логов и диагностики. */
        val SIZE_SHORT: String = "${WIDTH}x${HEIGHT}"

        /** Верхняя граница FPS видеопотока. */
        const val FPS_LIMIT: Int = 8

        /** Использовать динамический FPS по обновлениям UI. */
        const val DYNAMIC_FPS: Boolean = false

        /** DPI виртуального дисплея по умолчанию. */
        const val DEFAULT_DPI: Int = 320

        /** Битрейт H.264-видеопотока в битах в секунду. */
        const val BITRATE: Int = 8_000_000

        /** Интервал между I-frame в секундах. */
        const val IFRAME_INTERVAL_SEC: Int = 1

        /** Высота чёрной маски снизу кадра в пикселях. */
        const val BLACK_BOTTOM_PX: Int = 106
    }

    object Network {
        /** IP-адрес принимающей стороны, куда отправляется видеопоток. */
        const val TARGET_IP: String = "192.168.40.2"

        /** UDP-порт видеопотока. */
        const val VIDEO_PORT: Int = 5004

        /** UDP-порт служебной синхронизации статуса. */
        const val STATUS_PORT: Int = 5001

        /** Короткая строка адреса видеопотока для логов и диагностики. */
        val VIDEO_ENDPOINT: String = "$TARGET_IP:$VIDEO_PORT"

        /** Короткая строка адреса синхронизации статуса для логов и диагностики. */
        val STATUS_ENDPOINT: String = "$TARGET_IP:$STATUS_PORT"

        /** Локальный адрес и маска интерфейса в формате CIDR. */
        const val LOCAL_CIDR: String = "192.168.40.1/24"

        /** Шлюз для целевого маршрута. */
        const val GATEWAY: String = "192.168.40.2"

        /** Локальный IP, к которому привязывается UDP-сокет. */
        const val BIND_IP: String = "192.168.40.1"

        /** Максимальный размер полезной нагрузки одного UDP-пакета. */
        const val UDP_MAX_PAYLOAD_BYTES: Int = 61_440

        /** Верхнее ограничение скорости отправки UDP в битах в секунду. */
        const val UDP_PACING_MAX_BPS: Int = 16_000_000
    }

    object Root {
        /** Имя интерфейса для root-сети. По умолчанию используется `eth0`. */
        const val IFACE: String = "eth0"

        /** Ключ диагностики для признака существования интерфейса. */
        val IFACE_EXISTS_LABEL: String = "${IFACE}_exists"

        /** Причина перезапуска, если интерфейс не найден при старте. */
        val MISSING_REASON: String = "${IFACE}_missing"

        /** Причина перезапуска, если интерфейс пропал во время работы. */
        val MISSING_RUNTIME_REASON: String = "${IFACE}_missing_runtime"

        /** Время жизни кеша проверки существования интерфейса. */
        const val IFACE_CACHE_TTL_MS: Long = 10_000L

        /** Время жизни кеша проверки маршрута через root-команды. */
        const val ROUTE_CACHE_TTL_MS: Long = 3_000L

        /** Таймаут выполнения одной `su`-команды по умолчанию. */
        const val SU_TIMEOUT_MS: Long = 10_000L
    }

    object UpdateFtp {
        /** Имя архива обновления, которое ожидает IVI-клиент. */
        const val UPDATE_ZIP_NAME: String = "ICUpdate.zip"

        /** Имя файла подписи, которое ожидает IVI-клиент. */
        const val UPDATE_SIG_NAME: String = "ICUpdate.zip.sig"

        /** Управляющий порт встроенного FTP-сервера обновления. */
        const val PORT: Int = 2121

        /** Начало диапазона пассивных FTP-портов. */
        const val PASSIVE_PORT_START: Int = 22100

        /** Конец диапазона пассивных FTP-портов. */
        const val PASSIVE_PORT_END: Int = 22109

        /** Имя FTP-пользователя для совместимости с IVI-клиентом. */
        const val USER: String = "anonymous"

        /** Пароль FTP-пользователя для совместимости с IVI-клиентом. */
        const val PASSWORD: String = "fetisov"

        /** Имя рабочей директории, публикуемой по FTP. */
        const val ROOT_DIR_NAME: String = "ftp_root"

        /** Период опроса обновления во внутренней памяти. */
        const val INTERNAL_POLL_PERIOD_MS: Long = 10_000L

        /** Задержка перед повторной попыткой поднять FTP после позднего старта сети. */
        const val RETRY_DELAY_MS: Long = 3_000L
    }

    object Security {
        /** Ожидаемый SHA-256 хеш подписи APK (нижний регистр, без двоеточий).
         *  Пустая строка = проверка отключена (debug-режим).
         *  Для release-сборки заполните актуальным хешем подписи. */
        const val EXPECTED_SIGNATURE_SHA256: String = "322fc02918d1cd7729dc6fac7c77b962f09a7afa41760264b44c7baac1e63227"
    }

    object Service {
        /** Идентификатор foreground-уведомления сервиса. */
        const val NOTIFICATION_ID: Int = 1201

        /** Идентификатор канала уведомлений foreground-сервиса. */
        const val NOTIFICATION_CHANNEL_ID: String = "cluster_stream"

        /** Отображаемое имя канала уведомлений. */
        const val NOTIFICATION_CHANNEL_NAME: String = "Трансляция кластера"

        /** Начальная задержка перед первой попыткой перезапуска. */
        const val RESTART_BACKOFF_START_MS: Long = 700L

        /** Максимальная задержка экспоненциального увеличения задержки при перезапусках. */
        const val RESTART_BACKOFF_MAX_MS: Long = 15_000L

        /** Задержка стандартного восстановления сервиса. */
        const val SERVICE_RECOVERY_DELAY_MS: Long = 700L

        /** Задержка восстановления после удаления задачи из списка недавних приложений. */
        const val TASK_REMOVED_RECOVERY_DELAY_MS: Long = 500L

        /** Код запроса для `PendingIntent` восстановления сервиса. */
        const val SERVICE_RECOVERY_REQUEST_CODE: Int = 1101

        /** Минимальный интервал между одинаковыми запросами на рестарт. */
        const val RESTART_REQUEST_DEBOUNCE_MS: Long = 250L

        /** Минимальный интервал между рестартами при кодек-ошибках (чтобы не уйти в бесконечный loop). */
        const val CODEC_ERROR_RESTART_DEBOUNCE_MS: Long = 3_000L

        /** Максимальное время ожидания появления корректного маршрута. */
        const val ROUTE_WAIT_TIMEOUT_MS: Long = 3_000L

        /** Шаг повторной проверки маршрута во время ожидания. */
        const val ROUTE_WAIT_STEP_MS: Long = 100L

        /** Минимальная задержка повторного запуска при отсутствии маршрута до целевого IP. */
        const val NO_ROUTE_RESTART_BACKOFF_MIN_MS: Long = 2_500L

        /** Минимальная задержка повторного запуска, если не найден сетевой интерфейс. */
        const val IFACE_MISSING_RESTART_BACKOFF_MIN_MS: Long = 4_000L

        /** Период работы watchdog проверки связности. */
        const val CONNECTIVITY_WATCHDOG_PERIOD_MS: Long = 1_500L

        /** Окно, в течение которого свежая отправка считается актуальной. */
        const val ROUTE_RECENT_SEND_GRACE_MS: Long = 5_000L

        /** Сколько подряд отказов маршрута допускается до принудительного рестарта. */
        const val ROUTE_FAILURES_BEFORE_RESTART: Int = 2

        /** Таймаут ожидания завершения служебных потоков. */
        const val THREAD_JOIN_TIMEOUT_MS: Long = 250L

        /** Период отправки синхронизации статуса в миллисекундах. */
        const val STATUS_PERIOD_MS: Int = 250

        /** Интервал полной синхронизации ключевых данных статуса. */
        const val STATUS_KEY_SYNC_INTERVAL: Int = 2400

        /** Как часто логировать повторяющиеся ошибки синхронизации статуса. */
        const val STATUS_ERROR_LOG_EVERY: Int = 10

        /** Задержка перед восстановлением после падения процесса. */
        const val PROCESS_CRASH_RECOVERY_DELAY_MS: Long = 1_500L

        /** Окно наблюдения crash loop для всего процесса. */
        const val PROCESS_CRASH_WINDOW_MS: Long = 60_000L

        /** Максимум падений в окне до временного подавления recovery. */
        const val PROCESS_CRASH_MAX_IN_WINDOW: Int = 3

        /** На сколько подавлять автоподъём после crash loop. */
        const val PROCESS_CRASH_SUPPRESS_MS: Long = 180_000L
    }
}
