package mage.webproxy;

import mage.view.RoomUsersView;
import mage.view.UsersView;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Unit checks for the `users_list` payload the new `get_room_users` ws request
 * produces ({@link WebProxySession#serializeRoomUsers}).
 *
 * Verifies:
 *  - every mage.view.UsersView getter lands on the wire under its camelCase name
 *    (no invented fields, no dropped fields);
 *  - a null room / null users list degrades to an empty list (the lobby panel
 *    renders "no users", it does not throw — the room may legitimately be empty);
 *  - multiple RoomUsersView blocks flatten (the server returns one per room;
 *    the proxy asks for the main room but iterates defensively).
 *
 * The FAIL-FAST behavior of the BROWSER side (parseUsersListPayload throwing on
 * malformed users_list payloads) is covered by the frontend harness
 * react/scripts/verify-lobby-chat-users.ts, which runs against JSON fixtures.
 */
public class WebProxyRoomUsersSerializerTest {

    private static UsersView sampleUser(String name) {
        return new UsersView(
                "us", // flagName
                name,
                "12-3", // matchHistory
                5, // matchQuitRatio
                "2-1", // tourneyHistory
                7, // tourneyQuitRatio
                "4 games", // infoGames
                "23 ms", // infoPing
                1500, // generalRating
                1600, // constructedRating
                1550 // limitedRating
        );
    }

    @Test
    public void serializesEveryUsersViewField() {
        RoomUsersView room = new RoomUsersView(Arrays.asList(sampleUser("Alice"), sampleUser("Bob")), 3, 2, 16);
        List<Map<String, Object>> out = WebProxySession.serializeRoomUsers(room);

        Assert.assertEquals("two users serialized", 2, out.size());
        Map<String, Object> alice = out.get(0);
        Assert.assertEquals("us", alice.get("flagName"));
        Assert.assertEquals("Alice", alice.get("userName"));
        Assert.assertEquals("12-3", alice.get("matchHistory"));
        Assert.assertEquals(5, alice.get("matchQuitRatio"));
        Assert.assertEquals("2-1", alice.get("tourneyHistory"));
        Assert.assertEquals(7, alice.get("tourneyQuitRatio"));
        Assert.assertEquals("4 games", alice.get("infoGames"));
        Assert.assertEquals("23 ms", alice.get("infoPing"));
        Assert.assertEquals(1500, alice.get("generalRating"));
        Assert.assertEquals(1600, alice.get("constructedRating"));
        Assert.assertEquals(1550, alice.get("limitedRating"));
        Assert.assertEquals("exactly the 11 UsersView fields, nothing invented", 11, alice.size());
    }

    @Test
    public void nullRoomYieldsEmptyList() {
        Assert.assertTrue(WebProxySession.serializeRoomUsers(null).isEmpty());
    }

    @Test
    public void nullUsersListYieldsEmptyList() {
        Assert.assertTrue(WebProxySession.serializeRoomUsers(new RoomUsersView(null, 0, 0, 0)).isEmpty());
    }

    @Test
    public void nullUserEntriesAreSkipped() {
        RoomUsersView room = new RoomUsersView(Arrays.asList(sampleUser("Alice"), null), 0, 0, 0);
        List<Map<String, Object>> out = WebProxySession.serializeRoomUsers(room);
        Assert.assertEquals(1, out.size());
        Assert.assertEquals("Alice", out.get(0).get("userName"));
    }

    @Test
    public void multipleRoomsFlattenInOrder() {
        RoomUsersView r1 = new RoomUsersView(Arrays.asList(sampleUser("Alice")), 0, 0, 0);
        RoomUsersView r2 = new RoomUsersView(Arrays.asList(sampleUser("Bob")), 0, 0, 0);
        List<Map<String, Object>> out = WebProxySession.serializeRoomUsers(r1);
        out.addAll(WebProxySession.serializeRoomUsers(r2));
        Assert.assertEquals(2, out.size());
        Assert.assertEquals("Alice", out.get(0).get("userName"));
        Assert.assertEquals("Bob", out.get(1).get("userName"));
    }
}