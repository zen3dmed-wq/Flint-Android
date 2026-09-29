#include "flintController.h"

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

namespace {
const QString kApiBase = QStringLiteral("https://flintmain.ru/api/v1");
const QString kVersion = QStringLiteral("8.9.7");

bool isProfileUri(const QString &s)
{
    const QString v = s.trimmed().toLower();
    return v.startsWith("vless://") || v.startsWith("vmess://") ||
           v.startsWith("trojan://") || v.startsWith("ss://");
}

QString countryCodeForName(const QString &name)
{
    const QString n = name.toLower();
    struct C { const char *code; const char *name; const char *keys; };
    static const C table[] = {
        {"DE", "Германия", "de germany germany frankfurt германия франкфурт"},
        {"NL", "Нидерланды", "nl netherlands holland нидерланды голландия"},
        {"FI", "Финляндия", "fi finland финляндия helsinki хельсинки"},
        {"FR", "Франция", "fr france франция paris париж"},
        {"SE", "Швеция", "se sweden швеция stockholm стокгольм"},
        {"PL", "Польша", "pl poland польша warsaw варшава"},
        {"UK", "Великобритания", "uk gb united kingdom london британ лондон"},
        {"US", "США", "us usa united states америка сша"},
        {"TR", "Турция", "tr turkey türkiye турция"},
        {"KZ", "Казахстан", "kz kazakhstan казахстан"},
        {"AM", "Армения", "am armenia армения yerevan ереван"},
        {"GE", "Грузия", "ge georgia грузия tbilisi тбилиси"}
    };
    for (const auto &x : table) {
        const QStringList keys = QString::fromLatin1(x.keys).split(' ', Qt::SkipEmptyParts);
        for (const QString &k : keys) {
            if (n.contains(k))
                return QString::fromLatin1(x.code);
        }
    }
    return QString();
}

QString countryNameForCode(const QString &code)
{
    const QString c = code.toUpper();
    if (c == "DE") return QStringLiteral("Германия");
    if (c == "NL") return QStringLiteral("Нидерланды");
    if (c == "FI") return QStringLiteral("Финляндия");
    if (c == "FR") return QStringLiteral("Франция");
    if (c == "SE") return QStringLiteral("Швеция");
    if (c == "PL") return QStringLiteral("Польша");
    if (c == "UK") return QStringLiteral("Великобритания");
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
    QNetworkRequest r(QUrl(kApiBase + path));
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
    if (wanted.isEmpty() || wanted == "AUTO")
        return profiles.first();

    for (const QString &p : profiles) {
        const QString name = profileName(p);
        if (countryCodeForName(name) == wanted ||
            name.contains(wanted, Qt::CaseInsensitive))
            return p;
    }

    return profiles.first();
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
    m_settings->remove("Conf/flintAccessToken");
    m_settings->remove("Conf/flintRefreshToken");
    m_settings->remove("Conf/flintEmail");
    m_settings->remove("Conf/flintTelegramUsername");
    m_settings->remove("Conf/flintSubscriptionUrl");
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
    connect(reply, &QNetworkReply::finished, this, [this, reply, done]() {
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
    connect(reply, &QNetworkReply::finished, this,
        [this, reply, path, done, retry]() {
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
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
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
                        importSubscription();
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
    if (m_telegramLoginId.isEmpty() || m_telegramVerifier.isEmpty()) {
        m_tgTimer.stop();
        return;
    }

    QJsonObject body;
    body["loginId"] = m_telegramLoginId;
    body["codeVerifier"] = m_telegramVerifier;

    postPublic("/auth/telegram/bot/complete", body,
        [this](int status, const QByteArray &raw, const QString &err) {
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

void FlintController::importSubscription()
{
    const QString sub = subscriptionUrl();
    if (!validSubscriptionUrl(sub)) {
        setError(QStringLiteral("Активная подписка не найдена."));
        return;
    }

    if (isProfileUri(sub)) {
        m_settings->setValue("Conf/flintLastProfile", sub);
        const QString directCode = countryCodeForName(profileName(sub));
        m_settings->setValue("Conf/flintLastProfileCountry",
                             directCode.isEmpty() ? QStringLiteral("AUTO") : directCode);
        emit profileReady(sub);
        setAssist(QStringLiteral("Подписка Flint"),
                  QStringLiteral("Профиль готов. Можно подключаться."));
        return;
    }

    QNetworkRequest req(QUrl(sub));
    req.setRawHeader("User-Agent", QByteArray("Flint/") + kVersion.toUtf8());
    req.setRawHeader("X-Client", QByteArray("android/") + kVersion.toUtf8());
    req.setTransferTimeout(9000);

    setBusy(true);
    QNetworkReply *reply = m_net.get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray raw = reply->readAll();
        const QString networkError =
            reply->error() == QNetworkReply::NoError ? QString() : reply->errorString();
        reply->deleteLater();
        setBusy(false);

        if (status >= 200 && status < 300) {
            const QStringList profiles = parseSubscriptionProfiles(raw);
            const QString selected = chooseProfile(profiles);
            if (!selected.isEmpty()) {
                m_settings->setValue("Conf/flintLastProfile", selected);
                m_settings->setValue("Conf/flintLastProfileName", profileName(selected));
                const QString selectedCode = countryCodeForName(profileName(selected));
                m_settings->setValue("Conf/flintLastProfileCountry",
                                     selectedCountry() == "AUTO"
                                         ? QStringLiteral("AUTO")
                                         : (selectedCode.isEmpty() ? selectedCountry() : selectedCode));
                setError(QString());
                emit profileReady(selected);
                setAssist(QStringLiteral("Подписка Flint"),
                          QStringLiteral("Профиль загружен. Можно подключаться."));
                return;
            }
            setError(QStringLiteral("В подписке Flint не найден поддерживаемый профиль."));
        } else {
            setError(networkError.isEmpty()
                ? QStringLiteral("Не удалось загрузить профиль Flint.")
                : networkError);
        }

        const QString cached = m_settings->value("Conf/flintLastProfile").toString().trimmed();
        const QString cachedCountry =
            m_settings->value("Conf/flintLastProfileCountry", "AUTO").toString().toUpper();
        const QString wanted = selectedCountry().toUpper();
        const bool cacheMatches = (wanted == "AUTO") || (cachedCountry == wanted);
        if (isProfileUri(cached) && cacheMatches) {
            emit profileReady(cached);
            setAssist(QStringLiteral("Резервный профиль"),
                      QStringLiteral("Сервер подписки отвечает медленно. Использую последний рабочий профиль."));
        } else if (wanted != "AUTO") {
            setError(QStringLiteral("Не удалось загрузить выбранную локацию. Повторите позже."));
        }
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
