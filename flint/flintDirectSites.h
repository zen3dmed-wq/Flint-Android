#pragma once
#include <QString>
#include <QUrl>
#include <QRegularExpression>

namespace FlintDirectSites {
inline QString normalize(const QString &input)
{
    QString value = input.trimmed();
    if (value.isEmpty() || value.size() > 2048 || value.contains(QRegularExpression("[\\s\\\\]"))) return {};
    if (!value.contains("://")) value.prepend("https://");
    const QUrl url(value, QUrl::StrictMode);
    if (!url.isValid() || (url.scheme() != "https" && url.scheme() != "http") || !url.userInfo().isEmpty()) return {};
    QString host = QString::fromLatin1(QUrl::toAce(url.host().toLower()));
    if (host.endsWith('.')) host.chop(1);
    if (host.size() > 253 || !host.contains('.')) return {};
    static const QRegularExpression label("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    for (const auto &part : host.split('.')) if (!label.match(part).hasMatch()) return {};
    return host;
}
}
