#pragma once
#include "flintTelemetry.h"
#include <QStringList>
#include <cmath>

namespace FlintBalance {
inline bool loadFresh(const QVariantMap &h, qint64 now) {
    bool ok=false; const auto value=h.value("loadPercent").toDouble(&ok);
    return ok && std::isfinite(value) && value>=0 && value<=100
        && FlintTelemetry::fresh(h.value("loadAt").toLongLong(),now);
}
inline double score(const QVariantMap &h, qint64 now) {
    if (!h.value("available").isValid() || !FlintTelemetry::fresh(h.value("checkedAt").toLongLong(),now)) return 10000;
    if (!h.value("available").toBool()) return 100000;
    return qMax(1.0,h.value("latencyMs").toDouble()) + (loadFresh(h,now)?12*h.value("loadPercent").toDouble():0);
}
struct State {
    int badSamples=0; qint64 lastSample=0; QString reason;
    void reset() { badSamples=0; lastSample=0; reason.clear(); }
    QString choose(const QStringList &keys, const QVariantMap &health, const QString &active,
                   bool enabled, qint64 connectedAt, qint64 now) {
        if (!enabled || active.isEmpty() || connectedAt<=0) {reset();return {};}
        const auto current=health.value(active).toMap();
        const bool down=current.value("available").isValid()&&!current.value("available").toBool()
            && FlintTelemetry::fresh(current.value("checkedAt").toLongLong(),now);
        const bool loaded=loadFresh(current,now)&&current.value("loadPercent").toDouble()>=85;
        if (!down && now-connectedAt<180000) {reset();return {};}
        if (!down&&!loaded) {reset();return {};}
        const QString kind=down?"down":"load";
        const qint64 sample=current.value(down?"checkedAt":"loadAt").toLongLong();
        if (reason!=kind) {reset();reason=kind;}
        if(sample<=lastSample)return {}; // Cached API replies are not independent measurements.
        lastSample=sample; ++badSamples;
        if(badSamples<(down?2:3))return {};
        QString best;double bestScore=10000;
        for(const auto &key:keys) {
            const auto h=health.value(key).toMap();
            if(key==active || (!current.value("node").toString().isEmpty() && h.value("node")==current.value("node")))continue;
            const bool knownLoad=loadFresh(h,now);
            if((!down&&!knownLoad)||(!down&&knownLoad&&h.value("loadPercent").toDouble()>=70))continue;
            const double candidate=score(h,now);
            if(candidate<bestScore && candidate+200<score(current,now)) {best=key;bestScore=candidate;}
        }
        if(!best.isEmpty())reset();
        return best;
    }
};
}
