#include "flintController.h"

#include <QClipboard>
#include <QCryptographicHash>
#include <QDesktopServices>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QSysInfo>
#include <QUuid>
#include <QUrl>

namespace {
const QString kApiBase = QStringLiteral("https://flintmain.ru/api/v1");
const QByteArray kClientHeader = QByteArrayLiteral("android/8.9.4");

QString base64Url(const QByteArray &data)
{
    return QString::fromLatin1(data.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}
}

FlintController::FlintController(SecureQSettings *settings, QObject *parent)
    : QObject(parent), m_settings(settings)
{
    connect(&m_telegramTimer, &QTimer::timeout, this, &FlintController::pollTelegram);
    m_telegramTimer.setInterval(2000);
    QTimer::singleShot(300, this, [this]() {
        const QString refresh = m_settings->value("Conf/flintRefreshToken").toString();
        if (!refresh.isEmpty()) refreshSession();
    });
}

bool FlintController::authenticated() const { return !m_accessToken.isEmpty(); }
QString FlintController::email() const { return m_email; }
QString FlintController::planName() const { return m_planName; }
bool FlintController::subscriptionActive() const { return m_subscriptionActive; }
QString FlintController::subscriptionUrl() const { return m_subscriptionUrl; }
int FlintController::devicesUsed() const { return m_devicesUsed; }
bool FlintController::ruDirectEnabled() const { return m_settings->value("Conf/sitesSplitTunnelingEnabled", true).toBool(); }
QString FlintController::telegramBotUrl() const { return m_telegramBotUrl; }
bool FlintController::busy() const { return m_busy; }
QString FlintController::lastError() const { return m_lastError; }

void FlintController::setBusy(bool value)
{
    if (m_busy == value) return;
    m_busy = value;
    emit busyChanged();
}

void FlintController::setError(const QString &value)
{
    if (m_lastError == value) return;
    m_lastError = value;
    emit lastErrorChanged();
}

QString FlintController::problemText(const QByteArray &raw, int status) const
{
    if (raw.trimmed().isEmpty() && status == 404)
        return QStringLiteral("Сервис Flint временно недоступен.");

    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    const QString detail = o.value("detail").toString();
    if (!detail.isEmpty()) return detail;
    const QString code = o.value("code").toString();
    if (!code.isEmpty()) return code;
    if (status > 0) return QStringLiteral("Ошибка Flint API: HTTP %1").arg(status);
    return QStringLiteral("Не удалось связаться с Flint API.");
}

QNetworkRequest FlintController::request(const QString &path, bool auth) const
{
    QNetworkRequest r(QUrl(kApiBase + path));
    r.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    r.setRawHeader("Accept", "application/json");
    r.setRawHeader("X-Client", kClientHeader);
    if (auth && !m_accessToken.isEmpty())
        r.setRawHeader("Authorization", QByteArray("Bearer ") + m_accessToken.toUtf8());
    r.setTransferTimeout(25000);
    return r;
}

QJsonObject FlintController::deviceObject()
{
    QString deviceId = m_settings->value("Conf/flintDeviceId").toString();
    if (deviceId.isEmpty()) {
        deviceId = QUuid::createUuid().toString(QUuid::WithoutBraces);
        m_settings->setValue("Conf/flintDeviceId", deviceId);
    }

    QString model = QSysInfo::prettyProductName();
    if (model.trimmed().isEmpty()) model = QSysInfo::productType();
    if (model.trimmed().isEmpty()) model = QStringLiteral("Android device");

    return QJsonObject{
        {"deviceId", deviceId},
        {"platform", QStringLiteral("android")},
        {"model", model.left(100)},
        {"osVersion", QSysInfo::productVersion().left(50)},
        {"appVersion", QStringLiteral("8.9.4")}
    };
}

void FlintController::acceptTokens(const QJsonObject &o)
{
    m_accessToken = o.value("accessToken").toString();
    const QString refresh = o.value("refreshToken").toString();
    if (!refresh.isEmpty()) m_settings->setValue("Conf/flintRefreshToken", refresh);
    setError(QString());
    emit authChanged();
}

void FlintController::login(const QString &emailValue, const QString &password)
{
    if (emailValue.trimmed().isEmpty() || password.isEmpty()) {
        setError(QStringLiteral("Введите email и пароль."));
        return;
    }

    setBusy(true);
    const QJsonObject body{
        {"email", emailValue.trimmed()},
        {"password", password},
        {"device", deviceObject()}
    };
    auto *reply = m_net.post(request("/auth/login", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        setBusy(false);
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }
        acceptTokens(QJsonDocument::fromJson(raw).object());
        refreshAll();
        reply->deleteLater();
    });
}

void FlintController::registerAccount(const QString &emailValue, const QString &password)
{
    if (emailValue.trimmed().isEmpty() || password.size() < 8) {
        setError(QStringLiteral("Введите email и пароль не короче 8 символов."));
        return;
    }

    setBusy(true);
    const QJsonObject body{
        {"email", emailValue.trimmed()},
        {"password", password},
        {"device", deviceObject()}
    };
    auto *reply = m_net.post(request("/auth/register", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        setBusy(false);
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }
        acceptTokens(QJsonDocument::fromJson(raw).object());
        refreshAll();
        reply->deleteLater();
    });
}

void FlintController::refreshSession()
{
    const QString refresh = m_settings->value("Conf/flintRefreshToken").toString();
    if (refresh.isEmpty()) {
        m_accessToken.clear();
        emit authChanged();
        return;
    }

    setBusy(true);
    const QJsonObject body{{"refreshToken", refresh}};
    auto *reply = m_net.post(request("/auth/refresh", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        setBusy(false);
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            if (status == 401) {
                m_settings->remove("Conf/flintRefreshToken");
                m_accessToken.clear();
                emit authChanged();
            }
            setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }
        acceptTokens(QJsonDocument::fromJson(raw).object());
        refreshAll();
        reply->deleteLater();
    });
}

void FlintController::logout()
{
    const QString refresh = m_settings->value("Conf/flintRefreshToken").toString();
    if (!refresh.isEmpty()) {
        const QJsonObject body{{"refreshToken", refresh}};
        auto *reply = m_net.post(request("/auth/logout", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::finished, reply, &QObject::deleteLater);
    }

    m_telegramTimer.stop();
    m_accessToken.clear();
    m_email.clear();
    m_planName.clear();
    m_subscriptionUrl.clear();
    m_devicesUsed = 0;
    m_subscriptionActive = false;
    m_settings->remove("Conf/flintRefreshToken");
    emit authChanged();
    emit profileChanged();
    emit subscriptionChanged();
    emit devicesChanged();
}

void FlintController::refreshAll()
{
    if (!authenticated()) return;
    fetchProfile();
    fetchSubscriptions();
    fetchSessions();
}

void FlintController::fetchProfile()
{
    auto *reply = m_net.get(request("/me"));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() == QNetworkReply::NoError && status >= 200 && status < 300) {
            const QJsonObject o = QJsonDocument::fromJson(raw).object();
            m_email = o.value("email").toString();
            emit profileChanged();
        } else if (status == 401) {
            refreshSession();
        } else {
            setError(problemText(raw, status));
        }
        reply->deleteLater();
    });
}

void FlintController::fetchSubscriptions()
{
    auto *reply = m_net.get(request("/subscriptions"));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            if (status == 401) refreshSession();
            else setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }

        const QJsonArray items = QJsonDocument::fromJson(raw).object().value("items").toArray();
        QJsonObject active;
        for (const QJsonValue &v : items) {
            const QJsonObject o = v.toObject();
            if (o.value("status").toString() == QStringLiteral("active")
                && !o.value("subscriptionUrl").toString().isEmpty()) {
                active = o;
                break;
            }
        }

        m_subscriptionActive = !active.isEmpty();
        if (m_subscriptionActive) {
            m_planName = active.value("plan").toObject().value("name").toString();
            m_subscriptionUrl = active.value("subscriptionUrl").toString();
            m_settings->setValue("Conf/flintSubscriptionUrl", m_subscriptionUrl);
            const QString imported = m_settings->value("Conf/flintImportedSubscriptionUrl").toString();
            if (!m_subscriptionUrl.isEmpty() && imported != m_subscriptionUrl) {
                m_settings->setValue("Conf/flintImportedSubscriptionUrl", m_subscriptionUrl);
                emit profileReady(m_subscriptionUrl);
            }
        } else {
            m_planName.clear();
            m_subscriptionUrl = m_settings->value("Conf/flintSubscriptionUrl").toString();
        }
        emit subscriptionChanged();
        reply->deleteLater();
    });
}

void FlintController::fetchSessions()
{
    auto *reply = m_net.get(request("/me/sessions"));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() == QNetworkReply::NoError && status >= 200 && status < 300) {
            m_devicesUsed = QJsonDocument::fromJson(raw).object().value("items").toArray().size();
            emit devicesChanged();
        } else if (status == 401) {
            refreshSession();
        }
        reply->deleteLater();
    });
}

void FlintController::importSubscription()
{
    QString url = m_subscriptionUrl;
    if (url.isEmpty()) url = m_settings->value("Conf/flintSubscriptionUrl").toString();
    if (url.isEmpty()) {
        setError(QStringLiteral("У активной подписки пока нет ссылки."));
        return;
    }
    emit profileReady(url);
}

void FlintController::setRuDirectEnabled(bool enabled)
{
    if (ruDirectEnabled() == enabled) return;
    m_settings->setValue("Conf/sitesSplitTunnelingEnabled", enabled);
    emit ruDirectEnabledChanged();
}

void FlintController::startTelegramLogin()
{
    QByteArray random(32, Qt::Uninitialized);
    for (int i = 0; i < random.size(); i += 4) {
        const quint32 v = QRandomGenerator::system()->generate();
        for (int j = 0; j < 4 && i + j < random.size(); ++j)
            random[i + j] = char((v >> (8 * j)) & 0xff);
    }
    m_telegramVerifier = base64Url(random);
    const QByteArray hash = QCryptographicHash::hash(m_telegramVerifier.toLatin1(), QCryptographicHash::Sha256);
    const QString challenge = base64Url(hash);

    const QJsonObject body{
        {"codeChallenge", challenge},
        {"device", deviceObject()}
    };

    setBusy(true);
    auto *reply = m_net.post(request("/auth/telegram/bot/start", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        setBusy(false);
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }

        const QJsonObject o = QJsonDocument::fromJson(raw).object();
        m_telegramLoginId = o.value("loginId").toString();
        m_telegramBotUrl = o.value("botUrl").toString();
        emit telegramChanged();
        if (!m_telegramBotUrl.isEmpty()) QDesktopServices::openUrl(QUrl(m_telegramBotUrl));
        m_telegramTimer.start();
        reply->deleteLater();
    });
}

void FlintController::pollTelegram()
{
    if (m_telegramLoginId.isEmpty() || m_telegramVerifier.isEmpty()) {
        m_telegramTimer.stop();
        return;
    }

    const QJsonObject body{
        {"loginId", m_telegramLoginId},
        {"codeVerifier", m_telegramVerifier}
    };
    auto *reply = m_net.post(request("/auth/telegram/bot/complete", false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();

        if (status == 202) {
            reply->deleteLater();
            return;
        }

        m_telegramTimer.stop();
        if (reply->error() != QNetworkReply::NoError || status < 200 || status >= 300) {
            setError(problemText(raw, status));
            reply->deleteLater();
            return;
        }

        acceptTokens(QJsonDocument::fromJson(raw).object());
        m_telegramLoginId.clear();
        m_telegramVerifier.clear();
        refreshAll();
        reply->deleteLater();
    });
}
