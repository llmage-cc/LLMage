package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.choices.Choice;
import mage.choices.ChoiceImpl;
import mage.view.GameClientMessage;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit test for the suggested-creature-type plumbing

 *
 * The engine already ranks creature-type chooser options in `Choice.getSortData`
 * (ChoiceCreatureType.onChooseStart: me = -100, opponent = -10, unused = 0) and
 * the desktop client sorts its dialog by it (PickChoiceDialog.java:116-127). The
 * web proxy used to send only {index,key,label}, so the browser could not group
 * the suggestions. The fix forwards the engine's value per option as `order`.
 *
 * Contract pinned here:
 *  - a ranked option carries `order` with the engine's exact value;
 *  - an option the engine did not rank has NO `order` field (nothing invented);
 *  - a choice with no sort data at all (null map) is unaffected;
 *  - a simple (non-key) choice keeps its old shape (no order);
 *  - keyChoices order / labels / indices are untouched.
 *
 * Drives the REAL handler on a real WebProxyClient whose BrowserSender is a
 * recording stub — no server, no XMage, same technique as
 * WebProxyRollbackTurnsTest.
 */
public class WebProxyChooseChoiceSerializerTest {

    /** Capture what the handler pushes to the browser. */
    private static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }
    }

    private static RecordingSender invokeChooseChoice(Choice choice) throws Exception {
        WebProxyClient client = new WebProxyClient("tester");
        RecordingSender sender = new RecordingSender();
        client.setWsSender(sender);

        GameClientMessage gcm = new GameClientMessage(null, null, choice);
        Method m = WebProxyClient.class.getDeclaredMethod("handleGameChooseChoice", GameClientMessage.class, mage.view.GameView.class);
        m.setAccessible(true);
        m.invoke(client, gcm, null);

        Assert.assertEquals("exactly one action_prompt pushed", 1, sender.sent.size());
        return sender;
    }

    private static JsonObject optionsEnvelope(RecordingSender sender) {
        JsonObject msg = JsonParser.parseString(sender.sent.get(0)).getAsJsonObject();
        Assert.assertEquals("choose_choice prompt", "action_prompt", msg.get("type").getAsString());
        Assert.assertEquals("choose_choice", msg.get("action_type").getAsString());
        return msg;
    }

    /** One `order` per option index that carries one. */
    private static JsonArray orders(JsonObject msg) {
        return msg.getAsJsonArray("options");
    }

    /**
     * A creature-type chooser as the engine delivers it AFTER onChooseStart:
     * ranked types carry a sortData entry, unranked ones do not.
     */
    private static Choice creatureTypeChoice() {
        Choice choice = new ChoiceImpl(true);
        choice.setMessage("Choose a creature type");
        choice.withItem("Angel", "Angel", null, null, null);
        choice.withItem("Elf", "Elf (me)", null, null, null);
        choice.withItem("Goblin", "Goblin (opponent)", null, null, null);
        choice.withItem("Zombie", "Zombie", null, null, null);
        // onChooseStart's own writes (ChoiceCreatureType.java:106-123)
        choice.setSortData(new java.util.LinkedHashMap<>());
        choice.getSortData().put("Elf", -100);
        choice.getSortData().put("Goblin", -10);
        return choice;
    }

    @Test
    public void forwardsTheEngineOrderPerRankedOption() throws Exception {
        JsonObject msg = optionsEnvelope(invokeChooseChoice(creatureTypeChoice()));
        JsonArray opts = orders(msg);
        Assert.assertEquals("all four options serialized", 4, opts.size());

        JsonObject elf = opts.get(1).getAsJsonObject();
        Assert.assertEquals("Elf", elf.get("key").getAsString());
        Assert.assertEquals("Elf (me)", elf.get("label").getAsString());
        Assert.assertEquals("ranked option carries the engine's exact priority",
                -100, elf.get("order").getAsInt());

        JsonObject goblin = opts.get(2).getAsJsonObject();
        Assert.assertEquals("opponent-ranked option carries -10",
                -10, goblin.get("order").getAsInt());
    }

    @Test
    public void unrankedOptionsCarryNoOrderField() throws Exception {
        JsonObject msg = optionsEnvelope(invokeChooseChoice(creatureTypeChoice()));
        JsonArray opts = orders(msg);

        for (int i : new int[]{0, 3}) {
            JsonObject o = opts.get(i).getAsJsonObject();
            Assert.assertFalse("option " + o.get("key").getAsString()
                    + " was not ranked by the engine and must carry NO order field",
                    o.has("order"));
        }
    }

    @Test
    public void nullSortDataIsHarmless() throws Exception {
        Choice choice = new ChoiceImpl(true);
        choice.setMessage("Choose a color");
        choice.withItem("W", "White", null, null, null);
        choice.withItem("G", "Green", null, null, null);
        // ChoiceImpl's sortData defaults to an empty map; setSortData(null) is the
        // worst case the handler must survive (getSortData() == null).
        choice.setSortData(null);

        JsonObject msg = optionsEnvelope(invokeChooseChoice(choice));
        JsonArray opts = orders(msg);
        Assert.assertEquals(2, opts.size());
        Assert.assertFalse("no order when the engine sent no sort data", opts.get(0).getAsJsonObject().has("order"));
        Assert.assertTrue("labels still render", opts.get(0).getAsJsonObject().get("label").getAsString().equals("White"));
    }

    @Test
    public void simpleChoicesKeepTheirOldShape() throws Exception {
        Choice choice = new ChoiceImpl(true);
        choice.setMessage("Choose one");
        choice.setChoices(new java.util.LinkedHashSet<>(java.util.Arrays.asList("Alpha", "Beta")));
        choice.setSortData(new java.util.LinkedHashMap<>());
        choice.getSortData().put("Alpha", -100);

        JsonObject msg = optionsEnvelope(invokeChooseChoice(choice));
        JsonArray opts = orders(msg);
        Assert.assertEquals(2, opts.size());
        Assert.assertEquals("simple choices render in set order", "Alpha", opts.get(0).getAsJsonObject().get("label").getAsString());
        Assert.assertFalse("the order field belongs to keyChoices only", opts.get(0).getAsJsonObject().has("order"));
    }

    @Test
    public void choiceCountAndKeysAreUntouched() throws Exception {
        JsonObject msg = optionsEnvelope(invokeChooseChoice(creatureTypeChoice()));
        Assert.assertEquals(4, msg.get("choice_count").getAsInt());
        JsonArray opts = orders(msg);
        for (int i = 0; i < opts.size(); i++) {
            Assert.assertEquals("index stays positional", i, opts.get(i).getAsJsonObject().get("index").getAsInt());
        }
        Assert.assertEquals("message untouched", "Choose a creature type", msg.get("message").getAsString());
    }
}
