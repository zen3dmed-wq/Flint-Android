package app.flint.prototype.pairing

import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.ServerProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingProtocolTest {
    private val id = PairingProtocol.secret()
    private val key = PairingProtocol.secret()
    private val profile = ServerProfile(ServerProfile.stableId("pairing-test"), "Test node", "node.example.invalid", 443,
        "vless", """{"protocol":"vless","settings":{"vnext":[{"address":"node.example.invalid","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000001"}]}]}}""")
    private fun payload() = PairingProtocol.payload(listOf(profile), "https://subscription.example.invalid/test", "Test plan",
        listOf("gosuslugi.ru", "yandex.ru"), true, """{"version":1,"domains":["domain:gosuslugi.ru"]}""", profile.id)
    @Test fun qrHasNoBackendUrlAccountTokenOrDeviceVerifier() {
        val verifier = PairingProtocol.secret()
        val qr = PairingProtocol.qr(id, key)
        val parsed = PairingProtocol.parse(qr)
        assertEquals(id, parsed.id); assertEquals(key, parsed.key)
        assertFalse(qr.contains(verifier)); assertFalse(qr.contains("http")); assertFalse(parsed.toString().contains(key))
        assertNotEquals(verifier, PairingProtocol.challenge(verifier))
    }
    @Test fun encryptedTransferPreservesAllServersAndRoutingWithoutPlaintextSecrets() {
        val envelope = PairingProtocol.seal(id,key,payload())
        assertFalse(envelope.toString().contains("subscription.example"))
        val transfer = PairingProtocol.open(id,key,envelope)
        assertEquals(profile.id,transfer.profiles.single().id); assertEquals(profile.id,transfer.selectedId)
        assertEquals(listOf("gosuslugi.ru","yandex.ru"),transfer.sites)
        assertTrue(transfer.automaticRouting); assertEquals(1,transfer.policy!!.getInt("version"))
    }
    @Test fun tamperedWrongKeyAndCrossSessionPayloadsAreRejected() {
        val envelope = PairingProtocol.seal(id,key,payload())
        rejects { PairingProtocol.open(id,PairingProtocol.secret(),envelope) }
        rejects { PairingProtocol.open(PairingProtocol.secret(),key,envelope) }
        val changed = JSONObject(envelope.toString()).put("nonce", "AAAAAAAAAAAAAAAA")
        rejects { PairingProtocol.open(id,key,changed) }
    }
    @Test fun qrCannotRedirectToAttackerOrContainMalformedKey() {
        for (value in listOf("https://attacker.invalid/pair?id=$id#key=$key", "flint://pair@attacker.invalid?v=1&id=$id#key=$key",
            "flint://pair?v=1&id=$id#key=bad", "flint://pair?v=2&id=$id#key=$key", "flint://pair?v=1&id=$id&api=http://attacker.invalid#key=$key")) {
            rejects { PairingProtocol.parse(value) }
        }
    }
    @Test fun emptyProfilesInvalidSourceAndOversizedTransferAreRejected() {
        rejects { PairingProtocol.open(id,key,PairingProtocol.seal(id,key,payload().put("subscriptionUrl","http://insecure.invalid/key"))) }
        rejects { PairingProtocol.open(id,key,PairingProtocol.seal(id,key,payload().put("collection",JSONObject("""{"version":1,"profiles":[]}""")))) }
        try { PairingProtocol.seal(id,key,payload().put("oversized","x".repeat(PairingProtocol.MAX_BYTES))); fail("oversized") }
        catch (_: IllegalArgumentException) { }
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Invalid pairing accepted") } catch (_: ImportException) { } }
}
