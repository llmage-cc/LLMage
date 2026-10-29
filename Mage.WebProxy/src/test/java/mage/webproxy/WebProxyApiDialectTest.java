package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.players.LLMPlayerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Frontier Phase 2: the web proxy must accept the per-seat api dialect and keep
 * its server-default view consistent with LLMPlayer.init. Package-private
 * statics, so the test lives in the same package.
 */
public class WebProxyApiDialectTest {

    @After
    public void clearProperty() {
        System.clearProperty("xmage.llm.api_dialect");
    }

    private static LLMPlayerConfig parse(String json) {
        JsonObject lcfg = JsonParser.parseString(json).getAsJsonObject();
        return WebProxySession.parseLLMConfig(lcfg);
    }

    @Test
    public void apiDialectIsParsedFromSeatJson() {
        LLMPlayerConfig c = parse("{\"model\":\"claude-sonnet-4-5\",\"apiDialect\":\"anthropic-messages\"}");
        Assert.assertEquals("anthropic-messages", c.getApiDialect());
    }

    @Test
    public void absentOrDefaultApiDialectLeavesServerDefaultInCharge() {
        Assert.assertNull(parse("{\"model\":\"m\"}").getApiDialect());
        Assert.assertNull(parse("{\"model\":\"m\",\"apiDialect\":\"default\"}").getApiDialect());
        Assert.assertNull(parse("{\"model\":\"m\",\"apiDialect\":\"\"}").getApiDialect());
        Assert.assertNull(parse("{\"model\":\"m\",\"apiDialect\":null}").getApiDialect());
    }

    @Test
    public void serverDefaultApiDialectMirrorsTheEngineDefault() {
        System.clearProperty("xmage.llm.api_dialect");
        Assert.assertEquals("chat-completions", WebProxySession.serverDefaultApiDialect());
        System.setProperty("xmage.llm.api_dialect", "openai-responses");
        Assert.assertEquals("openai-responses", WebProxySession.serverDefaultApiDialect());
    }
}
