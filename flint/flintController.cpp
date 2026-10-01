#include "flintController.h"
#include "flintDirectSites.h"

QString FlintController::normalizeDirectSite(const QString &value) const
{
    return FlintDirectSites::normalize(value);
}

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSysInfo>
#include <QUrl>
#include <QUrlQuery>
#include <QUuid>
#include <utility>
#ifdef Q_OS_ANDROID
#include <QCoreApplication>
#include <QJniObject>
#endif

void FlintController::requestHomeWidget()
{
#ifdef Q_OS_ANDROID
    auto activity = QNativeInterface::QAndroidApplication::context();
    if (activity.isValid()) activity.callMethod<void>("requestFlintWidget");
#endif
}

namespace {
const QString kApiBase = QStringLiteral("https://flintmain.ru/api/v1");
const QString kVersion = QStringLiteral("8.10.5");

bool isProfileUri(const QString &s)
{
    const QString v = s.trimmed().toLower();
    return v.startsWith("vless://") || v.startsWith("vmess://") ||
           v.startsWith("trojan://") || v.startsWith("ss://");
}

QString countryCodeForName(const QString &name)
{
    const QString n = name.toLower();
    struct CountryHint { QString code; QString displayName; QStringList keys; };
    const QList<CountryHint> table = {
        {QStringLiteral("FI"), QStringLiteral("Финляндия"),
         {QStringLiteral("финлянд"), QStringLiteral("finland"), QStringLiteral("helsinki"), QStringLiteral("🇫🇮")}},
        {QStringLiteral("DE"), QStringLiteral("Германия"),
         {QStringLiteral("герман"), QStringLiteral("germany"), QStringLiteral("deutsch"), QStringLiteral("frankfurt"), QStringLiteral("🇩🇪")}},
        {QStringLiteral("NL"), QStringLiteral("Нидерланды"),
         {QStringLiteral("нидерланд"), QStringLiteral("netherlands"), QStringLiteral("holland"), QStringLiteral("amsterdam"), QStringLiteral("🇳🇱")}},
        {QStringLiteral("FR"), QStringLiteral("Франция"),
         {QStringLiteral("франц"), QStringLiteral("france"), QStringLiteral("paris"), QStringLiteral("🇫🇷")}},
        {QStringLiteral("SE"), QStringLiteral("Швеция"),
         {QStringLiteral("швец"), QStringLiteral("sweden"), QStringLiteral("stockholm"), QStringLiteral("🇸🇪")}},
        {QStringLiteral("CH"), QStringLiteral("Швейцария"),
         {QStringLiteral("швейцар"), QStringLiteral("switzerland"), QStringLiteral("zurich"), QStringLiteral("🇨🇭")}},
        {QStringLiteral("GB"), QStringLiteral("Великобритания"),
         {QStringLiteral("британ"), QStringLiteral("united kingdom"), QStringLiteral("london"), QStringLiteral(" uk "), QStringLiteral("🇬🇧")}},
        {QStringLiteral("US"), QStringLiteral("США"),
         {QStringLiteral("сша"), QStringLiteral("usa"), QStringLiteral("united states"), QStringLiteral("new york"), QStringLiteral("🇺🇸")}},
        {QStringLiteral("TR"), QStringLiteral("Турция"),
         {QStringLiteral("турц"), QStringLiteral("turkey"), QStringLiteral("türkiye"), QStringLiteral("istanbul"), QStringLiteral("🇹🇷")}},
        {QStringLiteral("KZ"), QStringLiteral("Казахстан"),
         {QStringLiteral("казахстан"), QStringLiteral("kazakhstan"), QStringLiteral("almaty"), QStringLiteral("astana"), QStringLiteral("🇰🇿")}},
        {QStringLiteral("AM"), QStringLiteral("Армения"),
         {QStringLiteral("армени"), QStringLiteral("armenia"), QStringLiteral("yerevan"), QStringLiteral("ереван"), QStringLiteral("🇦🇲")}},
        {QStringLiteral("GE"), QStringLiteral("Грузия"),
         {QStringLiteral("грузи"), QStringLiteral("georgia"), QStringLiteral("tbilisi"), QStringLiteral("тбилиси"), QStringLiteral("🇬🇪")}}
    };
    for (const CountryHint &country : table) {
        for (const QString &key : country.keys) {
            if (n.contains(key))
                return country.code;
        }
    }
    return QString();
}

QString countryNameForCode(const QString &code)
{
    const QString c = code.toUpper();
    if (c == "FI") return QStringLiteral("Финляндия");
    if (c == "DE") return QStringLiteral("Германия");
    if (c == "NL") return QStringLiteral("Нидерланды");
    if (c == "FR") return QStringLiteral("Франция");
    if (c == "SE") return QStringLiteral("Швеция");
    if (c == "CH") return QStringLiteral("Швейцария");
    if (c == "GB" || c == "UK") return QStringLiteral("Великобритания");
    if (c == "US") return QStringLiteral("США");
    if (c == "TR") return QStringLiteral("Турция");
    if (c == "KZ") return QStringLiteral("Казахстан");
    if (c == "AM") return QStringLiteral("Армения");
    if (c == "GE") return QStringLiteral("Грузия");
    return c;
}
}

FlintController::FlintController(SecureQSettings *settings, QObject *parent)
    : QObject(parent), m_settings(settings)
{
    // 8.9.7/8.9.8 could leave partially written auth/QR state behind when the
    // QML engine aborted during startup. Remove only Flint session/cache keys
    // once; VPN engine settings are preserved. The user may need to sign in once.
    const int startupSchema = m_settings->value("Conf/flintStartupSchema", 0).toInt();
    if (startupSchema < 899) {
        const QStringList transientKeys = {
            "Conf/flintAccessToken",
            "Conf/flintRefreshToken",
            "Conf/flintSubscriptionUrl",
            "Conf/flintTelegramLoginId",
            "Conf/flintTelegramVerifier",
            "Conf/flintTelegramBotUrl",
            "Conf/flintLastProfile",
            "Conf/flintLastProfileName"
        };
        for (const QString &key : transientKeys)
            m_settings->remove(key);
        m_settings->setValue("Conf/flintStartupSchema", 899);
    }
    QVariantMap a;
    a["code"] = "AUTO";
    a["name"] = QStringLiteral("Автоматически");
    m_countries << a;

    ensureDeviceId();
    m_subscriptionActive = validSubscriptionUrl(subscriptionUrl());
    m_email = m_settings->value("Conf/flintEmail").toString();
    m_telegramUsername = m_settings->value("Conf/flintTelegramUsername").toString();
    m_telegramLoginId = m_settings->value("Conf/flintTelegramLoginId").toString();
    m_telegramVerifier = m_settings->value("Conf/flintTelegramVerifier").toString();
    m_telegramBotUrl = m_settings->value("Conf/flintTelegramBotUrl").toString();
    m_sessionsCount = m_settings->value("Conf/flintSessionsCount", 0).toInt();

    m_tgTimer.setInterval(3000);
    connect(&m_tgTimer, &QTimer::timeout, this, &FlintController::checkTelegramLogin);
    if (!m_telegramLoginId.isEmpty() && !m_telegramVerifier.isEmpty())
        m_tgTimer.start();
}

bool FlintController::loggedIn() const
{
    return !m_settings->value("Conf/flintRefreshToken").toString().trimmed().isEmpty();
}

QString FlintController::subscriptionUrl() const
{
    return m_settings->value("Conf/flintSubscriptionUrl").toString().trimmed();
}

QString FlintController::apiBase() const
{
    return m_settings->value("Conf/flintApiBase", kApiBase).toString();
}

bool FlintController::setApiBase(const QString &base)
{
    QString value = base.trimmed();
    while (value.endsWith('/')) value.chop(1);
    const QUrl url(value);
    if (!url.isValid() || url.scheme() != "https" || url.host().isEmpty() ||
        !url.userInfo().isEmpty() || url.hasQuery() || url.hasFragment() || url.path().contains("..")) {
        setError(QStringLiteral("Введите HTTPS-адрес API без пароля, параметров и фрагмента."));
        return false;
    }
    if (value == apiBase()) return true;
    // Responses from the previous authority must not populate the new account.
    ++m_apiEpoch;
    m_tgTimer.stop();
    m_telegramCheckInFlight = false;
    m_telegramLoginId.clear(); m_telegramVerifier.clear(); m_telegramBotUrl.clear();
    for (const auto &key : {"Conf/flintTelegramLoginId", "Conf/flintTelegramVerifier", "Conf/flintTelegramBotUrl"}) m_settings->remove(key);
    m_refreshInFlight = false; m_refreshWaiters.clear();
    for (auto *reply : m_net.findChildren<QNetworkReply*>()) reply->abort();
    clearAuthState();
    m_settings->setValue("Conf/flintApiBase", value);
    setBusy(false); setError(QString());
    emit telegramChanged(); emit apiBaseChanged();
    refreshConfig();
    return true;
}

QString FlintController::newRequestKey() const { return QUuid::createUuid().toString(QUuid::WithoutBraces); }

QVariantMap FlintController::clientDraft(const QString &name) const
{
    if (name != "purchase" && name != "support") return {};
    return QJsonDocument::fromJson(m_settings->value("Conf/flintDraft/" + name).toByteArray()).object().toVariantMap();
}

void FlintController::saveClientDraft(const QString &name, const QVariantMap &value)
{
    if (name != "purchase" && name != "support") return;
    const QString key = "Conf/flintDraft/" + name;
    if (value.isEmpty()) m_settings->remove(key);
    else m_settings->setValue(key, QJsonDocument(QJsonObject::fromVariantMap(value)).toJson(QJsonDocument::Compact));
}

void FlintController::accountRequest(const QString &id, const QString &method, const QString &path,
                                     const QVariantMap &body, const QString &key)
{
    static const QRegularExpression allowed(QStringLiteral(
        "^/(config|me(/sessions)?|subscriptions(/[A-Za-z0-9_-]+/devices(/[A-Za-z0-9_-]+)?)?|plans|payment-methods|orders(/[A-Za-z0-9_-]+(/(payment-link|cancel))?)?|referrals(/apply)?|support/tickets(/[A-Za-z0-9_-]+)?)$"));
    static const QRegularExpression deviceDelete(QStringLiteral("^/subscriptions/[A-Za-z0-9_-]+/devices/[A-Za-z0-9_-]+$"));
    if (!allowed.match(path).hasMatch() || (method != "GET" && method != "POST" && !(method == "DELETE" && deviceDelete.match(path).hasMatch()))) {
        emit accountResponse(id, 400, {}, QStringLiteral("Операция API не поддерживается")); return;
    }
    if (method == "POST" && (path == "/orders" || path == "/support/tickets") && key.isEmpty()) {
        emit accountResponse(id, 400, {}, QStringLiteral("Не указан ключ повторного запроса")); return;
    }
    accountRequestImpl(id, method, path, body, key, true);
}

void FlintController::accountRequestImpl(const QString &id, const QString &method, const QString &path,
                                         const QVariantMap &body, const QString &key, bool retry)
{
    const bool authorized = path != "/config";
    if (authorized && !loggedIn()) { emit accountResponse(id, 401, {}, QStringLiteral("Войдите в аккаунт Flint")); return; }
    auto request = apiRequest(path, authorized);
    if (!key.isEmpty()) request.setRawHeader("Idempotency-Key", key.toUtf8());
    auto *reply = method == "DELETE" ? m_net.deleteResource(request) : method == "GET" ? m_net.get(request)
        : m_net.post(request, QJsonDocument(QJsonObject::fromVariantMap(body)).toJson(QJsonDocument::Compact));
    QTimer::singleShot(12000, reply, [reply]() { if (!reply->isFinished()) reply->abort(); });
    const int epoch = m_apiEpoch;
    connect(reply, &QNetworkReply::finished, this, [this, reply, id, method, path, body, key, retry, epoch]() {
        if (epoch != m_apiEpoch) { reply->deleteLater(); return; }
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray raw = reply->readAll();
        const QJsonDocument document = QJsonDocument::fromJson(raw);
        reply->deleteLater();
        if (status == 401 && retry) {
            refreshAccessToken([this, id, method, path, body, key, epoch](bool ok) {
                if (epoch != m_apiEpoch) return;
                if (ok) accountRequestImpl(id, method, path, body, key, false);
                else emit accountResponse(id, 401, {}, QStringLiteral("Сессия истекла. Войдите повторно."));
            }); return;
        }
        QString error;
        if (status < 200 || status >= 300) {
            error = document.object().value("detail").toString();
            if (error.isEmpty()) error = status == 0 ? QStringLiteral("Нет ответа сервера. Повторите запрос.")
                : QStringLiteral("Операция недоступна на подключённом API (HTTP %1).").arg(status);
        } else if (status != 204 && !document.isObject()) error = QStringLiteral("Некорректный ответ API");
        emit accountResponse(id, status, document.object().toVariantMap(), error);
    });
}

QString FlintController::selectedCountry() const
{
    return m_settings->value("Conf/flintSelectedCountry", "AUTO").toString();
}

bool FlintController::ruDirectEnabled() const
{
    return m_settings->value("Conf/sitesSplitTunnelingEnabled", true).toBool();
}

QString FlintController::ensureDeviceId()
{
    QString id = m_settings->value("Conf/flintDeviceId").toString().trimmed();
    if (id.isEmpty()) {
        id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        m_settings->setValue("Conf/flintDeviceId", id);
        m_settings->sync();
    }
    return id;
}

QByteArray FlintController::deviceJson() const
{
    QJsonObject d;
    d["deviceId"] = m_settings->value("Conf/flintDeviceId").toString();
    d["platform"] = "android";
    d["model"] = QSysInfo::prettyProductName();
    d["osVersion"] = QSysInfo::productVersion();
    d["appVersion"] = kVersion;
    return QJsonDocument(d).toJson(QJsonDocument::Compact);
}

QNetworkRequest FlintController::apiRequest(const QString &path, bool authorized) const
{
    QNetworkRequest r(QUrl(apiBase() + path));
    r.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    r.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    r.setRawHeader("X-Client", QByteArray("android/") + kVersion.toUtf8());
    r.setRawHeader("User-Agent", QByteArray("Flint/") + kVersion.toUtf8());
    r.setTransferTimeout(9000);
    if (authorized) {
        const QString token = m_settings->value("Conf/flintAccessToken").toString();
        if (!token.isEmpty())
            r.setRawHeader("Authorization", QByteArray("Bearer ") + token.toUtf8());
    }
    return r;
}

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

void FlintController::setAssist(const QString &title, const QString &reply)
{
    m_assistTitle = title;
    m_assistReply = reply;
    emit assistChanged();
}

QString FlintController::b64url(const QByteArray &in)
{
    return QString::fromLatin1(in.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

bool FlintController::validSubscriptionUrl(const QString &value) const
{
    const QUrl u(value.trimmed());
    const QString s = u.scheme().toLower();
    return u.isValid() && (s == "https" || s == "http" || s == "vless" ||
                           s == "vmess" || s == "trojan" || s == "ss");
}

QString FlintController::profileName(const QString &uri)
{
    const QUrl u(uri);
    const QString fragment = QUrl::fromPercentEncoding(u.fragment(QUrl::FullyEncoded).toUtf8()).trimmed();
    return fragment.isEmpty() ? u.host() : fragment;
}

QStringList FlintController::parseSubscriptionProfiles(const QByteArray &raw) const
{
    QList<QByteArray> candidates;
    const QByteArray trimmed = raw.trimmed();
    candidates << raw;
    const QByteArray stdDecoded = QByteArray::fromBase64(trimmed, QByteArray::Base64Encoding);
    const QByteArray urlDecoded = QByteArray::fromBase64(trimmed, QByteArray::Base64UrlEncoding);
    if (!stdDecoded.isEmpty()) candidates << stdDecoded;
    if (!urlDecoded.isEmpty() && urlDecoded != stdDecoded) candidates << urlDecoded;

    QStringList profiles;
    const QRegularExpression ws(QStringLiteral("[\\r\\n\\t ]+"));
    for (const QByteArray &candidate : candidates) {
        const QString text = QString::fromUtf8(candidate);
        const QStringList parts = text.split(ws, Qt::SkipEmptyParts);
        for (const QString &part : parts) {
            const QString p = part.trimmed();
            if (isProfileUri(p) && !profiles.contains(p))
                profiles << p;
        }
        if (!profiles.isEmpty())
            break;
    }
    return profiles;
}

void FlintController::updateCountriesFromProfiles(const QStringList &profiles)
{
    QVariantList out;
    QVariantMap autoItem;
    autoItem["code"] = "AUTO";
    autoItem["name"] = QStringLiteral("Автоматически");
    out << autoItem;

    QStringList seen;
    for (const QString &p : profiles) {
        const QString name = profileName(p);
        const QString code = countryCodeForName(name);
        if (code.isEmpty() || seen.contains(code))
            continue;
        seen << code;
        QVariantMap m;
        m["code"] = code;
        m["name"] = countryNameForCode(code);
        out << m;
    }

    m_countries = out;
    emit countriesChanged();
}

QString FlintController::chooseProfile(const QStringList &profiles)
{
    if (profiles.isEmpty())
        return QString();

    updateCountriesFromProfiles(profiles);

    const QString wanted = selectedCountry().trimmed().toUpper();
    if (wanted.isEmpty() || wanted == "AUTO") {
        const QString lastWorking = m_settings->value("Conf/flintWorkingProfile").toString();
        if (profiles.contains(lastWorking)) return lastWorking;
        const QString lastUsed = m_settings->value("Conf/flintLastProfile").toString();
        if (profiles.contains(lastUsed)) return lastUsed;
        return profiles.first();
    }

    for (const QString &p : profiles) {
        const QString name = profileName(p);
        if (countryCodeForName(name) == wanted ||
            name.contains(wanted, Qt::CaseInsensitive))
            return p;
    }

    return {};
}

void FlintController::saveTokens(const QJsonObject &obj)
{
    const QString access = obj.value("accessToken").toString();
    const QString refresh = obj.value("refreshToken").toString();
    if (!access.isEmpty()) m_settings->setValue("Conf/flintAccessToken", access);
    if (!refresh.isEmpty()) m_settings->setValue("Conf/flintRefreshToken", refresh);
    emit authChanged();
}

void FlintController::clearAuthState()
{
    ++m_apiEpoch;
    cancelProfileImport();
    m_refreshInFlight = false;
    m_refreshWaiters.clear();
    setBusy(false);
    saveClientDraft("purchase", {});
    saveClientDraft("support", {});
    m_settings->remove("Conf/flintAccessToken");
    m_settings->remove("Conf/flintRefreshToken");
    m_settings->remove("Conf/flintEmail");
    m_settings->remove("Conf/flintTelegramUsername");
    m_settings->remove("Conf/flintSubscriptionUrl");
    for (const auto &key : {"Conf/flintCachedProfiles", "Conf/flintCachedProfilesUrl", "Conf/flintWorkingProfile", "Conf/flintLastProfile", "Conf/flintLastProfileUrl"}) m_settings->remove(key);
    m_settings->remove("Conf/flintSessionsCount");
    m_email.clear();
    m_telegramUsername.clear();
    m_subscriptionActive = false;
    m_sessionsCount = 0;
    emit authChanged();
    emit subscriptionChanged();
}

void FlintController::postPublic(
    const QString &path,
    const QJsonObject &body,
    std::function<void(int,const QByteArray&,const QString&)> done)
{
    QNetworkReply *reply =
        m_net.post(apiRequest(path, false), QJsonDocument(body).toJson(QJsonDocument::Compact));
    const int epoch = m_apiEpoch;
    connect(reply, &QNetworkReply::finished, this, [this, reply, done, epoch]() {
        if (epoch != m_apiEpoch) { reply->deleteLater(); return; }
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray raw = reply->readAll();
        const QString err =
            reply->error() == QNetworkReply::NoError ? QString() : reply->errorString();
        const bool online = reply->error() == QNetworkReply::NoError || status > 0;
        if (m_apiOnline != online) {
            m_apiOnline = online;
            emit apiStatusChanged();
        }
        reply->deleteLater();
        done(status, raw, err);
    });
}

void FlintController::refreshAccessToken(std::function<void(bool)> done)
{
    // Flint refresh tokens rotate. Only one refresh request may use the current
    // token; all concurrent 401 handlers wait for the same result.
    m_refreshWaiters.append(std::move(done));
    if (m_refreshInFlight)
        return;

    const QString rt = m_settings->value("Conf/flintRefreshToken").toString();
    if (rt.isEmpty()) {
        auto waiters = std::move(m_refreshWaiters);
        m_refreshWaiters.clear();
        for (const auto &cb : waiters) cb(false);
        return;
    }

    m_refreshInFlight = true;
    QJsonObject body;
    body["refreshToken"] = rt;
    postPublic("/auth/refresh", body,
        [this](int status, const QByteArray &raw, const QString &err) {
            bool ok = false;
            if (status >= 200 && status < 300) {
                const QJsonObject o = QJsonDocument::fromJson(raw).object();
                if (!o.value("accessToken").toString().isEmpty() &&
                    !o.value("refreshToken").toString().isEmpty()) {
                    saveTokens(o);
                    ok = true;
                }
            }

            if (!ok && status == 401) {
                clearAuthState();
                setError(QStringLiteral("Сессия Flint истекла. Войдите снова."));
            } else if (!ok && !err.isEmpty()) {
                setError(QStringLiteral("Не удалось обновить сессию: ") + err);
            }

            m_refreshInFlight = false;
            auto waiters = std::move(m_refreshWaiters);
        m_refreshWaiters.clear();
            for (const auto &cb : waiters) cb(ok);
        });
}

void FlintController::authorizedGet(
    const QString &path,
    std::function<void(int,const QByteArray&,const QString&)> done,
    bool retry)
{
    if (!loggedIn()) {
        done(401, QByteArray(), QStringLiteral("Вход не выполнен"));
        return;
    }

    QNetworkReply *reply = m_net.get(apiRequest(path, true));
    const int epoch = m_apiEpoch;
    connect(reply, &QNetworkReply::finished, this,
        [this, reply, path, done, retry, epoch]() {
            if (epoch != m_apiEpoch) { reply->deleteLater(); return; }
            const int status =
                reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            const QByteArray raw = reply->readAll();
            const QString err =
                reply->error() == QNetworkReply::NoError ? QString() : reply->errorString();
            reply->deleteLater();

            if (status == 401 && retry) {
                refreshAccessToken([this, path, done](bool ok) {
                    if (!ok) {
                        done(401, QByteArray(), QStringLiteral("Сессия истекла"));
                        return;
                    }
                    authorizedGet(path, done, false);
                });
                return;
            }
            done(status, raw, err);
        });
}

void FlintController::refreshConfig()
{
    QNetworkReply *reply = m_net.get(apiRequest("/config", false));
    const int epoch = m_apiEpoch;
    connect(reply, &QNetworkReply::finished, this, [this, reply, epoch]() {
        if (epoch != m_apiEpoch) { reply->deleteLater(); return; }
        const int status =
            reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray raw = reply->readAll();
        const bool online = status >= 200 && status < 500;
        if (m_apiOnline != online) {
            m_apiOnline = online;
            emit apiStatusChanged();
        }
        if (status >= 200 && status < 300) {
            const QJsonObject maintenance =
                QJsonDocument::fromJson(raw).object().value("maintenance").toObject();
            if (maintenance.value("enabled").toBool()) {
                setAssist(QStringLiteral("Технические работы"),
                          maintenance.value("message").toString(
                              QStringLiteral("Сервис временно недоступен.")));
            }
        }
        reply->deleteLater();
    });
}

void FlintController::refreshAccount()
{
    if (!loggedIn()) return;
    setBusy(true);

    authorizedGet("/me",
        [this](int status, const QByteArray &raw, const QString &) {
            if (status >= 200 && status < 300) {
                const QJsonObject o = QJsonDocument::fromJson(raw).object();
                m_email = o.value("email").toString();
                m_telegramUsername =
                    o.value("telegram").toObject().value("username").toString();
                m_settings->setValue("Conf/flintEmail", m_email);
                m_settings->setValue("Conf/flintTelegramUsername", m_telegramUsername);
                emit authChanged();
            }
        });

    // /subscriptions only refreshes the cache. A working VPN connection never
    // waits for this endpoint; importSubscription() can use the cached URL.
    authorizedGet("/subscriptions",
        [this](int status, const QByteArray &raw, const QString &err) {
            if (status >= 200 && status < 300) {
                const QJsonArray items =
                    QJsonDocument::fromJson(raw).object().value("items").toArray();
                QString found;
                for (const QJsonValue &v : items) {
                    const QJsonObject s = v.toObject();
                    if (s.value("status").toString().compare(
                            "active", Qt::CaseInsensitive) == 0) {
                        found = s.value("subscriptionUrl").toString().trimmed();
                        if (!found.isEmpty()) break;
                    }
                }

                if (validSubscriptionUrl(found)) {
                    const bool changed = found != subscriptionUrl();
                    m_settings->setValue("Conf/flintSubscriptionUrl", found);
                    m_subscriptionActive = true;
                    emit subscriptionChanged();
                    if (changed)
                        importSubscription(false);
                } else {
                    // A successful response without an active subscription is
                    // authoritative. Keep cached access only on network failure.
                    m_settings->remove("Conf/flintSubscriptionUrl");
                    m_subscriptionActive = false;
                    emit subscriptionChanged();
                }
                setError(QString());
            } else if (!validSubscriptionUrl(subscriptionUrl()) && !err.isEmpty()) {
                setError(QStringLiteral("Не удалось обновить подписку: ") + err);
            }
            setBusy(false);
        });

    authorizedGet("/me/sessions",
        [this](int status, const QByteArray &raw, const QString &) {
            if (status >= 200 && status < 300) {
                m_sessionsCount =
                    QJsonDocument::fromJson(raw).object().value("items").toArray().size();
                m_settings->setValue("Conf/flintSessionsCount", m_sessionsCount);
                emit subscriptionChanged();
            }
        });
}

void FlintController::refresh()
{
    refreshConfig();
    m_subscriptionActive = validSubscriptionUrl(subscriptionUrl());
    emit subscriptionChanged();

    if (loggedIn()) {
        refreshAccount();
    } else {
        setAssist(QStringLiteral("Войдите во Flint"),
                  QStringLiteral("Один аккаунт работает на Windows, Android и TV."));
    }
}

void FlintController::login(const QString &email, const QString &password)
{
    if (email.trimmed().isEmpty() || password.isEmpty()) {
        setError(QStringLiteral("Введите Email и пароль."));
        return;
    }

    setBusy(true);
    setError(QString());

    QJsonObject body;
    body["email"] = email.trimmed();
    body["password"] = password;
    body["device"] = QJsonDocument::fromJson(deviceJson()).object();

    postPublic("/auth/login", body,
        [this](int status, const QByteArray &raw, const QString &err) {
            if (status >= 200 && status < 300) {
                saveTokens(QJsonDocument::fromJson(raw).object());
                setAssist(QStringLiteral("Вход выполнен"),
                          QStringLiteral("Загружаю подписку и устройства…"));
                refreshAccount();
            } else {
                setBusy(false);
                setError(err.isEmpty()
                    ? QStringLiteral("Не удалось войти. Проверьте Email и пароль.")
                    : err);
            }
        });
}

void FlintController::registerAccount(const QString &email, const QString &password)
{
    if (email.trimmed().isEmpty() || password.length() < 8) {
        setError(QStringLiteral("Введите Email и пароль не короче 8 символов."));
        return;
    }

    setBusy(true);
    setError(QString());

    QJsonObject body;
    body["email"] = email.trimmed();
    body["password"] = password;
    body["referralCode"] = QJsonValue::Null;
    body["device"] = QJsonDocument::fromJson(deviceJson()).object();

    postPublic("/auth/register", body,
        [this](int status, const QByteArray &raw, const QString &err) {
            if (status >= 200 && status < 300) {
                saveTokens(QJsonDocument::fromJson(raw).object());
                setAssist(QStringLiteral("Аккаунт создан"),
                          QStringLiteral("Flint синхронизирует подписку и устройства."));
                refreshAccount();
            } else {
                setBusy(false);
                setError(err.isEmpty()
                    ? QStringLiteral("Не удалось создать аккаунт.")
                    : err);
            }
        });
}

void FlintController::logout()
{
    const QString rt = m_settings->value("Conf/flintRefreshToken").toString();
    if (!rt.isEmpty()) {
        QJsonObject body;
        body["refreshToken"] = rt;
        postPublic("/auth/logout", body,
                   [](int,const QByteArray&,const QString&){});
    }

    m_tgTimer.stop();
    m_settings->remove("Conf/flintTelegramLoginId");
    m_settings->remove("Conf/flintTelegramVerifier");
    m_settings->remove("Conf/flintTelegramBotUrl");
    m_telegramLoginId.clear();
    m_telegramVerifier.clear();
    m_telegramBotUrl.clear();

    clearAuthState();
    setError(QString());
    setAssist(QStringLiteral("Вы вышли"),
              QStringLiteral("Для подключения снова войдите во Flint."));
    emit telegramChanged();
}

void FlintController::startTelegramLogin()
{
    if (m_busy) return;
    m_tgTimer.stop();
    m_telegramLoginId.clear();
    m_telegramBotUrl.clear();
    m_telegramCheckInFlight = false;
    for (const auto &key : {"Conf/flintTelegramLoginId", "Conf/flintTelegramVerifier", "Conf/flintTelegramBotUrl"}) m_settings->remove(key);
    QByteArray random(32, Qt::Uninitialized);
    for (int i = 0; i < random.size(); ++i)
        random[i] = char(QRandomGenerator::system()->bounded(256));

    m_telegramVerifier = b64url(random);
    const QString challenge = b64url(
        QCryptographicHash::hash(
            m_telegramVerifier.toUtf8(), QCryptographicHash::Sha256));

    QJsonObject body;
    body["codeChallenge"] = challenge;
    body["device"] = QJsonDocument::fromJson(deviceJson()).object();

    setBusy(true);
    setError(QString());

    postPublic("/auth/telegram/bot/start", body,
        [this](int status, const QByteArray &raw, const QString &err) {
            setBusy(false);
            if (status < 200 || status >= 300) {
                setError(err.isEmpty()
                    ? QStringLiteral("Не удалось начать вход через Telegram.")
                    : err);
                return;
            }

            const QJsonObject o = QJsonDocument::fromJson(raw).object();
            m_telegramLoginId = o.value("loginId").toString();
            m_telegramBotUrl = o.value("botUrl").toString();

            m_settings->setValue("Conf/flintTelegramLoginId", m_telegramLoginId);
            m_settings->setValue("Conf/flintTelegramVerifier", m_telegramVerifier);
            m_settings->setValue("Conf/flintTelegramBotUrl", m_telegramBotUrl);

            emit telegramChanged();
            if (!m_telegramLoginId.isEmpty()) m_tgTimer.start();

            setAssist(QStringLiteral("Telegram"),
                      QStringLiteral("Откройте бота и подтвердите вход. Flint завершит авторизацию автоматически."));
        });
}

void FlintController::checkTelegramLogin()
{
    if (m_telegramCheckInFlight) return;
    if (m_telegramLoginId.isEmpty() || m_telegramVerifier.isEmpty()) {
        m_tgTimer.stop();
        return;
    }

    QJsonObject body;
    body["loginId"] = m_telegramLoginId;
    body["codeVerifier"] = m_telegramVerifier;
    const QString loginId = m_telegramLoginId;
    m_telegramCheckInFlight = true;

    postPublic("/auth/telegram/bot/complete", body,
        [this, loginId](int status, const QByteArray &raw, const QString &err) {
            if (loginId != m_telegramLoginId) return;
            m_telegramCheckInFlight = false;
            if (status == 202) return;

            if (status >= 200 && status < 300) {
                m_tgTimer.stop();
                saveTokens(QJsonDocument::fromJson(raw).object());

                m_settings->remove("Conf/flintTelegramLoginId");
                m_settings->remove("Conf/flintTelegramVerifier");
                m_settings->remove("Conf/flintTelegramBotUrl");
                m_telegramLoginId.clear();
                m_telegramVerifier.clear();
                m_telegramBotUrl.clear();

                emit telegramChanged();
                setAssist(QStringLiteral("Telegram подключён"),
                          QStringLiteral("Вход выполнен. Загружаю подписку и устройства…"));
                refreshAccount();
                return;
            }

            if (status != 0 && status != 408) {
                m_tgTimer.stop();
                if (status == 410) {
                    m_telegramLoginId.clear();
                    m_telegramVerifier.clear();
                    m_telegramBotUrl.clear();
                    m_settings->remove("Conf/flintTelegramLoginId");
                    m_settings->remove("Conf/flintTelegramVerifier");
                    m_settings->remove("Conf/flintTelegramBotUrl");
                    emit telegramChanged();
                    setError(QStringLiteral("Попытка входа больше недействительна. Начните вход заново."));
                    return;
                }
                setError(err.isEmpty()
                    ? QStringLiteral("Telegram-вход не подтверждён.")
                    : err);
            }
        });
}

void FlintController::setSelectedCountry(const QString &value)
{
    const QString v =
        value.trimmed().isEmpty() ? QStringLiteral("AUTO") : value.trimmed().toUpper();
    if (selectedCountry() == v) return;
    m_settings->setValue("Conf/flintSelectedCountry", v);
    emit selectedCountryChanged();
}

void FlintController::setRuDirectEnabled(bool enabled)
{
    if (ruDirectEnabled() == enabled) return;
    m_settings->setValue("Conf/sitesSplitTunnelingEnabled", enabled);
    emit ruDirectEnabledChanged();
}

QVariantList FlintController::savedServers() const
{
    const QString managedId = m_settings->value("Conf/flintProfileServerId").toString();
    QVariantList result;
    for (const auto &server : m_savedServers) {
        if (server.toMap().value("id").toString() != managedId)
            result.append(server);
    }
    return result;
}

QString FlintController::selectedSavedServerId() const
{
    for (const auto &server : savedServers()) {
        if (server.toMap().value("id").toString() == m_defaultServerId)
            return m_defaultServerId;
    }
    return {};
}

void FlintController::syncSavedServers(const QVariantList &servers, const QString &defaultServerId)
{
    if (m_savedServers == servers && m_defaultServerId == defaultServerId) return;
    if (m_defaultServerId != defaultServerId && m_profileReply) cancelProfileImport();
    m_savedServers = servers;
    m_defaultServerId = defaultServerId;
    emit savedServersChanged();
}

void FlintController::setManagedProfileServerId(const QString &serverId)
{
    m_settings->setValue("Conf/flintProfileServerId", serverId);
    emit savedServersChanged();
}

void FlintController::setProfilePreparing(bool preparing)
{
    if (m_profilePreparing == preparing) return;
    m_profilePreparing = preparing;
    emit profilePreparingChanged();
}

void FlintController::cancelProfileImport()
{
    ++m_profileEpoch;
    if (m_profileReply) m_profileReply->abort();
    setProfilePreparing(false);
}

QStringList FlintController::cachedProfiles() const
{
    if (m_settings->value("Conf/flintCachedProfilesUrl").toString() != subscriptionUrl()) return {};
    return parseSubscriptionProfiles(m_settings->value("Conf/flintCachedProfiles").toByteArray());
}

void FlintController::publishProfile(const QString &profile)
{
    m_pendingProfile = profile;
    if (!m_attemptedProfiles.contains(profile)) m_attemptedProfiles.append(profile);
    m_settings->setValue("Conf/flintLastProfile", profile);
    m_settings->setValue("Conf/flintLastProfileUrl", subscriptionUrl());
    m_settings->setValue("Conf/flintLastProfileCountry", selectedCountry());
    setError({});
    setProfilePreparing(true);
    emit profileReady(profile);
    // CoreController confirms the actual import before the UI starts a tunnel.
}

void FlintController::profileInstallResult(bool success)
{
    setProfilePreparing(false);
    if (success) m_settings->setValue("Conf/flintInstalledProfile", m_pendingProfile);
    if (!success) setError(QStringLiteral("Не удалось подготовить профиль. Выберите другую локацию или обновите подписку."));
    emit profilePreparationFinished(success);
}

void FlintController::markProfileConnected()
{
    if (!m_pendingProfile.isEmpty() && m_defaultServerId == m_settings->value("Conf/flintProfileServerId").toString())
        m_settings->setValue("Conf/flintWorkingProfile", m_pendingProfile);
}

bool FlintController::tryNextAutomaticProfile()
{
    if (selectedCountry() != "AUTO" || !selectedSavedServerId().isEmpty() || m_attemptedProfiles.size() >= 3) return false;
    for (const auto &profile : cachedProfiles()) {
        if (!m_attemptedProfiles.contains(profile)) {
            publishProfile(profile);
            return true;
        }
    }
    return false;
}

void FlintController::importSubscription(bool selectProfile, bool forceRefresh)
{
    if (!selectProfile && (m_profileReply || m_profilePreparing)) return;
    if (selectProfile) {
        cancelProfileImport();
        m_attemptedProfiles.clear();
        setError({});
    }
    const QString sub = subscriptionUrl();
    if (!validSubscriptionUrl(sub)) {
        if (selectProfile) profileInstallResult(false);
        return;
    }
    if (isProfileUri(sub)) {
        updateCountriesFromProfiles({sub});
        if (selectProfile) publishProfile(sub);
        return;
    }
    const auto cached = cachedProfiles();
    if (selectProfile && !forceRefresh) {
        const QString selected = chooseProfile(cached);
        if (!selected.isEmpty()) { publishProfile(selected); return; }
        // Upgrade path: the previous build only saved one profile. AUTO can
        // keep using it while the endpoint is unavailable, without a busy UI.
        const QString legacy = m_settings->value("Conf/flintLastProfile").toString();
        if (selectedCountry() == "AUTO" && isProfileUri(legacy) &&
            m_settings->value("Conf/flintLastProfileUrl", sub).toString() == sub) {
            publishProfile(legacy);
            return;
        }
    }
    QNetworkRequest req{QUrl(sub)};
    req.setRawHeader("User-Agent", QByteArray("Flint/") + kVersion.toUtf8());
    req.setRawHeader("X-Client", QByteArray("android/") + kVersion.toUtf8());
    req.setTransferTimeout(8000);
    if (selectProfile) setProfilePreparing(true);
    const int profileEpoch = m_profileEpoch;
    const int apiEpoch = m_apiEpoch;
    auto *reply = m_net.get(req);
    m_profileReply = reply;
    // Transfer timeout is an inactivity timeout; add an absolute deadline too.
    QTimer::singleShot(10000, reply, [reply]() { if (!reply->isFinished()) reply->abort(); });
    connect(reply, &QNetworkReply::finished, this, [this, reply, sub, selectProfile, profileEpoch, apiEpoch]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray raw = reply->isOpen() ? reply->readAll() : QByteArray();
        reply->deleteLater();
        if (m_profileReply == reply) m_profileReply.clear();
        if (apiEpoch != m_apiEpoch || profileEpoch != m_profileEpoch) return;
        if (sub != subscriptionUrl()) { if (selectProfile) profileInstallResult(false); return; }
        if (status >= 200 && status < 300) {
            const auto profiles = parseSubscriptionProfiles(raw);
            if (!profiles.isEmpty()) {
                m_settings->setValue("Conf/flintCachedProfiles", profiles.join('\n').toUtf8());
                m_settings->setValue("Conf/flintCachedProfilesUrl", sub);
                updateCountriesFromProfiles(profiles);
                if (!selectProfile) return;
                const QString selected = chooseProfile(profiles);
                if (!selected.isEmpty()) { publishProfile(selected); return; }
            }
        }
        // A failed background refresh never blocks Connect or replaces a
        // usable local profile with an English network error.
        if (!selectProfile) return;
        const QString selected = chooseProfile(cachedProfiles());
        if (!selected.isEmpty()) { publishProfile(selected); return; }
        setProfilePreparing(false);
        setError(QStringLiteral("Сервер подписки не ответил. Выберите сохранённый сервер или повторите обновление."));
        emit profilePreparationFinished(false);
    });
}

void FlintController::askAssist(const QString &message)
{
    const QString m = message.toLower();
    if (m.contains(QStringLiteral("подключ"))) {
        setAssist(QStringLiteral("Подключение"),
                  QStringLiteral("Flint использует последний рабочий профиль, даже если API или сервер подписки временно отвечает медленно."));
    } else if (m.contains(QStringLiteral("росс")) ||
               m.contains(QStringLiteral("закуп"))) {
        setAssist(QStringLiteral("Российские сервисы"),
                  QStringLiteral("zakupki.gov.ru, ЕИС и другие выбранные российские сервисы идут напрямую."));
    } else {
        setAssist(QStringLiteral("Flint Assist"),
                  QStringLiteral("Обновите аккаунт или повторите подключение."));
    }
}
