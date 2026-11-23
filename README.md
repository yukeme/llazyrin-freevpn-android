# 🛡️ llazyrinVPN — Официальный Android Клиент

<p align="center">
  <img src="res/drawable/ic_launcher_192.png" width="128" height="128" alt="llazyrinVPN Logo" style="border-radius: 24px; box-shadow: 0 10px 30px rgba(16, 185, 129, 0.4);" />
</p>

<p align="center">
  <b>Сверхскоростной, безопасный и отказоустойчивый VPN-клиент нового поколения для Android.</b><br>
  Нативный движок Xray-core, маскировка VLESS Reality (XTLS-Vision), резервный пул Anti-Jamming для сотовых сетей, умное раздельное туннелирование и Zero-Logs.
</p>

<p align="center">
  <a href="https://llazyrin.online"><img src="https://img.shields.io/badge/🌐_Официальный_сайт-llazyrin.online-10B981?style=for-the-badge" alt="Official Website"></a>
  <a href="https://t.me/llazyrin"><img src="https://img.shields.io/badge/📢_Telegram_канал-@llazyrin-0088cc?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Channel"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android Version" />
  <img src="https://img.shields.io/badge/Core-Xray--core%20v1.8%2B-00ADD8?style=flat-square&logo=go&logoColor=white" alt="Engine" />
  <img src="https://img.shields.io/badge/Protocol-VLESS%20Reality%20(XTLS--Vision)-8A2BE2?style=flat-square" alt="Protocol" />
  <img src="https://img.shields.io/badge/VirusTotal-0%2F72%20Clean-brightgreen?style=flat-square&logo=virustotal&logoColor=white" alt="VirusTotal" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="License" />
</p>

---

## 📥 Где скачать актуальную версию?

* 🌐 **Официальный сайт проекта:** [https://llazyrin.online](https://llazyrin.online) — всегда самая свежая и проверенная версия APK, статус доступности серверов и документация.
* 📢 **Официальный Telegram-канал:** [@llazyrin](https://t.me/llazyrin) — новости проекта, обновления, уведомления о блокировках и дропы промокодов.
* 📦 **GitHub Releases:** [Страница релизов](https://github.com/yukeme/llazyrin-freevpn-android/releases) — исходные APK с контрольными суммами SHA-256.

---

## ⚡ Ключевые преимущества и «фишки» llazyrinVPN

### 1. 🛡️ VLESS Reality (XTLS-Vision) — Невидимость для DPI и ТСПУ
* В отличие от устаревших протоколов (OpenVPN, WireGuard, обычный ShadowSocks), которые легко вычисляются и блокируются ТСПУ по сигнатурам рукопожатия, **VLESS Reality маскирует трафик под обычный защищенный TLS-трафик** крупнейших зарубежных порталов.
* Для систем глубокого анализа пакетов (DPI) ваше соединение неотличимо от посещения легитимного сайта с валидным сертификатом шифрования.

### 2. 📡 Резервный пул Anti-Jamming для сотовой связи
* Встроенный отказоустойчивый пул шлюзов, оптимизированных для работы в условиях жестких мобильных блокировок, белых списков операторов и подавления базовых станций.
* Автоматическая память стабильных мобильных узлов (`MobileProvenConfigStorage`): клиент запоминает, какие серверы успешно работали на вашей сотовой сети, и подключается к ним в первую очередь.

### 3. 🔀 Интеллектуальный Switch-Router
* Непрерывный асинхронный мониторинг задержки серверов (RTT/ping) в реальном времени.
* В случае деградации текущей ноды клиент выполняет бесшовное автоматическое переключение на самый быстрый доступный узел без потери сессии.

### 4. 🚀 Раздельное туннелирование (Split Tunneling)
* Вы сами выбираете, какие приложения направлять через VPN, а какие пускать напрямую.
* Российские сервисы (Сбер, Т-Банк, Госуслуги, Яндекс Карты) продолжают работать через прямое подключение на максимальной скорости вашего провайдера, а заблокированные ресурсы идут через безопасный туннель.

### 5. 🔒 Zero-Logs & Приватность
* Приложение не требует регистрации по паспорту или номеру телефона.
* Полное отсутствие сбора журналов посещенных сайтов, DNS-запросов и IP-адресов назначения.
* Исходный код открыт — вы можете лично убедиться в отсутствии трекеров и скрытого сбора данных.

### 6. 🔄 Безопасный OTA-клиент (RSA-2048)
* Встроенная система доставки обновлений проверяет цифровую подпись каждого нового APK перед установкой, защищая пользователей от подделок и MITM-атак.

---

## 🛡️ Безопасность и проверка на вирусы

Приложение регулярно проходит независимую проверку ведущими антивирусными лабораториями:

* **VirusTotal:** `0 детектов из 72 антивирусов` (Kaspersky, Dr.Web, ESET, Google Play Protect, Microsoft Defender подтверждают полную безопасность).
* **Контрольные суммы SHA-256** публикуются для каждого релиза, гарантируя соответствие бинарного файла исходному коду.

> **Примечание об установке сторонних APK:**  
> При установке напрямую из браузера Android может показать стандартное системное предупреждение о неизвестном источнике. Это стандартная политика Google для всех приложений вне Play Store.  
> Для продолжения нажмите **«Подробнее» (Details) → «Все равно установить» (Install anyway)**.

---

## 🏗️ Архитектура проекта

```text
llazyrin-freevpn-android/
├── src/ru/llazyrin/goblinvpn/
│   ├── MainActivity.java               # Главный экран управления подключением
│   ├── service/
│   │   ├── GoblinVpnService.java       # Системная интеграция android.net.VpnService
│   │   ├── VpnState.java               # Машина состояний туннеля (DISCONNECTED/CONNECTING/CONNECTED)
│   │   └── VpnTileService.java         # Быстрый переключатель в шторке Android (Quick Settings)
│   ├── net/
│   │   ├── AutoSwitchEngine.java       # Фоновый мониторинг пинга и автопереключение узлов
│   │   ├── WhitelistPoolManager.java   # Резервный пул Anti-Jamming для сотовых сетей
│   │   ├── XrayConfigGenerator.java    # Генерация нативных JSON-конфигураций Xray-core
│   │   └── AppUpdateManager.java       # Защищенный OTA-клиент обновлений
│   ├── security/
│   │   ├── GoblinSecurityVault.java    # Локальное хранилище резервных семян
│   │   └── MobileProvenConfigStorage.java # Кэш подтвержденных мобильных конфигураций
│   └── ui/                             # Киберпанк интерфейс, диалоги и Starfield анимация
├── res/                                # Ресурсы оформления, векторная графика, локализация
├── assets/                             # Базы маршрутизации geoip.dat, geosite.dat
├── libs/                               # Gomobile обвязка libv2ray.jar
├── jniLibs/                            # Скомпилированные бинарники Xray-core (arm64-v8a, armeabi-v7a)
└── AndroidManifest.xml                 # Системные манифесты и разрешения
```

---

## 💻 Сборка из исходного кода

### Требования:
* **JDK 17+** или **JDK 21**
* **Android SDK:** Platform 34 (Android 14) / Platform 35, Build-Tools 34.0.0+

### Инструкция по сборке:
1. Клонируйте репозиторий:
   ```bash
   git clone https://github.com/yukeme/llazyrin-freevpn-android.git
   cd llazyrin-freevpn-android
   ```
2. Сборка через Android Command-line Tools / Gradle:
   ```bash
   # Сборка Release APK
   ./gradlew assembleRelease
   ```
3. Скомпилированный APK появится в директории `build/outputs/apk/release/`.

---

## 🤝 Сообщество и контакты

* 🌐 **Сайт:** [llazyrin.online](https://llazyrin.online)
* 📢 **Telegram канал:** [@llazyrin](https://t.me/llazyrin)
* 🐛 **Сообщить об ошибке:** [GitHub Issues](https://github.com/yukeme/llazyrin-freevpn-android/issues)

---

## 📄 Лицензия

Исходный код распространяется под лицензией **GNU General Public License v3.0 (GPLv3)**. Подробности см. в файле [LICENSE](LICENSE).
