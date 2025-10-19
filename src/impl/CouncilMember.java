package impl;

import interfaces.PaxosNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements the Paxos consensus algorithm as a council member node.
 * Each instance represents a node that can propose, accept, and vote on values
 * to reach distributed consensus. Supports different network profiles (reliable, latent, failure)
 * and handles network communication via sockets.
 */
public class CouncilMember implements PaxosNode {
    // Unique identifier for this council member (e.g., "M1", "M2").
    private final String memberId;
    // Network profile determining message latency and failure behavior (e.g., "reliable", "latent", "failure").
    private String profile;
    // Map of member IDs to their host:port configurations, loaded from network.config.
    private final Map<String, String> networkConfig;
    // Counter for generating unique proposal numbers, incremented per proposal.
    private final AtomicInteger proposalCounter = new AtomicInteger(0);
    // Highest proposal number seen so far, used to compare proposals.
    private int highestProposalNumber = -1;
    // Member ID associated with the highest proposal number.
    private String highestProposalId = "";
    // Value accepted by this node (e.g., candidate name like "M5").
    private String acceptedValue = null;
    // Proposal number of the accepted value.
    private int acceptedProposalNumber = -1;
    // Member ID of the accepted proposal.
    private String acceptedProposalId = "";
    // Flag indicating if the node is running and accepting messages.
    private volatile boolean isRunning = true;
    // Flag indicating if consensus has been reached, stopping further processing.
    private volatile boolean hasReachedConsensus = false;
    // Random number generator for simulating latency and failures.
    private final Random random = new Random();
    // Server socket for receiving Paxos messages (e.g., PREPARE, PROMISE).
    private ServerSocket serverSocket;
    // Server socket for receiving proposal inputs (e.g., candidate names).
    private ServerSocket inputSocket;
    // Thread pool for handling incoming messages and input asynchronously.
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // List of member IDs that sent PROMISE messages for the current proposal.
    private final List<String> receivedPromises = new ArrayList<>();
    // List of member IDs that sent ACCEPTED messages for the current value.
    private final List<String> receivedAccepts = new ArrayList<>();

    /**
     * Constructs a CouncilMember with a given ID and configuration file.
     * Initializes the member with a default profile and loads the network configuration.
     *
     * @param memberId   Unique identifier for this council member (e.g., "M1").
     * @param configFile Path to the network configuration file (e.g., "network.config").
     */
    public CouncilMember(String memberId, String configFile) {
        this.memberId = memberId;
        this.profile = "standard";
        this.networkConfig = NetworkConfig.loadConfig(configFile);
    }

    /**
     * Starts the council member by binding to communication and input ports.
     * Initializes server sockets for Paxos messages and proposal inputs,
     * then spawns threads to handle incoming messages and console input.
     */
    @Override
    public void start() {
        // Extract host and port from network configuration (e.g., "localhost:8001").
        String[] hostPort = networkConfig.get(memberId).split(":");
        int port = Integer.parseInt(hostPort[1]);
        int inputPort = port + 1000; // Input port is offset by 1000 (e.g., 9001 for M1).

        // Attempt to bind the main server socket for Paxos messages.
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                serverSocket = new ServerSocket(port);
                System.out.println(memberId + " started on port " + port);
                break;
            } catch (BindException e) {
                // Handle case where port is already in use, retry up to 3 times.
                System.err.println(memberId + " port " + port + " busy (attempt " + attempt + "), retrying...");
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                // Handle other IO errors and exit if binding fails.
                System.err.println(memberId + " failed to start: " + e.getMessage());
                return;
            }
        }

        // Attempt to bind the input socket for proposal inputs.
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                inputSocket = new ServerSocket(inputPort);
                System.out.println(memberId + " started input socket on port " + inputPort);
                break;
            } catch (BindException e) {
                // Handle case where input port is in use, retry up to 3 times.
                System.err.println(memberId + " input port " + inputPort + " busy (attempt " + attempt + "), retrying...");
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                // Handle other IO errors and exit if binding fails.
                System.err.println(memberId + " failed to start input socket: " + e.getMessage());
                return;
            }
        }

        // Start threads to handle Paxos messages and proposal inputs.
        executor.execute(this::runServer);
        executor.execute(this::readConsoleInput);
    }

    /**
     * Sets the network profile for this council member.
     * Profiles affect message latency and failure behavior (e.g., dropping messages).
     *
     * @param profile The network profile ("reliable", "latent", "failure", "standard").
     */
    @Override
    public void setProfile(String profile) {
        this.profile = profile.toLowerCase();
    }

    /**
     * Initiates a Paxos proposal for a given candidate.
     * Generates a unique proposal number, clears previous promises and accepts,
     * and broadcasts a PREPARE message to all other members.
     *
     * @param candidate The proposed value (e.g., candidate ID like "M5").
     */
    @Override
    public void propose(String candidate) {
        if (hasReachedConsensus) {
            return; // Ignore everything if consensus reached
        }

        if (isRunning && !hasReachedConsensus) {
            // Generate a unique proposal number (counter.memberId, e.g., "1.M4").
            String proposalNumber = proposalCounter.incrementAndGet() + "." + memberId;
            System.out.println(memberId + " proposing candidate " + candidate + " with proposal " + proposalNumber);
            // Clear previous state for this proposal round.
            receivedPromises.clear();
            receivedAccepts.clear();
            acceptedValue = candidate;
            // Broadcast PREPARE message to initiate Paxos phase 1.
            broadcastMessage("PREPARE:" + memberId + ":" + proposalNumber + ":" + candidate);
        } else {
            // Log if proposal cannot be made due to node state.
            System.out.println(memberId + " cannot propose: isRunning=" + isRunning + ", hasReachedConsensus=" + hasReachedConsensus);
        }
    }

    /**
     * Processes incoming Paxos messages (PREPARE, PROMISE, ACCEPT_REQUEST, ACCEPTED).
     * Applies simulated latency and drops messages if required by the profile.
     * Dispatches to appropriate handlers based on message type.
     *
     * @param message The Paxos message received (e.g., "PREPARE:M4:1.M4:M5").
     */
    @Override
    public void receiveMessage(String message) {
        synchronized (this) {
            if (hasReachedConsensus) {
                return; // Ignore everything if consensus reached
            }

            System.out.println(memberId + " received: " + message);
            if (isRunning && !shouldDropMessage()) {
                try { Thread.sleep(simulateLatency()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                String[] parts = message.split(":");
                if (parts.length < 3) {
                    System.err.println(memberId + " invalid message format: " + message);
                    return;
                }

                switch (parts[0]) {
                    case "PREPARE":
                        if (parts.length == 4) handlePrepare(parts);
                        break;
                    case "PROMISE":
                        handlePromise(parts);
                        break;
                    case "ACCEPT_REQUEST":
                        if (parts.length == 4) handleAcceptRequest(parts);
                        break;
                    case "ACCEPTED":
                        if (parts.length == 4) handleAccepted(parts);
                        break;
                    default:
                        System.err.println(memberId + " unknown message type: " + parts[0]);
                }
            } else {
                System.out.println(memberId + " dropped message: " + message);
            }
        }
    }


    /**
     * Runs the server loop to accept and process incoming Paxos messages.
     * Accepts client connections and spawns threads to handle each message.
     */
    private void runServer() {
        while (isRunning && !hasReachedConsensus) {
            try {
                // Accept incoming connection from another member.
                Socket client = serverSocket.accept();
                executor.execute(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                        // Read and process the message.
                        String message = in.readLine();
                        if (message != null) {
                            receiveMessage(message);
                        }
                    } catch (IOException e) {
                        if (isRunning && !hasReachedConsensus) {
                            System.err.println(memberId + " error receiving message: " + e.getMessage());
                        }
                    } finally {
                        try {
                            client.close();
                        } catch (IOException e) {
                            // Ignore client socket close errors.
                        }
                    }
                });
            } catch (IOException e) {
                if (isRunning && !hasReachedConsensus) {
                    System.err.println(memberId + " server error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Reads proposal inputs from the input socket (e.g., port 9001 for M1).
     * Triggers the propose() method when a valid candidate ID is received.
     */
    private void readConsoleInput() {
        while (isRunning && !hasReachedConsensus) {
            try (
                    Socket client = inputSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))
            ) {
                // Read input (e.g., candidate ID like "M5").
                String input = reader.readLine();
                if (input != null && !input.trim().isEmpty()) {
                    System.out.println(memberId + " received proposal input: " + input.trim());
                    propose(input.trim());
                }
            } catch (IOException e) {
                if (isRunning && !hasReachedConsensus) {
                    System.err.println(memberId + " console input error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Handles PREPARE messages in Paxos phase 1.
     * Compares the received proposal number with the highest seen and sends a PROMISE if higher.
     *
     * @param parts Message parts: [0]=PREPARE, [1]=proposerId, [2]=proposalNumber, [3]=candidate.
     */
    private void handlePrepare(String[] parts) {
        String proposerId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];
        // Check if the proposal number is higher than the current highest.
        if (compareProposalNumbers(proposalNumber, highestProposalNumber, highestProposalId) > 0) {
            // Update highest proposal seen.
            String[] numParts = proposalNumber.split("\\.");
            highestProposalNumber = Integer.parseInt(numParts[0]);
            highestProposalId = numParts[1];
            // Construct PROMISE response, including any previously accepted value.
            String response = "PROMISE:" + memberId + ":" + proposalNumber;
            if (acceptedValue != null) {
                response += ":" + acceptedProposalNumber + "." + acceptedProposalId + ":" + acceptedValue;
            }
            sendMessage(proposerId, response);
        }
    }

    /**
     * Handles PROMISE messages in Paxos phase 1.
     * Collects promises and, upon reaching a majority (5 of 9 nodes), sends ACCEPT_REQUEST.
     *
     * @param parts Message parts: [0]=PROMISE, [1]=responderId, [2]=proposalNumber, [3]=acceptedProposal (optional), [4]=acceptedValue (optional).
     */
    private void handlePromise(String[] parts) {
        String responderId = parts[1];
        String proposalNumber = parts[2];
        synchronized (receivedPromises) {
            if (!hasReachedConsensus) {
                // Add responder to the list of received promises.
                receivedPromises.add(responderId);
                // Check if majority (5 of 9 nodes) has been reached.
                if (receivedPromises.size() >= 5) {
                    if (acceptedValue != null) {
                        // Send ACCEPT_REQUEST to move to Paxos phase 2.
                        System.out.println(memberId + " received majority promises, sending ACCEPT_REQUEST for " + acceptedValue);
                        broadcastMessage("ACCEPT_REQUEST:" + memberId + ":" + proposalNumber + ":" + acceptedValue);
                        receivedPromises.clear();
                    } else {
                        System.err.println(memberId + " error: No candidate available for ACCEPT_REQUEST");
                    }
                }
            }
        }
    }

    /**
     * Handles ACCEPT_REQUEST messages in Paxos phase 2.
     * Accepts the proposed value if the proposal number is at least as high as the current highest.
     *
     * @param parts Message parts: [0]=ACCEPT_REQUEST, [1]=proposerId, [2]=proposalNumber, [3]=candidate.
     */
    private void handleAcceptRequest(String[] parts) {
        String proposerId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];
        // Accept the value if the proposal number is valid.
        if (compareProposalNumbers(proposalNumber, highestProposalNumber, highestProposalId) >= 0) {
            String[] numParts = proposalNumber.split("\\.");
            highestProposalNumber = Integer.parseInt(numParts[0]);
            highestProposalId = numParts[1];
            acceptedProposalNumber = highestProposalNumber;
            acceptedProposalId = highestProposalId;
            acceptedValue = candidate;
            // Record this node's acceptance and broadcast ACCEPTED message.
            processAcceptVote(memberId, candidate);
            broadcastMessage("ACCEPTED:" + memberId + ":" + proposalNumber + ":" + candidate);
        }
    }

    /**
     * Handles ACCEPTED messages in Paxos phase 2.
     * Counts votes and declares consensus when a majority (5 of 9 nodes) accepts the value.
     *
     * @param parts Message parts: [0]=ACCEPTED, [1]=responderId, [2]=proposalNumber, [3]=candidate.
     */
    private void handleAccepted(String[] parts) {
        synchronized (this) {
            if (hasReachedConsensus) return;

            String responderId = parts[1];
            String candidate = parts[3];
            System.out.println(memberId + " handling ACCEPTED: " + String.join(":", parts));
            processAcceptVote(responderId, candidate);
        }
    }


    /**
     * Processes an acceptance vote for a candidate.
     * Reaches consensus when a majority (5 of 9 nodes) accepts the same value.
     *
     * @param voterId  The ID of the member that accepted the value (e.g., "M1").
     * @param candidate The accepted candidate (e.g., "M5").
     */
    private void processAcceptVote(String voterId, String candidate) {
        synchronized (this) {
            if (hasReachedConsensus) return;

            receivedAccepts.add(voterId);
            int majority = (networkConfig.size() / 2) + 1;
            if (receivedAccepts.size() >= majority) {
                System.out.println("CONSENSUS: " + candidate + " has been elected Council President!");
                hasReachedConsensus = true;
                isRunning = false;
                receivedAccepts.clear();
                executor.shutdownNow();
                try {
                    if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
                    if (inputSocket != null && !inputSocket.isClosed()) inputSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }


    /**
     * Broadcasts a message to all other council members.
     * Sends the message to each member except this node.
     *
     * @param message The Paxos message to broadcast (e.g., "PREPARE:M4:1.M4:M5").
     */
    private void broadcastMessage(String message) {
        if (hasReachedConsensus) {
            return; // Ignore everything if consensus reached
        }

        System.out.println(memberId + " broadcasting: " + message);
        for (String targetId : networkConfig.keySet()) {
            if (!targetId.equals(memberId)) {
                sendMessage(targetId, message);
            }
        }
    }

    /**
     * Sends a Paxos message to a specific council member.
     * Drops the message if the node is stopped, has reached consensus, or the profile requires it.
     *
     * @param targetId The ID of the target member (e.g., "M1").
     * @param message  The Paxos message to send (e.g., "PROMISE:M2:1.M4").
     */
    private void sendMessage(String targetId, String message) {
        if (isRunning && !hasReachedConsensus && !shouldDropMessage()) {
            System.out.println(memberId + " sending to " + targetId + ": " + message);
            String[] hostPort = networkConfig.get(targetId).split(":");
            try (
                    Socket socket = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                out.println(message);
            } catch (IOException e) {
                System.err.println(memberId + " failed to send to " + targetId + ": " + e.getMessage());
            }
        } else {
            System.out.println(memberId + " dropped message to " + targetId + ": " + message);
        }
    }

    /**
     * Simulates network latency based on the node's profile.
     *
     * @return The latency in milliseconds:
     *         - reliable: 10ms
     *         - latent: 500–2000ms
     *         - standard: 50–250ms
     *         - failure: 0ms
     *         - default: 50ms
     */
    private int simulateLatency() {
        switch (profile) {
            case "reliable":
                return 10;
            case "latent":
                return 500 + random.nextInt(1500);
            case "standard":
                return 50 + random.nextInt(200);
            case "failure":
                return 0;
            default:
                return 50;
        }
    }

    /**
     * Determines if a message should be dropped based on the failure profile.
     * For the "failure" profile, drops messages with 30% probability and crashes
     * the node with 10% probability.
     *
     * @return true if the message should be dropped, false otherwise.
     */
    private boolean shouldDropMessage() {
        if (profile.equals("failure") && random.nextDouble() < 0.3) {
            if (random.nextDouble() < 0.1) {
                // Simulate node crash by stopping the node and exiting.
                isRunning = false;
                hasReachedConsensus = true;
                executor.shutdown();
                try {
                    serverSocket.close();
                    inputSocket.close();
                } catch (IOException e) {
                    // Ignore socket close errors.
                }
                System.out.println(memberId + " has crashed!");
                System.exit(1);
            }
            return true;
        }
        return false;
    }

    /**
     * Compares a new proposal number with the current highest proposal number.
     * Proposal numbers are in the format "counter.memberId" (e.g., "1.M4").
     * Compares counters first, then member IDs if counters are equal.
     *
     * @param newProposal       The new proposal number (e.g., "1.M4").
     * @param currentMax        The current highest proposal counter.
     * @param currentId         The member ID of the current highest proposal.
     * @return Positive if newProposal is higher, negative if lower, zero if equal.
     */
    private int compareProposalNumbers(String newProposal, int currentMax, String currentId) {
        if (currentMax == -1) {
            return 1; // No previous proposal, accept the new one.
        }
        String[] newParts = newProposal.split("\\.");
        int newNum = Integer.parseInt(newParts[0]);
        if (newNum != currentMax) {
            return Integer.compare(newNum, currentMax);
        }
        return newParts[1].compareTo(currentId);
    }

    /**
     * Main entry point for running a council member.
     * Parses command-line arguments and starts the node.
     *
     * @param args Command-line arguments: [0]=memberId, [1]="--profile", [2]=profile.
     */
    public static void main(String[] args) {
        if (args.length != 3 || !args[1].equals("--profile")) {
            System.err.println("Usage: java CouncilMember <memberId> --profile <profile>");
            System.exit(1);
        }
        CouncilMember member = new CouncilMember(args[0], "network.config");
        member.setProfile(args[2]);
        member.start();
    }
}