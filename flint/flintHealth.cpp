#include "flintController.h"
#include "flintTelemetry.h"
#include "flintBalance.h"
#include <QCryptographicHash>
#include <QDateTime>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QJsonArray>
#include <QTcpSocket>
#include <QThread>
#include <QUrl>
#include <QUrlQuery>
#include <algorithm>
#ifdef Q_OS_ANDROID
#include <QCoreApplication>
#include <QJniObject>
#include <QJniEnvironment>
#endif

namespace {
QString serverKey(const QString &uri) {return "SERVER:"+QString::fromLatin1(QCryptographicHash::hash(uri.toUtf8(),QCryptographicHash::Sha256).toHex()).toUpper();}
QString endpointKey(const QUrl &url) {
    QString host=url.host().toLower();if(host.contains(':'))host='['+host+']';
    return QString::fromLatin1(QCryptographicHash::hash((host+':'+QString::number(url.port(443))).toUtf8(),QCryptographicHash::Sha256).toHex());
}
double score(const QVariantMap &h) { return FlintBalance::score(h,QDateTime::currentMSecsSinceEpoch()); }
qint64 probe(const QString &uri,const QString &fingerprint) {
#ifdef Q_OS_ANDROID
    auto context=QNativeInterface::QAndroidApplication::context();
    const auto u=QJniObject::fromString(uri),f=QJniObject::fromString(fingerprint);
    auto ms=QJniObject::callStaticMethod<jlong>("org/amnezia/vpn/FlintProbe","probe","(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)J",context.object(),u.object<jstring>(),f.object<jstring>());
    QJniEnvironment env;if(env->ExceptionCheck()){env->ExceptionClear();return -2;}return ms;
#else
    Q_UNUSED(uri);Q_UNUSED(fingerprint);return -2;
#endif
}
}
QString FlintController::healthKey(const QString &key) const {
    const auto manual=m_settings->value("Conf/flintManualImports").toMap();
    for(auto it=manual.begin();it!=manual.end();++it)if(it.value().toString()==key)return serverKey(it.key());
    return key;
}
QString FlintController::serverHealthText(const QString &key) const {
    auto h=m_health.value(healthKey(key)).toMap();
    QString text;
    if(!h.value("available").isValid() || QDateTime::currentMSecsSinceEpoch()-h.value("checkedAt").toLongLong()>120000)text=QStringLiteral("Не проверен");
    else if(!h.value("available").toBool())text=QStringLiteral("Недоступен");
    else text=QStringLiteral("%1 мс · %2").arg(h.value("latencyMs").toInt()).arg(h.value("kind")=="vpn"?QStringLiteral("VPN проверен"):QStringLiteral("TCP"));
    if(h.value("loadPercent").isValid()&&QDateTime::currentMSecsSinceEpoch()-h.value("loadAt").toLongLong()<120000)text+=QStringLiteral(" · Загрузка %1%").arg(qRound(h.value("loadPercent").toDouble()));
    else text+=QStringLiteral(" · Загрузка: нет данных");
    return text;
}
bool FlintController::serverUnavailable(const QString &key) const {
    auto h=m_health.value(healthKey(key)).toMap();return h.value("available").isValid()&&!h.value("available").toBool()&&QDateTime::currentMSecsSinceEpoch()-h.value("checkedAt").toLongLong()<120000;
}
QString FlintController::bestHealthyProfile(const QStringList &profiles) const {
    if(profiles.isEmpty())return {};
    auto best=profiles.first();for(const auto &p:profiles)if(score(m_health.value(serverKey(p)).toMap())<score(m_health.value(serverKey(best)).toMap()))best=p;
    return score(m_health.value(serverKey(best)).toMap())<10000?best:QString();
}
QString FlintController::withWorkingFingerprint(const QString &profile) const {
    const auto fp=m_health.value(serverKey(profile)).toMap().value("fingerprint").toString();
    if(fp.isEmpty()||!profile.startsWith("vless://"))return profile;
    QUrl u(profile);QUrlQuery q(u);q.removeAllQueryItems("fp");q.addQueryItem("fp",fp);u.setQuery(q);return u.toString(QUrl::FullyEncoded);
}
void FlintController::refreshServerHealth() {runServerHealth(false);}
void FlintController::initializeServers() {runServerHealth(true);}
QStringList FlintController::healthProfiles() const {
    auto profiles=cachedProfiles();if(profiles.isEmpty()&&subscriptionUrl().startsWith("vless://"))profiles<<subscriptionUrl();
    const auto manual=m_settings->value("Conf/flintManualImports").toMap();
    for(auto it=manual.begin();it!=manual.end();++it)for(const auto &saved:savedServers())if(saved.toMap().value("id")==it.value()&&!profiles.contains(it.key()))profiles<<it.key();
    return profiles;
}
void FlintController::runServerHealth(bool initialize) {
    if(m_healthBusy)return;
    const auto profiles=healthProfiles();
    if(profiles.isEmpty())return;
    m_healthBusy=true;emit healthChanged();
    const auto previous=m_health;const QString scope=subscriptionUrl();const int epoch=m_apiEpoch;
    QPointer<FlintController> receiver(this);const bool testActive=m_vpnActive&&selectedCountry()=="AUTO";
    const QString active=m_pendingBaseProfile;
    auto *worker=QThread::create([receiver,profiles,previous,scope,epoch,initialize,testActive,active]() {
        QVariantMap result;QElapsedTimer total;total.start();
        for(const auto &p:profiles) {
            if(!receiver||total.elapsed()>(initialize?90000:15000))break;
            QUrl u(p);QVariantMap h{{"checkedAt",QDateTime::currentMSecsSinceEpoch()},{"kind","tcp"},{"latencyMs",-1}};
            const auto key=serverKey(p);const auto old=previous.value(key).toMap();
            h["node"]=endpointKey(u);
            if(u.host().isEmpty()) {result[key]=h;continue;}
            QElapsedTimer timer;timer.start();QTcpSocket socket;socket.connectToHost(u.host(),u.port(443));bool ok=socket.waitForConnected(1800);socket.abort();
            h["available"]=ok;if(ok)h["latencyMs"]=qMax<qint64>(1,timer.elapsed());
            h["fingerprint"]=old.value("fingerprint");
            if(ok && (initialize||(testActive&&p==active))) {
                QUrlQuery q(u);QString first=old.value("fingerprint").toString();if(first.isEmpty())first=q.queryItemValue("fp");if(first.isEmpty())first="chrome";
                QStringList variants{first};if(initialize)variants<<"chrome"<<"firefox"<<"safari"<<"ios"<<"android"<<"edge"<<"360"<<"qq"<<"randomized";variants.removeDuplicates();
                for(const auto &fp:variants) {
                    if(!receiver||total.elapsed()>(initialize?90000:15000)){h.remove("available");break;}
                    auto ms=probe(p,fp);if(ms==-2)break;
                    h["kind"]="vpn";h["available"]=ms>0;h["latencyMs"]=ms;
                    if(ms>0){h["fingerprint"]=fp;break;}
                }
            }
            h["loadPercent"]=old.value("loadPercent");h["loadAt"]=old.value("loadAt");result[key]=h;
        }
        if(receiver)QMetaObject::invokeMethod(receiver,[receiver,result,scope,epoch,initialize]() {
            if(!receiver)return;
            receiver->m_healthBusy=false;
            if(scope==receiver->subscriptionUrl()&&epoch==receiver->m_apiEpoch) {
                receiver->m_health=result;receiver->m_settings->setValue("Conf/flintHealth",result);receiver->m_settings->setValue("Conf/flintHealthScope",scope);
                ++receiver->m_healthRevision;receiver->loadServerTelemetry();
                if(initialize)emit receiver->initializationFinished();
            }
            emit receiver->healthChanged();
        },Qt::QueuedConnection);
    });
    connect(worker,&QThread::finished,worker,&QObject::deleteLater);worker->start();
}
void FlintController::loadServerTelemetry(bool legacy) {
    if(!loggedIn()){evaluateAutomaticSwitch();return;}
    const auto scope=subscriptionUrl();const auto epoch=m_apiEpoch;
    auto req=apiRequest(legacy ? "/server-health" : "/locations",true);req.setTransferTimeout(2500);
    auto reply=m_net.get(req);QTimer::singleShot(3000,reply,[reply](){if(!reply->isFinished())reply->abort();});
    connect(reply,&QNetworkReply::finished,this,[this,reply,scope,epoch,legacy](){
        const auto raw=reply->readAll();reply->deleteLater();if(scope!=subscriptionUrl()||epoch!=m_apiEpoch)return;
        const auto status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if(!legacy && (status==404 || status==501)) {loadServerTelemetry(true);return;}
        const auto document=QJsonDocument::fromJson(raw).object();
        if(status==200 && document.value("items").isArray()) {
            const auto items=document.value("items").toArray();
            for(const auto &profile:healthProfiles()) {auto key=serverKey(profile);auto h=m_health.value(key).toMap();h.remove("loadPercent");h.remove("loadAt");
                if(!legacy) {
                    const auto telemetry=FlintTelemetry::location(document,profile,QDateTime::currentMSecsSinceEpoch());
                    for(auto it=telemetry.begin();it!=telemetry.end();++it)h[it.key()]=it.value();
                } else {
                for(const auto &item:items){const auto o=item.toObject();const auto at=QDateTime::fromString(o.value("measuredAt").toString(),Qt::ISODateWithMs).toMSecsSinceEpoch();const auto age=QDateTime::currentMSecsSinceEpoch()-at;const auto load=o.value("loadPercent");
                    if(o.value("endpointId").toString()==endpointKey(QUrl(profile))&&age>=-30000&&age<120000&&load.isDouble()&&load.toDouble()>=0&&load.toDouble()<=100){h["loadPercent"]=load.toDouble();h["loadAt"]=at;break;}}
                }
                m_health[key]=h;
            }
            m_settings->setValue("Conf/flintHealth",m_health);
        }
        ++m_healthRevision;emit healthChanged();evaluateAutomaticSwitch();
    });
}
void FlintController::evaluateAutomaticSwitch() {
    const auto profiles=cachedProfiles(); QStringList keys;
    for(const auto &p:profiles) keys<<serverKey(p);
    const auto now=QDateTime::currentMSecsSinceEpoch();
    const auto next=m_balance.choose(keys,m_health,serverKey(m_pendingBaseProfile),
        m_vpnActive&&m_tunnelConnected&&selectedCountry()=="AUTO"&&selectedSavedServerId().isEmpty()&&!m_profilePreparing,
        m_connectedAt,now);
    if(next.isEmpty())return;
    for(const auto &p:profiles) if(serverKey(p)==next) {
        m_autoSwitchProfile=p; m_connectedAt=now;
        emit automaticReconnectRequested(); return;
    }
}
