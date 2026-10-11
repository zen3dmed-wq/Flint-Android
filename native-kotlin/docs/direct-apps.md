# Приложения напрямую

При включённых «Сайты РФ» и автоматическом определении Flint исключает из
VPN установленные приложения из точного списка пакетов `DirectApps.catalog`.
Это правило Android для всего приложения: DNS, TCP, UDP, вход, карты и CDN.
Другие приложения продолжают пользоваться VPN и правилами сайтов.
Браузеры целиком из VPN автоматически не исключаются.

«Сайты РФ → Приложения напрямую» позволяет поменять выбор и добавить другое
установленное приложение. Выбор хранится только на телефоне, список приложений
на сервер не отправляется. Сохранение при активном VPN переподключает его.
При выключении «Сайты РФ» эти исключения не применяются. При выключении
автоматического выбора остаются только явные пользовательские исключения.

Список установленных пакетов заново проверяется перед подключением, в том
числе из виджета/шторки, после восстановления службы и смены ноды. Удалённые
приложения пропускаются. Сам Flint и пакеты с тем же UID не исключаются.
Если приложение было открыто во время смены маршрута, закройте и откройте его
заново, чтобы оно пересоздало соединения.

Пакеты сверены 08.10.2026 по карточкам разработчиков в магазинах:

- [Госуслуги](https://www.rustore.ru/catalog/app/ru.rostel)
- [АЗС Газпромнефть](https://www.rustore.ru/catalog/app/com.gpn.azs)
- [СберБанк](https://www.rustore.ru/catalog/app/ru.sberbankmobile)
- [Т-Банк](https://www.rustore.ru/catalog/app/com.idamob.tinkoff.android)
- [ВТБ](https://www.rustore.ru/catalog/app/ru.vtb24.mobilebanking.android)
- [Альфа-Банк](https://www.rustore.ru/catalog/app/ru.alfabank.mobile.android)
- [Ozon](https://www.rustore.ru/catalog/app/ru.ozon.app.android)
- [Wildberries](https://www.rustore.ru/catalog/app/com.wildberries.ru)
- [Яндекс Go](https://www.rustore.ru/catalog/app/ru.yandex.taxi)
- [2ГИС](https://play.google.com/store/apps/details?id=ru.dublgis.dgismobile)
- [Почта России](https://www.rustore.ru/catalog/app/com.octopod.russianpost.client.android)
- [Налоги ФЛ](https://www.rustore.ru/catalog/app/ru.fns.lkfl)

Яндекс Карты и Навигатор сохраняют ранее проверенные исключения Qt.
Тесты используют локальный сервер и отдельный UID тестового APK, а не
пользовательские аккаунты Госуслуг, банков и заправок. Полную работу этих
внешних приложений на всех прошивках эта проверка не подтверждает.
