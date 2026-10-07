#pragma once
#include <QDateTime>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonObject>
#include <QUrl>
#include <QVariantMap>

namespace FlintTelemetry {
inline QString hostKey(QString host) {
    host = host.trimmed().toLower();
    if (host.startsWith('[') && host.endsWith(']')) host = host.mid(1, host.size()-2);
    QHostAddress ip;
    if (ip.setAddress(host)) return ip.toString().toLower();
    if (host.endsWith('.')) host.chop(1);
    return QString::fromLatin1(QUrl::toAce(host));
}
inline bool addressMatches(const QString &address, const QUrl &profile) {
    const auto raw = address.trimmed();
    if (raw.isEmpty() || profile.host().isEmpty()) return false;
    QHostAddress ip;
    if (ip.setAddress(raw)) return hostKey(raw) == hostKey(profile.host());
    // Bare hosts match every listener of the same node; an explicit port must match exactly.
    QUrl endpoint(raw.contains("://") ? raw : "tcp://"+raw);
    if (!endpoint.isValid() || endpoint.host().isEmpty() || !endpoint.userInfo().isEmpty()
        || endpoint.hasQuery() || endpoint.hasFragment()
        || (!endpoint.path().isEmpty() && endpoint.path() != "/")) return false;
    return hostKey(endpoint.host()) == hostKey(profile.host())
        && (endpoint.port(-1) < 0 || endpoint.port() == profile.port(443));
}
inline bool fresh(qint64 timestamp, qint64 now) {
    return timestamp > 0 && now-timestamp >= -30000 && now-timestamp < 120000;
}
inline QVariantMap location(const QJsonObject &document, const QString &profile, qint64 now) {
    const auto at = QDateTime::fromString(document.value("loadUpdatedAt").toString(), Qt::ISODateWithMs).toMSecsSinceEpoch();
    // updatedAt is response time, not measurement time. Never make stale samples fresh.
    if (!fresh(at, now)) return {};
    QJsonObject match;
    bool found = false;
    for (const auto &value : document.value("items").toArray()) {
        const auto item = value.toObject(); bool matches = false;
        for (const auto &address : item.value("addresses").toArray())
            if (address.isString() && addressMatches(address.toString(), QUrl(profile))) matches = true;
        if (!matches) continue;
        if (found) return {}; // Ambiguous node addresses must not attribute another node's load.
        match = item; found = true;
    }
    if (!found) return {};
    if (match.value("status").toString().compare("offline", Qt::CaseInsensitive) == 0)
        return {{"available", false}, {"checkedAt", at}, {"kind", "server"}};
    const auto load = match.value("load");
    if (!load.isDouble() || load.toDouble() < 0 || load.toDouble() > 100) return {};
    return {{"loadPercent", load.toDouble()}, {"loadAt", at}};
}
}
