package org.mage.test.LLM;

import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Same-second filename collision guard for LLMClient summary writers

 *
 * writePostGameSummaryTo() is called TWICE per Bo3 game 1 — once for the
 * post-game reflection (game end) and once for the revised strategy (during
 * sideboarding, via generateRevisedStrategy()). Both stamp
 * {player}_{model}_postgame-<yyyyMMdd-HHmmss>.txt; measured 276-292 ms apart,
 * i.e. inside the same second. Pre-guard,
 * the second Files.write truncated the first file and the game-1 reflection
 * was silently lost.
 *
 * Contract pinned here:
 *  - Two postgame summary writes landing on the same second take distinct
 *    filenames (-1 suffix, same loop as initTranscript) and NEITHER content
 *    is lost.
 *  - The sideboard summary writer has the same guard (a re-queued
 *    sideboarding can write twice within one second).
 *  - Post-game and sideboard writes in the same second never collide with
 *    each other (distinct filename prefixes) and both survive.
 */
public class LLMPostGameFilenameCollisionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LLMClient clientWithTempDir() throws IOException {
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://127.0.0.1:1/v1/chat/completions")
                .model("unit-test-model")
                .transcriptDir(tmp.getRoot().getAbsolutePath());
        return new LLMClient(config);
    }

    private List<Path> filesMatching(String needle) throws IOException {
        try (Stream<Path> walk = Files.walk(tmp.getRoot().toPath())) {
            List<Path> out = new ArrayList<>();
            for (Path p : walk.toArray(Path[]::new)) {
                if (Files.isRegularFile(p) && p.getFileName().toString().contains(needle)) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    private List<String> namesWithContent(String needle, String exactContent) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : filesMatching(needle)) {
            if (readSafe(p).equals(exactContent)) {
                hits.add(p.getFileName().toString());
            }
        }
        return hits;
    }

    private int countFilesWithContent(String needle, String exactContent) throws IOException {
        return namesWithContent(needle, exactContent).size();
    }

    private String readSafe(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    /**
     * Reflection + revised strategy both land in the same second (the measured
     * production failure, 276-292 ms apart): exactly two files must exist and
     * each must carry its own content byte-intact.
     */
    @Test
    public void postgameTwiceSameSecondNeitherSummaryIsLost() throws Exception {
        LLMClient client = clientWithTempDir();
        String reflection = "REFLECTION-CONTENT-GAME-1";
        String strategy = "STRATEGY-CONTENT-POST-SIDEBOARD";

        client.writePostGameSummary("PlayerA", "testmodel", reflection);
        client.writePostGameSummary("PlayerA", "testmodel", strategy);

        List<Path> postgameFiles = filesMatching("_postgame-");
        Assert.assertEquals("two distinct postgame files must survive a same-second write",
                2, postgameFiles.size());
        Assert.assertEquals("the reflection must survive byte-intact in exactly one file",
                1, countFilesWithContent("_postgame-", reflection));
        Assert.assertEquals("the revised strategy must survive byte-intact in exactly one file",
                1, countFilesWithContent("_postgame-", strategy));

        List<String> reflectionNames = namesWithContent("_postgame-", reflection);
        List<String> strategyNames = namesWithContent("_postgame-", strategy);
        Assert.assertNotEquals("the two summaries must NOT share a filename",
                reflectionNames.get(0), strategyNames.get(0));
        // Suffix convention, same as initTranscript: base name, then -1.
        String reflectionName = reflectionNames.get(0);
        String strategyName = strategyNames.get(0);
        boolean suffixConventionHonored = reflectionName.endsWith(".txt")
                && strategyName.endsWith(".txt")
                && (strategyName.contains("-1.txt") || reflectionName.contains("-1.txt"));
        Assert.assertTrue("the second same-second write must take a -N suffix filename ("
                        + reflectionName + " vs " + strategyName + ")",
                suffixConventionHonored);
    }

    /**
     * Re-queued sideboarding writes twice within one second: both
     * sideboard summaries must survive (guard on the sideboard writer).
     */
    @Test
    public void sideboardTwiceSameSecondBothSummariesSurvive() throws Exception {
        LLMClient client = clientWithTempDir();
        String first = "SIDEBOARD-SUMMARY-FIRST";
        String second = "SIDEBOARD-SUMMARY-SECOND";

        client.writeSideboardSummary("PlayerA", "testmodel", first);
        client.writeSideboardSummary("PlayerA", "testmodel", second);

        Assert.assertEquals("two distinct sideboard files must survive a same-second write",
                2, filesMatching("_sideboard-").size());
        Assert.assertEquals(1, countFilesWithContent("_sideboard-", first));
        Assert.assertEquals(1, countFilesWithContent("_sideboard-", second));
    }

    /**
     * Reflection (game end) + sideboard summary (sideboarding start) in the
     * same second: distinct prefixes must already keep them apart, and both
     * must survive.
     */
    @Test
    public void postgameThenSideboardSameSecondBothSurvive() throws Exception {
        LLMClient client = clientWithTempDir();
        String reflection = "REFLECTION-THEN-SIDEBOARD-REFLECTION";
        String sideboard = "REFLECTION-THEN-SIDEBOARD-SUMMARY";

        client.writePostGameSummary("PlayerA", "testmodel", reflection);
        client.writeSideboardSummary("PlayerA", "testmodel", sideboard);

        Assert.assertEquals(1, countFilesWithContent("_postgame-", reflection));
        Assert.assertEquals(1, countFilesWithContent("_sideboard-", sideboard));
    }
}