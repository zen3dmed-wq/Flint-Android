#pragma once
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>
#include <QVersionNumber>

namespace FlintUpdatePolicy {
inline const QString version = QStringLiteral("8.10.25");
inline constexpr qint64 versionCode = 2195;
inline constexpr qint64 maxBytes = 500LL * 1024 * 1024;
inline QVersionNumber parseVersion(const QString &value) {
    if (!QRegularExpression("^[0-9]{1,9}(\\.[0-9]{1,9}){1,3}$").match(value).hasMatch()) return {};
    return QVersionNumber::fromString(value).normalized();
}
inline bool newer(const QString &candidate, const QString &installed) {
    const auto a = parseVersion(candidate), b = parseVersion(installed);
    return !a.isNull() && !b.isNull() && QVersionNumber::compare(a, b) > 0;
}
inline bool fileUrl(const QUrl &url, const QUrl &base) {
    return url.isValid() && url.scheme() == "https" && url.userInfo().isEmpty() &&
        url.fragment().isEmpty() && url.host() == base.host() && url.port(443) == base.port(443) &&
        url.path().startsWith(base.path() + "/app/files/") && !url.path().contains("..") &&
        !url.path().contains('\\');
}
inline bool appleUrl(const QUrl &url) {
    return url.scheme() == "https" && url.userInfo().isEmpty() && url.port(443) == 443 &&
        (url.host() == "apps.apple.com" || url.host() == "testflight.apple.com");
}
inline QString validate(const QJsonObject &latest, const QUrl &base, bool apple = false) {
    if (parseVersion(latest.value("version").toString()).isNull()) return QStringLiteral("Сервер передал некорректную версию обновления.");
    if (apple) return appleUrl(QUrl(latest.value("url").toString())) ? QString() : QStringLiteral("Для iOS нужна ссылка на App Store или TestFlight.");
    if (!fileUrl(QUrl(latest.value("url").toString()), base)) return QStringLiteral("Ссылка обновления не принадлежит серверу Flint.");
    const double size = latest.value("size").toDouble();
    if (size <= 0 || size > maxBytes || size != qint64(size)) return QStringLiteral("Некорректный размер обновления.");
    if (!QRegularExpression("^[A-Fa-f0-9]{64}$").match(latest.value("sha256").toString()).hasMatch()) return QStringLiteral("В обновлении отсутствует контрольная сумма.");
    if (!latest.value("fileName").toString().endsWith(".apk", Qt::CaseInsensitive)) return QStringLiteral("Для Android требуется файл APK.");
    if (latest.value("versionCode").toDouble() <= versionCode) return QStringLiteral("Эта сборка не новее установленной версии Android.");
    return {};
}
}
