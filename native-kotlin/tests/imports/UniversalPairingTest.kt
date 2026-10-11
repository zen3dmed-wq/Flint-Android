package app.flint.prototype

import app.flint.prototype.pairing.PairingProtocol
import app.flint.prototype.imports.ImportException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UniversalPairingTest {
    @Test fun ownerQrAndInstallationPageUseTheSameKeyWithoutAccountToken() {
        val id=PairingProtocol.secret();val key=PairingProtocol.secret()
        val qr=PairingProtocol.shareQr(id,key)
        val parsed=PairingProtocol.parse(qr)
        assertTrue(parsed.shared);assertEquals(id,parsed.id);assertEquals(key,parsed.key)
        assertEquals(key,PairingProtocol.parse("flint://connect/$id#key=$key").key)
        assertFalse(qr.contains("token"));assertFalse(PairingProtocol.shareSecret(key)==key)
        try {PairingProtocol.parse(qr.replace("flintmain.ru","flintmain.ru.attacker.invalid"));fail("Foreign host accepted")}
        catch(_:ImportException) {}
    }
    private fun desktopPayload() = JSONObject().put("version",1)
        .put("subscriptionContent", "vless://00000000-0000-0000-0000-000000000001@node.example.invalid:443?security=tls&sni=node.example.invalid#Desktop")
        .put("subscriptionUrl","https://subscription.example.invalid/test")
        .put("title","Desktop subscription").put("directSites",JSONArray().put("gosuslugi.ru"))
        .put("automaticRouting",true)

    @Test fun desktopAndIosContentCanBeReceivedWithoutAndroidCollectionOrAccount() {
        val id=PairingProtocol.secret();val key=PairingProtocol.secret()
        val result=PairingProtocol.open(id,key,PairingProtocol.seal(id,key,desktopPayload()))
        assertEquals(1,result.profiles.size);assertEquals("node.example.invalid",result.profiles.single().host)
        assertEquals(listOf("gosuslugi.ru"),result.sites);assertNull(result.selectedId)
    }
    @Test fun corruptedCrossPlatformSettingsCannotBecomeProfiles() {
        val id=PairingProtocol.secret();val key=PairingProtocol.secret()
        val envelope=PairingProtocol.seal(id,key,desktopPayload())
        val ciphertext=envelope.getString("ciphertext")
        envelope.put("ciphertext",(if(ciphertext.first()=='A') "B" else "A")+ciphertext.drop(1))
        try {PairingProtocol.open(id,key,envelope);fail("Tampered content accepted")}
        catch (_:ImportException) {}
    }
}
