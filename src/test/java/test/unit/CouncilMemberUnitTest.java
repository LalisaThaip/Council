// package test.java.test.unit;

// import main.java.impl.CouncilMember;
// import org.junit.jupiter.api.*;
// import org.mockito.Mockito;

// import java.io.ByteArrayOutputStream;
// import java.io.PrintStream;
// import java.util.*;
// import java.util.concurrent.atomic.AtomicInteger;

// import static org.junit.jupiter.api.Assertions.*;
// import static org.mockito.Mockito.*;

// /**
//  * Unit tests for CouncilMember methods that can be tested without actual network I/O.
//  */
// public class CouncilMemberUnitTest {

//     private CouncilMember member;
//     private Map<String, String> fakeConfig;

//     @BeforeEach
//     public void setup() {
//         // Create a fake network configuration
//         fakeConfig = new HashMap<>();
//         fakeConfig.put("M1", "localhost:8000");
//         fakeConfig.put("M2", "localhost:8001");
//         fakeConfig.put("M3", "localhost:8002");
//         fakeConfig.put("M4", "localhost:8003");
//         fakeConfig.put("M5", "localhost:8004");

//         // Create a spy of CouncilMember to override network methods
//         member = Mockito.spy(new CouncilMember("M1", "network.config") {
//             @Override
//             public Map<String, String> getNetworkConfig() {
//                 return fakeConfig;
//             }

//             @Override
//             protected void sendMessage(String targetId, String message) {
//                 // Skip actual network
//             }

//             @Override
//             protected void broadcastMessage(String message) {
//                 // Skip actual broadcast
//             }
//         });
//     }

//     @Test
//     public void testCompareProposalNumbers_higher() {
//         var result = member.compareProposalNumbers("1.M2", -1, "");
//         assertTrue(result > 0);

//         result = member.compareProposalNumbers("5.M2", 3, "M1");
//         assertTrue(result > 0);

//         result = member.compareProposalNumbers("3.M3", 3, "M2");
//         assertTrue(result > 0);

//         result = member.compareProposalNumbers("2.M2", 3, "M1");
//         assertTrue(result < 0);

//         result = member.compareProposalNumbers("3.M1", 3, "M1");
//         assertEquals(0, result);
//     }

//     @Test
//     public void testProcessAcceptVote_reachesConsensus() {
//         AtomicInteger consensusCount = new AtomicInteger(0);

//         CouncilMember testMember = spy(member);
//         doAnswer(invocation -> {
//             consensusCount.incrementAndGet();
//             return null;
//         }).when(testMember).broadcastMessage(any());

//         testMember.processAcceptVote("M2", "M1");
//         testMember.processAcceptVote("M3", "M1");
//         testMember.processAcceptVote("M4", "M1");
//         testMember.processAcceptVote("M5", "M1");

//         assertTrue(testMember.hasReachedConsensus(), "Consensus should be true");
//     }

//     @Test
//     public void testSimulateLatency_profiles() {
//         member.setProfile("reliable");
//         assertEquals(10, member.simulateLatency());

//         member.setProfile("failure");
//         assertEquals(0, member.simulateLatency());

//         member.setProfile("standard");
//         int standardLatency = member.simulateLatency();
//         assertTrue(standardLatency >= 50 && standardLatency <= 250);

//         member.setProfile("latent");
//         int latentLatency = member.simulateLatency();
//         assertTrue(latentLatency >= 500 && latentLatency <= 2000);
//     }

//     @Test
//     public void testReceiveMessage_invalidFormat() {
//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         System.setOut(new PrintStream(baos));

//         member.receiveMessage("INVALID_MESSAGE");

//         String output = baos.toString();
//         assertTrue(output.contains("invalid message format"));
//     }

//     @Test
//     public void testPropose_clearsPreviousPromisesAndAccepts() {
//         member.receivedPromises.add("dummy");
//         member.receivedAccepts.add("dummy");

//         member.propose("M1");

//         assertEquals("M1", member.acceptedValue);
//         assertTrue(member.receivedPromises.isEmpty() || member.receivedPromises.size() == 0);
//         assertTrue(member.receivedAccepts.isEmpty());
//     }
// }
