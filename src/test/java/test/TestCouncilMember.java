package test;

import main.java.impl.CouncilMember;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * TestCouncilMember
 *
 * This JUnit test simulates a 9-member Paxos cluster under ideal conditions.
 * Each CouncilMember runs in its own thread with a reliable network profile.
 *
 * Scenario 1:
 *   - 9 members: M1–M9
 *   - All network profiles = reliable
 *   - M1 proposes itself ("M1") as Council President
 * Expected outcome:
 *   - Consensus is reached and printed as:
 *     "CONSENSUS: M1 has been elected Council President!"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TestCouncilMember {

    private static final int MEMBER_COUNT = 9;
    private static final List<CouncilMember> members = new ArrayList<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final String CONFIG_FILE = "network.config";

    /**
     * Sets up a simulated network configuration and starts all CouncilMember instances.
     */
    @BeforeAll
    public static void setupNetworkConfig() throws IOException {
        System.out.println("\n===== test for 9 members all profile=reliable proposing M1 =====\n");

        // Create a temporary network.config file for the 9 members.
        try (PrintWriter writer = new PrintWriter(new FileWriter(CONFIG_FILE))) {
            int basePort = 8000;
            for (int i = 1; i <= MEMBER_COUNT; i++) {
                writer.printf("M%d:localhost:%d%n", i, basePort + (i - 1));
            }
        }

        // Instantiate and start all members
        for (int i = 1; i <= MEMBER_COUNT; i++) {
            CouncilMember m = new CouncilMember("M" + i, CONFIG_FILE);
            m.setProfile("reliable"); // all reliable network members
            members.add(m);
            executor.execute(m::start);
        }

        // Allow time for all members to boot up
        sleep(2000);
    }

    /**
     * Test Scenario 1:
     *   Verifies that consensus is achieved when M1 proposes itself.
     */
    @Test
    @Order(1)
    public void testConsensusForM1() {
        // Capture console output for verification
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));

        try {
            // Step 1: M1 initiates a proposal for itself
            members.get(0).propose("M1");

            // Step 2: Wait (up to 10 seconds) for consensus message to appear
            boolean reachedConsensus = waitForConsensus(
                    baos,
                    "CONSENSUS: M1 has been elected Council President!",
                    10000
            );

            // Step 3: Verify consensus was reached
            Assertions.assertTrue(reachedConsensus, "Consensus on M1 was not reached in time!");
        } finally {
            // Restore System.out
            System.setOut(originalOut);
        }
    }

    /**
     * Cleans up threads and temporary files.
     */
    @AfterAll
    public static void cleanup() {
        executor.shutdownNow();
        File f = new File(CONFIG_FILE);
        if (f.exists()) f.delete();
    }

    /**
     * Helper: waits until the output stream contains the expected consensus message.
     */
    private static boolean waitForConsensus(ByteArrayOutputStream baos, String expected, long timeoutMillis) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMillis) {
            String output = baos.toString();
            if (output.contains(expected)) {
                System.out.println(output);
                return true;
            }
            sleep(500);
        }
        return false;
    }

    /**
     * Helper: sleeps for the given number of milliseconds.
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
