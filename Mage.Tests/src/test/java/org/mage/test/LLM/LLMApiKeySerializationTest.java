package org.mage.test.LLM;

import mage.player.llm.LLMConfig;
import mage.players.LLMPlayerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Pins the apiKey serialization split (fix for GUI 401s, 2026-09-04):
 *
 *  - LLMPlayerConfig.apiKey must be NON-transient: this class travels
 *    client→server over the same Java serialization used for replay files
 *    (JBoss remoting RPC). The GUI/web dialog's key is dropped on the wire
 *    if the field is transient — every authenticated cloud endpoint then
 *    fails with HTTP 401 (observed with hyper.charm.land).
 *
 *  - LLMConfig.apiKey (the runtime config held by LLMPlayer, which reaches
 *    .game replay saves via GameState→players) must be TRANSIENT: the key
 *    must never persist to disk: cleartext keys must never reach replays.
 *
 * Both classes pin their serialVersionUID to the pre-change values so
 * existing .game saves remain loadable.
 */
public class LLMApiKeySerializationTest {

    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
        }
        return bos.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    @Test
    public void apiDialectSurvivesPlayerConfigSerialization() throws Exception {
        // The seat config crosses client→server on the RPC: the new apiDialect
        // field must be serialized (never transient) or the GUI's dialect choice
        // silently disappears on the wire.
        LLMPlayerConfig original = new LLMPlayerConfig();
        original.setApiDialect("anthropic-messages");
        LLMPlayerConfig copy = (LLMPlayerConfig) deserialize(serialize(original));
        Assert.assertEquals("anthropic-messages", copy.getApiDialect());
    }

    @Test
    public void apiDialectSurvivesRuntimeConfigSerialization() throws Exception {
        LLMConfig original = new LLMConfig().apiDialect("openai-responses");
        LLMConfig copy = (LLMConfig) deserialize(serialize(original));
        Assert.assertEquals("openai-responses", copy.getApiDialect());
    }

    @Test
    public void apiDialectDefaultsToChatCompletionsWhenAbsentFromASerializedSave() throws Exception {
        // Old .game replays deserialize LLMConfig without the apiDialect field;
        // Java leaves it null (constructors/field initializers do not run).
        // Null must mean the historical chat-completions dialect, not a crash.
        LLMConfig config = new LLMConfig();
        java.lang.reflect.Field f = LLMConfig.class.getDeclaredField("apiDialect");
        f.setAccessible(true);
        f.set(config, null);
        Assert.assertEquals("chat-completions", config.getApiDialect());
        Assert.assertEquals(mage.player.llm.ApiDialect.CHAT_COMPLETIONS,
                mage.player.llm.DialectCodecs.forConfig(config).dialect());
    }

    @Test
    public void playerConfigKeySurvivesSerialization() throws Exception {
        LLMPlayerConfig original = new LLMPlayerConfig();
        original.setApiKey("charm-test-key-123");
        LLMPlayerConfig copy = (LLMPlayerConfig) deserialize(serialize(original));
        Assert.assertEquals("LLMPlayerConfig.apiKey must survive client→server serialization (RPC)",
                "charm-test-key-123", copy.getApiKey());
    }

    @Test
    public void runtimeConfigKeyNeverPersists() throws Exception {
        LLMConfig original = new LLMConfig()
                .apiEndpoint("https://hyper.charm.land/v1/chat/completions")
                .model("glm-5.3-flash")
                .apiKey("charm-test-key-123");
        LLMConfig copy = (LLMConfig) deserialize(serialize(original));
        // Transient field is skipped by serialization; deserialized value is
        // the field initializer (""), never the original key.
        Assert.assertNotEquals("LLMConfig.apiKey must be transient — key must never persist",
                "charm-test-key-123", copy.getApiKey());
        // Java deserialization bypasses constructors, so the transient field
        // initializer never runs on the read-back path — the value is null.
        Assert.assertNull("deserialized LLMConfig.apiKey must be null (transient, ctor bypassed)",
                copy.getApiKey());
    }

    @Test
    public void copyConstructorStillCarriesKey() throws Exception {
        // LLMPlayer's copy constructor (tournament sub-tables, game 2+)
        // deep-copies via LLMConfig(LLMConfig) — the in-memory key must
        // still propagate there; transient only affects serialization.
        LLMConfig original = new LLMConfig()
                .apiKey("charm-test-key-123");
        LLMConfig copy = new LLMConfig(original);
        Assert.assertEquals("in-memory copy constructor must carry the API key",
                "charm-test-key-123", copy.getApiKey());
    }
}