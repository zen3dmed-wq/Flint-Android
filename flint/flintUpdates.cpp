#include "flintUpdates.h"
#include "flintController.h"
#include "flintUpdatePolicy.h"
#include <QJsonDocument>
#include <QDateTime>
#include <QStandardPaths>
#include <QSaveFile>
#include <QDir>
#include <QFile>
#include <QCryptographicHash>
#include <QTimer>
#include <memory>
#ifdef Q_OS_ANDROID
#include <QCoreApplication>
#include <QJniObject>
#include <QJniEnvironment>
#endif
#ifdef Q_OS_IOS
#include <QDesktopServices>
#endif

FlintUpdates::FlintUpdates(FlintController *account, QObject *parent) : QObject(parent), m_account(account) {
    reset();
    connect(account, &FlintController::authChanged, this, [this] {
        if (!m_account->loggedIn()) reset();
        else QTimer::singleShot(1500, this, [this] { check(false); });
    });
    connect(account, &FlintController::apiBaseChanged, this, &FlintUpdates::reset);
    QTimer::singleShot(5000, this, [this] { check(false); });
#ifdef Q_OS_ANDROID
    // startActivity runs asynchronously on Android's main thread. Surface launch
    // failures in the update dialog as well as in the short-lived native toast.
    auto installerMessages = new QTimer(this);
    connect(installerMessages, &QTimer::timeout, this, [this] {
        if (m_state.value("phase") != "ready") return;
        const auto result = QJniObject::callStaticObjectMethod(
            "org/amnezia/vpn/FlintUpdateInstaller", "takeMessage", "()Ljava/lang/String;");
        QJniEnvironment env;
        if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
        const auto message = result.toString();
        if (!message.isEmpty()) { m_state["message"] = message; emit changed(); }
    });
    installerMessages->start(750);
#endif
}
void FlintUpdates::reset() {
    ++m_epoch;
    if (m_reply) m_reply->abort();
    m_reply = nullptr;
    if (!m_file.isEmpty()) QFile::remove(m_file);
    m_file.clear(); m_latest = {}; m_announced.clear(); m_lastCheck = 0;
    m_state = {{"phase", "idle"}, {"version", FlintUpdatePolicy::version}, {"available", false}, {"required", false}, {"progress", 0}};
    emit changed();
}
void FlintUpdates::fail(const QString &message) {
    m_state["phase"] = "error"; m_state["message"] = message; emit changed();
}
void FlintUpdates::fetch(std::function<void(bool)> done) {
    const int epoch = m_epoch;
    m_state["phase"] = "checking"; m_state["message"] = ""; emit changed();
#ifdef Q_OS_IOS
    const QString platform = "ios";
#else
    const QString platform = "android";
#endif
    m_account->authorizedGet("/app/update?platform=" + platform + "&version=" + FlintUpdatePolicy::version,
        [this, epoch, done](int status, const QByteArray &raw, const QString &) {
        if (epoch != m_epoch) return;
        if (status != 200) { fail(status == 401 ? tr("Войдите в аккаунт для проверки обновлений.") : tr("Не удалось проверить обновления. Повторите позже.")); done(false); return; }
        QJsonParseError error;
        const auto document = QJsonDocument::fromJson(raw, &error);
        const auto data = document.object();
        if (error.error != QJsonParseError::NoError || !document.isObject() || !data.value("updateAvailable").isBool() || !data.value("required").isBool()) {
            fail(tr("Сервер вернул некорректный ответ обновления.")); done(false); return;
        }
        if (data.value("updateAvailable").toBool() && FlintUpdatePolicy::parseVersion(data.value("latest").toObject().value("version").toString()).isNull()) {
            fail(tr("В ответе отсутствует версия обновления.")); done(false); return;
        }
        m_lastCheck = QDateTime::currentSecsSinceEpoch();
        m_latest = data.value("latest").toObject(); // null is valid when no release is published.
        const bool update = data.value("updateAvailable").toBool() && FlintUpdatePolicy::newer(m_latest.value("version").toString(), FlintUpdatePolicy::version);
        m_state["available"] = update;
        m_state["required"] = update && (data.value("required").toBool() || FlintUpdatePolicy::newer(data.value("minVersion").toString(), FlintUpdatePolicy::version));
        m_state["latestVersion"] = update ? m_latest.value("version").toString() : QString();
        m_state["notes"] = update ? m_latest.value("notes").toString().left(16000) : QString();
        m_state["phase"] = update ? "available" : "current";
        m_state["message"] = update ? tr("Доступна новая версия Flint.") : tr("Обновлений пока нет.");
        emit changed(); done(true);
    });
}
void FlintUpdates::check(bool manual) {
    const auto phase = m_state.value("phase").toString();
    if (phase == "checking" || phase == "downloading" || phase == "ready") return;
    if (!m_account->loggedIn()) { if (manual) fail(tr("Войдите в аккаунт для проверки обновлений.")); return; }
    if (!manual && m_lastCheck && QDateTime::currentSecsSinceEpoch() - m_lastCheck < 21600) return;
    fetch([this](bool ok) {
        const auto version = m_state.value("latestVersion").toString();
        if (ok && m_state.value("available").toBool() && version != m_announced) { m_announced = version; emit available(); }
    });
}
void FlintUpdates::download() {
    if (!m_state.value("available").toBool() || m_state.value("phase") == "downloading" || m_state.value("phase") == "checking") return;
    // Signed links expire: re-read metadata immediately before downloading.
    const QString expected = m_latest.value("version").toString();
    fetch([this, expected](bool ok) {
        if (!ok || !m_state.value("available").toBool()) return;
        if (m_latest.value("version").toString() != expected) { m_state["message"] = tr("Сборка обновилась. Проверьте описание и нажмите «Скачать» ещё раз."); emit changed(); return; }
#ifdef Q_OS_IOS
        const auto error = FlintUpdatePolicy::validate(m_latest, QUrl(m_account->apiBase()), true);
        if (!error.isEmpty()) { fail(error); return; }
        if (!QDesktopServices::openUrl(QUrl(m_latest.value("url").toString()))) fail(tr("Не удалось открыть страницу обновления."));
#else
        beginDownload();
#endif
    });
}
void FlintUpdates::beginDownload() {
    const auto error = FlintUpdatePolicy::validate(m_latest, QUrl(m_account->apiBase()));
    if (!error.isEmpty()) { fail(error); return; }
    const QString directory = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/flint-updates";
    if (!QDir().mkpath(directory)) { fail(tr("Не удалось создать папку обновления.")); return; }
    m_file = directory + "/update.apk";
    struct Download {
        explicit Download(const QString &path) : file(path) { file.setDirectWriteFallback(false); }
        QSaveFile file; QCryptographicHash hash{QCryptographicHash::Sha256}; qint64 size = 0; bool failed = false;
    };
    const auto download = std::make_shared<Download>(m_file);
    if (!download->file.open(QIODevice::WriteOnly)) { fail(tr("Недостаточно места для обновления.")); return; }
    const qint64 expectedSize = qint64(m_latest.value("size").toDouble());
    const auto expectedHash = m_latest.value("sha256").toString().toLatin1().toLower();
    QNetworkRequest request(QUrl(m_latest.value("url").toString()));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setTransferTimeout(30000);
    auto reply = m_network.get(request); m_reply = reply;
    const int epoch = m_epoch;
    m_state["phase"] = "downloading"; m_state["progress"] = 0; m_state["message"] = tr("Скачивание обновления…"); emit changed();
    auto timer = new QTimer(reply); timer->setSingleShot(true); timer->start(300000);
    connect(timer, &QTimer::timeout, reply, &QNetworkReply::abort);
    auto consume = [this, reply, download, expectedSize, epoch] {
        if (epoch != m_epoch) return;
        const auto bytes = reply->readAll(); download->size += bytes.size();
        if (download->size > expectedSize || download->file.write(bytes) != bytes.size()) { download->failed = true; reply->abort(); return; }
        download->hash.addData(bytes);
        const int progress = int(download->size * 100 / expectedSize);
        if (m_state.value("progress").toInt() != progress) { m_state["progress"] = progress; emit changed(); }
    };
    connect(reply, &QNetworkReply::readyRead, this, consume);
    connect(reply, &QNetworkReply::finished, this, [this, reply, download, consume, expectedSize, expectedHash, epoch] {
        reply->deleteLater();
        if (epoch != m_epoch) { download->file.cancelWriting(); return; }
        consume(); m_reply = nullptr;
        if (download->failed || reply->error() != QNetworkReply::NoError || reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() != 200) {
            download->file.cancelWriting(); fail(tr("Скачивание не завершено. Проверьте интернет и повторите.")); return;
        }
        if (download->size != expectedSize || download->hash.result().toHex() != expectedHash) {
            download->file.cancelWriting(); fail(tr("Файл повреждён. Повторите скачивание.")); return;
        }
        if (!download->file.commit()) { fail(tr("Не удалось сохранить обновление.")); return; }
        m_state["phase"] = "ready"; m_state["message"] = tr("Файл проверен. Можно установить обновление."); emit changed();
    });
}
void FlintUpdates::cancel() {
    if (m_state.value("phase") != "downloading") return;
    ++m_epoch; if (m_reply) m_reply->abort(); m_reply = nullptr;
    m_state["phase"] = "available"; m_state["message"] = tr("Скачивание отменено."); emit changed();
}
void FlintUpdates::install() {
    if (m_state.value("phase") != "ready") return;
#ifdef Q_OS_ANDROID
    const auto activity = QNativeInterface::QAndroidApplication::context();
    const auto path = QJniObject::fromString(m_file);
    const auto hash = QJniObject::fromString(m_latest.value("sha256").toString());
    const auto result = QJniObject::callStaticObjectMethod("org/amnezia/vpn/FlintUpdateInstaller", "install",
        "(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;J)Ljava/lang/String;", activity.object(), path.object(), hash.object(), jlong(m_latest.value("versionCode").toDouble()));
    QJniEnvironment env;
    if (env->ExceptionCheck()) { env->ExceptionClear(); m_state["message"] = tr("Не удалось открыть установку Android."); }
    else m_state["message"] = result.toString().isEmpty() ? tr("Подтвердите установку в окне Android.") : result.toString();
    emit changed();
#endif
}
