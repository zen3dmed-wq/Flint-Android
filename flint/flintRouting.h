#pragma once
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QHostAddress>
#include <QRegularExpression>

namespace FlintRouting {
inline QJsonObject catalog() {
    static const QJsonObject data = [] {
        QFile f(":/ui/qml/Assets/flint-routing-catalog.json");
        if (!f.open(QIODevice::ReadOnly)) return QJsonObject{};
        return QJsonDocument::fromJson(f.readAll()).object();
    }();
    return data;
}
inline QJsonObject defaults() {
    return {{"version", 1}, {"geosite", QJsonArray{"category-ru", "tld-ru"}},
            {"geoip", QJsonArray{"ru", "private"}}, {"domains", QJsonArray{"domain:zakupki.gov.ru"}}, {"ips", QJsonArray{}}};
}
inline QJsonObject manual() {
    return {{"version", 1}, {"geosite", QJsonArray{}}, {"geoip", QJsonArray{}},
            {"domains", QJsonArray{}}, {"ips", QJsonArray{}}};
}
inline bool valid(const QJsonObject &policy) {
    if (policy.value("version").toInt() != 1) return false;
    const auto data = catalog();
    for (const auto &kind : {"geosite", "geoip"}) {
        if (!policy.value(kind).isArray() || policy.value(kind).toArray().size() > 32) return false;
        for (const auto &entry : policy.value(kind).toArray())
            if (!entry.isString() || !data.value(kind).toObject().contains(entry.toString())) return false;
    }
    for (const auto &kind : {"domains", "ips"}) {
        if (!policy.value(kind).isArray() || policy.value(kind).toArray().size() > 2048) return false;
        for (const auto &entry : policy.value(kind).toArray()) {
            const auto value = entry.toString();
            if (!entry.isString() || value.isEmpty() || value.size() > 253) return false;
            if (QString(kind) == "domains") {
                static const QRegularExpression domain("^(domain:|full:)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$");
                if (!domain.match(value).hasMatch() || value.contains("..")) return false;
            } else if (QHostAddress::parseSubnet(value).second < 0) return false;
        }
    }
    return true;
}
inline QJsonObject apply(QJsonObject config, const QJsonObject &policy, const QStringList &custom) {
    if (!valid(policy)) return config;
    const auto data = catalog();
    QStringList domains, ips;
    auto append = [](QStringList &out, const QJsonArray &entries) { for (const auto &x : entries) out.append(x.toString()); };
    append(domains, policy.value("domains").toArray()); append(ips, policy.value("ips").toArray());
    for (const auto &group : policy.value("geosite").toArray()) append(domains, data.value("geosite").toObject().value(group.toString()).toArray());
    for (const auto &group : policy.value("geoip").toArray()) append(ips, data.value("geoip").toObject().value(group.toString()).toArray());
    for (const auto &site : custom) {
        if (QHostAddress::parseSubnet(site).second >= 0) ips.append(site);
        else domains.append("domain:" + site);
    }
    domains.removeDuplicates(); ips.removeDuplicates();
    auto outbounds = config.value("outbounds").toArray();
    QString tag = "flint-direct";
    QStringList existing;
    for (const auto &out : outbounds) existing.append(out.toObject().value("tag").toString());
    while (existing.contains(tag)) tag += "-1";
    outbounds.append(QJsonObject{{"tag", tag}, {"protocol", "freedom"}});
    config["outbounds"] = outbounds;
    QJsonArray rules;
    if (!domains.isEmpty()) rules.append(QJsonObject{{"type", "field"}, {"domain", QJsonArray::fromStringList(domains)}, {"outboundTag", tag}});
    if (!ips.isEmpty()) rules.append(QJsonObject{{"type", "field"}, {"ip", QJsonArray::fromStringList(ips)}, {"outboundTag", tag}});
    auto routing = config.value("routing").toObject();
    for (const auto &rule : routing.value("rules").toArray()) rules.append(rule);
    routing["rules"] = rules;
    routing["domainStrategy"] = "IPIfNonMatch";
    config["routing"] = routing;
    auto inbounds = config.value("inbounds").toArray();
    for (int i=0; i<inbounds.size(); ++i) {
        auto inbound = inbounds[i].toObject();
        inbound["sniffing"] = QJsonObject{{"enabled", true}, {"destOverride", QJsonArray{"http", "tls", "quic"}}, {"routeOnly", true}};
        inbounds[i] = inbound;
    }
    config["inbounds"] = inbounds;
    return config;
}
}
