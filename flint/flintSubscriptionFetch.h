#pragma once
#include <QObject>
#include <QFile>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QTimer>
#include <QUrl>
#include <functional>

// An isolated manager per attempt prevents public proxies from ever receiving
// requests or Authorization headers belonging to the account API.
class FlintSubscriptionFetch : public QObject {
    QUrl url; QStringList proxies;int next=0,active=0;bool done=false;
    std::function<bool(const QByteArray&)> accepts;
    std::function<void(QByteArray)> complete;
    void finish(const QByteArray &raw) {
        if(done)return;done=true;
        for(auto reply:findChildren<QNetworkReply*>())reply->abort();
        complete(raw);deleteLater();
    }
    void launch(const QString &proxy) {
        if(done)return;++active;
        auto manager=new QNetworkAccessManager(this);
        if(proxy.isEmpty())manager->setProxy(QNetworkProxy::NoProxy);
        else {QUrl p(proxy);manager->setProxy(QNetworkProxy(QNetworkProxy::HttpProxy,p.host(),p.port()));}
        QNetworkRequest req(url);req.setRawHeader("User-Agent","Flint/8.10.11");req.setTransferTimeout(4000);
        req.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::NoLessSafeRedirectPolicy);
        req.setMaximumRedirectsAllowed(3);
        auto reply=manager->get(req);
        QTimer::singleShot(5500,reply,[reply](){if(!reply->isFinished())reply->abort();});
        connect(reply,&QNetworkReply::readyRead,this,[reply](){if(reply->bytesAvailable()>(4<<20))reply->abort();});
        connect(reply,&QNetworkReply::finished,this,[this,reply,manager,proxy](){
            --active;const auto raw=reply->readAll();const auto status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            const bool ok=reply->error()==QNetworkReply::NoError&&status>=200&&status<300&&raw.size()<=(4<<20)&&accepts(raw);
            manager->deleteLater();if(done)return;
            if(ok){finish(raw);return;}
            if(proxy.isEmpty() && url.scheme()=="https" && url.userInfo().isEmpty() && url.host()!="localhost") {
                QHostAddress target(url.host());
                if(!target.isNull()&&(target.isLoopback()||target.isLinkLocal()||target.isSiteLocal()||target.isInSubnet(QHostAddress("10.0.0.0"),8)||target.isInSubnet(QHostAddress("172.16.0.0"),12)||target.isInSubnet(QHostAddress("192.168.0.0"),16))){finish({});return;}
                QFile file(":/ui/qml/Assets/flint-import-proxies.json");if(file.open(QIODevice::ReadOnly)){
                    for(const auto &value:QJsonDocument::fromJson(file.readAll()).object().value("proxies").toArray()){
                        QUrl p(value.toString());QHostAddress address(p.host());
                        if(p.scheme()=="http"&&p.userInfo().isEmpty()&&p.port()>0&&p.path().isEmpty()&&!address.isNull()&&address.isGlobal()&&proxies.size()<20)proxies<<p.toString();
                    }
                }
            }
            while(!done&&active<4&&next<proxies.size())launch(proxies[next++]);
            if(active==0)finish({});
        });
    }
public:
    FlintSubscriptionFetch(QObject *parent,const QUrl &source,std::function<bool(const QByteArray&)> valid,std::function<void(QByteArray)> callback):QObject(parent),url(source),accepts(valid),complete(callback){
        QTimer::singleShot(0,this,[this](){launch({});});
        QTimer::singleShot(38000,this,[this](){finish({});});
    }
    void cancel(){if(done)return;done=true;for(auto reply:findChildren<QNetworkReply*>())reply->abort();deleteLater();}
};
