# Flint iOS 8.10.15

Обновление использует общий с Android 8.10.15 код: выбор AUTO из сохранённых профилей, отмена подключения, ограниченное ожидание и экран «Семья → Устройства». Список VPN-устройств и отзыв доступа только владельцем требуют расширения API и индивидуальных ключей из DEVICE_API.txt в пакете админки 1.2. На прежнем API отображаются группы сеансов входа по платформе. Группа может содержать разные физические устройства. Пользователь может раскрыть её и завершить выбранный старый вход после подтверждения; текущий вход защищён. Это не отзывает ранее выданный VPN-ключ.

Исходники iPhone/iPad используют общий интерфейс Flint, аккаунт и настраиваемый API, а для VPN — настоящую Apple Network Extension из движка Amnezia 5.0.3.0 (WireGuard/AmneziaWG, Xray, OpenVPN). Минимальная версия — iOS 16. Иконки собираются из предоставленной эмблемы, без смещения изображения. Покупки, подписки, рефералы и обращения используют тот же API, что Android и Windows; доступность зависит от подключённого сервера.

## Сборка без подписи

Нужен Mac с Xcode 26, Qt 6.10.3 для macOS и iOS, CMake, Python с Conan 2.28.0, Go. Workflow `Build Flint iOS unsigned` выполняет такую сборку на GitHub Actions. Архив UNSIGNED содержит приложение и встроенный packet tunnel; это **не устанавливаемый IPA**, не TestFlight и не подтверждение работы VPN на iPhone.

```bash
git clone --recursive --branch 5.0.3.0 https://github.com/amnezia-vpn/amnezia-client.git amnezia-client
python3 build_ios.py amnezia-client
python3 ios/test_recipe.py amnezia-client
export QT_ROOT_PATH="$HOME/Qt/6.10.3"
bash ios/build-unsigned.sh "$PWD/amnezia-client" "$PWD/ios-build"
python3 ios/verify_bundle.py ios-build
```

## Подпись после подключения Apple Developer

Для публикации VPN в App Store Apple требует аккаунт разработчика, оформленный как организация ([правило 5.4](https://developer.apple.com/app-store/review/guidelines/uk/#vpn-apps)). Регистрация учётной записи и публикация в эту подготовку не входят.

1. В своём Apple Developer создать App ID `app.flint.vpn` и `app.flint.vpn.network-extension`, включить Network Extensions (Packet Tunnel) и App Groups у обоих. Зарегистрировать группу `group.app.flint.vpn`. Если эти идентификаторы недоступны, передать свой `--bundle-id`; группа и идентификатор расширения будут изменены совместно.
2. На свежем checkout движка выполнить `python3 build_ios.py amnezia-client --team ВАШ_TEAM_ID`. Team ID не является паролем. Сертификаты и private keys хранить только в Keychain/секретах CI, не в исходниках.
3. Сконфигурировать Xcode-проект без флагов CODE_SIGNING_ALLOWED=NO, с Qt toolchain из команды unsigned-сборки. Открыть полученный .xcodeproj, выбрать собственную Team и Automatic Signing для Flint и networkextension. App Groups и entitlements обоих targets должны соответствовать зарегистрированным ID. Создать Archive для физического iPhone, затем Validate/Distribute в TestFlight.
4. На реальном iPhone проверить разрешение VPN, подключение с действующим профилем, возврат из фона, перезапуск, QR/буфер, Telegram-вход, завершение/восстановление сети. Не считать эти проверки выполненными по результату компиляции.

Сборка ничего не публикует в App Store и не использует чужие сертификаты. Требования Apple к распространению и оформлению сервиса необходимо проверить перед публикацией: [Network Extension](https://developer.apple.com/documentation/networkextension/packet-tunnel-provider), [Apple Developer Program](https://developer.apple.com/programs/).
