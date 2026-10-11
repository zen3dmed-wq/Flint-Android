#pragma once
#include <QByteArray>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUrl>
#include <QUrlQuery>
#include <QVariantMap>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <memory>
namespace FlintPairing {
inline QString encode(const QByteArray &b) { return QString::fromLatin1(b.toBase64(QByteArray::Base64UrlEncoding|QByteArray::OmitTrailingEquals)); }
inline QByteArray decode(const QString &s) { return QByteArray::fromBase64(s.toLatin1(),QByteArray::Base64UrlEncoding|QByteArray::AbortOnBase64DecodingErrors); }
inline bool secret(const QString &s) { const auto b=decode(s); return b.size()==32 && encode(b)==s; }
inline QString hash(const QString &s) { return encode(QCryptographicHash::hash(s.toUtf8(),QCryptographicHash::Sha256)); }
inline QString random() { QByteArray b(32,'\0'); return RAND_bytes(reinterpret_cast<unsigned char*>(b.data()),b.size())==1?encode(b):QString(); }
inline QVariantMap state() { auto key=random(), verifier=random(); if(key.isEmpty()||verifier.isEmpty())return {};return {{"key",key},{"verifier",verifier},{"challenge",hash(verifier)},{"claimSecret",hash("Flint-share-claim-v1:"+key)},{"claimChallenge",hash(hash("Flint-share-claim-v1:"+key))}}; }
inline QVariantMap parse(const QString &text) {
    if(text.size()>512)return {}; QUrl u(text,QUrl::StrictMode);if(!u.isValid()||!u.userInfo().isEmpty()||u.port()!=-1)return {};
    QString id;bool shared=false;
    if(u.scheme()=="https"&&u.host()=="flintmain.ru"&&u.path().startsWith("/connect/")&&u.query().isEmpty()){id=u.path().mid(9);shared=true;}
    else if(u.scheme()=="flint"&&u.host()=="connect"&&u.path().startsWith('/')&&u.query().isEmpty()){id=u.path().mid(1);shared=true;}
    else if(u.scheme()=="flint"&&u.host()=="pair"&&u.path().isEmpty()) {QUrlQuery q(u);if(q.queryItems().size()!=2||q.queryItemValue("v")!="1")return {};id=q.queryItemValue("id");}
    else return {};
    const auto f=u.fragment();const auto key=f.mid(4);if(!f.startsWith("key=")||!secret(id)||!secret(key))return {};
    return {{"id",id},{"key",key},{"shared",shared},{"claimSecret",hash("Flint-share-claim-v1:"+key)}};
}
inline QVariantMap seal(const QString &id,const QString &key,const QVariantMap &payload) {
    const auto raw=QJsonDocument(QJsonObject::fromVariantMap(payload)).toJson(QJsonDocument::Compact);
    if(!secret(id)||!secret(key)||raw.size()>512*1024)return {};
    QByteArray nonce(12,'\0');if(RAND_bytes(reinterpret_cast<unsigned char*>(nonce.data()),12)!=1)return {};
    const auto k=decode(key),aad=("Flint-TV-pairing-v1:"+id).toUtf8();
    std::unique_ptr<EVP_CIPHER_CTX,decltype(&EVP_CIPHER_CTX_free)> c(EVP_CIPHER_CTX_new(),EVP_CIPHER_CTX_free);if(!c)return {};
    QByteArray out(raw.size()+16,'\0');int n=0,total=0;auto ptr=[](QByteArray &b){return reinterpret_cast<unsigned char*>(b.data());};auto cp=[](const QByteArray &b){return reinterpret_cast<const unsigned char*>(b.constData());};
    if(EVP_EncryptInit_ex(c.get(),EVP_aes_256_gcm(),nullptr,cp(k),cp(nonce))!=1||EVP_EncryptUpdate(c.get(),nullptr,&n,cp(aad),aad.size())!=1||EVP_EncryptUpdate(c.get(),ptr(out),&n,cp(raw),raw.size())!=1)return {};total=n;
    if(EVP_EncryptFinal_ex(c.get(),ptr(out)+total,&n)!=1)return {};total+=n;out.resize(total+16);
    if(EVP_CIPHER_CTX_ctrl(c.get(),EVP_CTRL_GCM_GET_TAG,16,out.data()+total)!=1)return {};
    return {{"version",1},{"nonce",encode(nonce)},{"ciphertext",encode(out)}};
}
inline QVariantMap open(const QString &id,const QString &key,const QVariantMap &envelope) {
    if(!secret(id)||!secret(key)||envelope.value("version").toInt()!=1||envelope.value("ciphertext").toString().size()>700000)return {};
    const auto nonce=decode(envelope.value("nonce").toString());auto encrypted=decode(envelope.value("ciphertext").toString());if(nonce.size()!=12||encrypted.size()<17||encrypted.size()>512*1024+16)return {};
    auto tag=encrypted.right(16);encrypted.chop(16);const auto k=decode(key),aad=("Flint-TV-pairing-v1:"+id).toUtf8();
    std::unique_ptr<EVP_CIPHER_CTX,decltype(&EVP_CIPHER_CTX_free)> c(EVP_CIPHER_CTX_new(),EVP_CIPHER_CTX_free);if(!c)return {};
    QByteArray raw(encrypted.size()+16,'\0');int n=0,total=0;auto ptr=[](QByteArray &b){return reinterpret_cast<unsigned char*>(b.data());};auto cp=[](const QByteArray &b){return reinterpret_cast<const unsigned char*>(b.constData());};
    if(EVP_DecryptInit_ex(c.get(),EVP_aes_256_gcm(),nullptr,cp(k),cp(nonce))!=1||EVP_DecryptUpdate(c.get(),nullptr,&n,cp(aad),aad.size())!=1||EVP_DecryptUpdate(c.get(),ptr(raw),&n,cp(encrypted),encrypted.size())!=1)return {};total=n;
    if(EVP_CIPHER_CTX_ctrl(c.get(),EVP_CTRL_GCM_SET_TAG,16,tag.data())!=1||EVP_DecryptFinal_ex(c.get(),ptr(raw)+total,&n)!=1)return {};raw.resize(total+n);
    const auto doc=QJsonDocument::fromJson(raw);if(!doc.isObject()||doc.object().value("version").toInt()!=1)return {};return doc.object().toVariantMap();
}
}
