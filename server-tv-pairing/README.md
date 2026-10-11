# Flint — QR подключения для всех клиентов, API 1.1.0

Пакет для разработчика. На flintmain.ru ничего не установлено и не изменено.
Клиенты: Android/Android TV 8.11.9, Windows 8.10.34, iOS 8.10.29 (без подписи).

## Сценарий владельца: существующая кнопка в «Устройствах»

1. Владелец входит в Flint и выбирает «Добавить устройство по QR» в «Устройствах».
2. Выбирает активную подписку. Приложение загружает её свежие конфигурации и
   показывает HTTPS-ссылку `https://flintmain.ru/connect/{id}#key={key}` в QR.
3. Другой пользователь сканирует QR обычной камерой телефона или во Flint.
   Если приложение установлено и App Links настроены, оно открывается.
   Иначе открывается встроенная в этот модуль страница установки.
4. На странице есть «Скачать Flint» и «Открыть Flint и подключиться».
   Установку APK пользователь подтверждает в Android. После установки надо
   вернуться на ту же страницу и нажать «Открыть Flint» либо снова сканировать QR.
   Автоматическую передачу браузерной ссылки в только что установленный APK
   Android не гарантирует; об этом прямо сказано на странице.
5. Получатель подтверждает импорт. Все конфигурации, ссылка обновления подписки,
   сайты и автоматические правила РФ сохраняются; запускается VPN.
   Первый системный запрос на создание VPN пользователь подтверждает сам.

Ссылка работает 15 минут, принимается одним устройством. Повторное получение
тем же получателем с тем же proof допускается до ACK, для сетевых повторов.
Открытие страницы и скачивание APK ссылку не погашают. Владелец может отменить
её кнопкой «Отменить передачу». Закрытие окна владельца оставляет QR действующим.

## Сценарий получателя: телевизор, компьютер или телефон показывает QR

На устройстве нажать «Получить настройки по QR» (на TV — «Добавить с помощью QR»).
Владелец сканирует экран во Flint и выбирает подписку. Получатель ждёт настройки
через API, сохраняет их и подключается. Такой QR действует 5 минут; при закрытии
окна получение отменяется. Telegram, Bluetooth и общий роутер не нужны.
Оба устройства должны иметь обычный HTTPS-доступ к flintmain.ru.

## Установка разработчиком

Бинарники Linux amd64/arm64 и Windows amd64 — в `bin/`. Страница уже встроена
в бинарник. Исходники Go без внешних зависимостей — рядом. Запуск от отдельного
непривилегированного пользователя; HTTP-порт только на loopback:

```sh
go test -race ./...
CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o flint-pairing ./cmd/flint-pairing
./flint-pairing -listen 127.0.0.1:8099 -upstream https://flintmain.ru/api/v1 \
  -trust-loopback-proxy -android-apk /opt/flint/releases/Flint-v8.11.9-Android.apk \
  -windows-setup /opt/flint/releases/Flint-Setup-8.10.34.exe \
  -android-certificate-sha256 CERTIFICATE_SHA256
```

Вместо CERTIFICATE_SHA256 для приложенных APK указать:
`E5:19:05:C1:24:EB:6F:50:81:AF:D9:7E:D5:24:D0:49:97:0B:9A:BF:68:F1:84:5A:79:A3:D9:BB:B2:2B:65:F2`
Системный пользователь сервиса должен иметь права чтения APK.

В существующий HTTPS reverse proxy добавить только эти маршруты:

```nginx
location ^~ /api/v1/devices/pairing/ {
    proxy_pass http://127.0.0.1:8099;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header Host $host;
    proxy_read_timeout 180s;
    client_max_body_size 750k;
}
location ^~ /connect/ {
    proxy_pass http://127.0.0.1:8099;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Host $host;
}
location = /.well-known/assetlinks.json {
    proxy_pass http://127.0.0.1:8099;
}
location = /.well-known/apple-app-site-association {
    proxy_pass http://127.0.0.1:8099;
}
```

Не заменять существующие страницы/методы API. Исключить эти пути из редиректа
на вход в админку. X-Real-IP обязательно перезаписывать; доверие принимается
только от loopback. Не включать логирование тел запросов/ответов и URL-фрагментов.
Если /.well-known уже обслуживает другие приложения, объединить JSON-ассоциации,
а не заменять их. `assetlinks.json` не должен перенаправлять и требует HTTPS.

Для Android опубликованный пакет — `app.flint.vpn`, подпись сохранена.
Проверить App Links на устройстве; при старой сохранённой настройке браузера
пользователю может потребоваться разрешить открытие ссылок Flint в Android.

Для iOS после получения Apple Developer и подписи добавить
`-ios-url https://apps.apple.com/...` (либо опубликованный TestFlight URL)
и `-apple-application-id TEAMID.app.flint.vpn`. Настроить associated domains.
До этого страница честно сообщает, что установка iOS пока не опубликована.
В исходниках iOS зарегистрирован `flint://`; это запасная кнопка открытия страницы.
Windows установщик регистрирует `flint://` у текущего пользователя. Windows
также получает QR из изображения/буфера или показывает свой QR для подтверждения.

## API и шифрование

Полный контракт: `openapi.json`. Для owner QR:
`POST share/start` (Bearer владельца), `POST approve` (Bearer), затем
`POST share/claim` (получатель без Bearer), `POST ack` (без Bearer).
Пути относительны `/api/v1/devices/pairing/`. Отмена владельцем — `share/cancel`.
Для receiver QR: start → inspect → approve → complete → ack; cancel при закрытии.

Ключ AES-256-GCM (32 байта) находится только в URL fragment #key=.
Он не отправляется серверу. Nonce 12 байт, tag 16 байт в конце ciphertext,
Base64URL без padding. AAD: `Flint-TV-pairing-v1:{pairingId}` — историческое
имя сохранено ради совместимости. Максимальный plaintext 512 KiB.
Portable payload: version=1, subscriptionContent (строки URI), subscriptionUrl
(HTTPS), title, directSites, automaticRouting, russianPolicy. Нельзя добавлять
account/password/accessToken/refreshToken. Android дополнительно передаёт
collection, но все клиенты читают переносимый subscriptionContent.

claimSecret = Base64URL(SHA256(UTF8("Flint-share-claim-v1:" + key)))
claimChallenge = Base64URL(SHA256(UTF8(claimSecret)))
Получатель генерирует случайный codeVerifier (32 байта, Base64URL), отправляет
codeChallenge = Base64URL(SHA256(UTF8(codeVerifier))). Сервер хранит proof,
зашифрованные данные, ID владельца/подписки и срок; не хранит ключ расшифрования.
Сессии ограничены и хранятся в памяти, перезапуск сервиса погасит незавершённые QR.

Модуль проверяет владельца и активную подписку только read-only запросами
GET /me и GET /subscriptions к фиксированному upstream; оплаты/чат не меняет.
Передаётся VPN-профиль общей подписки, без входа в аккаунт владельца. Этот модуль
сам не выдаёт индивидуальные VPN-ключи и не добавляет login session в /me/sessions.
Для отдельного отзыва устройства/серверных лимитов разработчик должен подключить
выдачу и отзыв ключей VPN-панели. Одноразовый QR не делает скопированный профиль
одноразовым. Windows поддерживает существующий в нём набор VLESS/REALITY,
Android — Xray-профили; неизвестные форматы не считаются рабочими автоматически.

## Проверка после публикации

1. GET /api/v1/devices/pairing/capabilities: enabled=true, ownerSharing=true.
2. Проверить distribution, скачивание APK с Range, assetlinks.json без входа.
3. Владелец создаёт QR в «Устройствах», другой телефон сканирует без Flint,
   устанавливает, возвращается на страницу, открывает и подтверждает импорт.
4. Проверить установленный Flint, повторное использование другим устройством,
   просроченный/отменённый QR, отказ системного разрешения VPN.
5. TV на домашнем интернете + телефон на мобильной сети; Windows/iOS отдельно.
6. Проверить реальный трафик VPN; физические устройства в этой сборке не тестировались.
