package impl;

import interfaces.PaxosNode;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CouncilMember implements PaxosNode {
    private final String memberId;
    private String profile;
    private final Map<String, String> networkConfig;
    private final AtomicInteger proposalCounter = new AtomicInteger(0);
    private int highestProposalNumber = -1;
    private String acceptedValue = null;
    private int acceptedProposalNumber = -1;
    private volatile boolean isRunning = true;
    private final Random random = new Random();
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> receivedPromises = new ArrayList<>();
    private final List<String> receivedAccepts = new ArrayList<>();

    public CouncilMember(String memberId, String configFile) {
        this.memberId = memberId;
        this.profile = "standard";
        this.networkConfig = NetworkConfig.loadConfig(configFile);
    }

    @Override
    public void start() {
        try {
            String[] hostPort = networkConfig.get(memberId).split(":");
            serverSocket = new ServerSocket(Integer.parseInt(hostPort[1]));
            System.out.println(memberId + " started on port " + hostPort[1]);

            // Start server thread
            executor.execute(this::runServer);

            // Start console input thread
            executor.execute(this::readConsoleInput);
        } catch (IOException e) {
            System.err.println(memberId + " failed to start: " + e.getMessage());
        }
    }

    @Override
    public void setProfile(String profile) {
        this.profile = profile.toLowerCase();
    }

    @Override
    public void propose(String candidate) {
        if (!isRunning) return;
        String proposalNumber = proposalCounter.incrementAndGet() + "." + memberId;
        receivedPromises.clear();
        receivedAccepts.clear();
        acceptedValue = candidate; // Set the proposed candidate

        // Phase 1: PREPARE
        broadcastMessage("PREPARE:" + memberId + ":" + proposalNumber + ":" + candidate);
    }

    @Override
    public void receiveMessage(String message) {
        System.out.println(memberId + " received: " + message);
        if (!isRunning || shouldDropMessage()) return;

        try {
            Thread.sleep(simulateLatency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String[] parts = message.split(":");
        String type = parts[0];

        switch (type) {
            case "PREPARE":
                handlePrepare(parts);
                break;
            case "PROMISE":
                handlePromise(parts);
                break;
            case "ACCEPT_REQUEST":
                handleAcceptRequest(parts);
                break;
            case "ACCEPTED":
                handleAccepted(parts);
                break;
        }
    }

    private void runServer() {
        while (isRunning) {
            try {
                Socket client = serverSocket.accept();
                executor.execute(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                        String message = in.readLine();
                        if (message != null) {
                            receiveMessage(message);
                        }
                    } catch (IOException e) {
                        System.err.println(memberId + " error receiving message: " + e.getMessage());
                    } finally {
                        try {
                            client.close();
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                });
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println(memberId + " server error: " + e.getMessage());
                }
            }
        }
    }

    private void readConsoleInput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (isRunning) {
                String input = reader.readLine();
                if (input != null && !input.trim().isEmpty()) {
                    System.out.println(memberId + " proposing: " + input.trim());
                    propose(input.trim());
                }
            }
        } catch (IOException e) {
            System.err.println(memberId + " console input error: " + e.getMessage());
        }
    }

    private void handlePrepare(String[] parts) {
        String proposerId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];

        if (compareProposalNumbers(proposalNumber, highestProposalNumber) > 0) {
            highestProposalNumber = parseProposalNumber(proposalNumber);
            String response = "PROMISE:" + memberId + ":" + proposalNumber;
            if (acceptedValue != null) {
                response += ":" + acceptedProposalNumber + ":" + acceptedValue;
            }
            sendMessage(proposerId, response);
        }
    }

    private void handlePromise(String[] parts) {
        String responderId = parts[1];
        String proposalNumber = parts[2];

        synchronized (receivedPromises) {
            receivedPromises.add(responderId);
            if (receivedPromises.size() >= 2) { // Majority for 3 nodes
                // Use the candidate from the original PREPARE message
                if (acceptedValue != null) {
                    broadcastMessage("ACCEPT_REQUEST:" + memberId + ":" + proposalNumber + ":" + acceptedValue);
                } else {
                    System.err.println(memberId + " error: No candidate available for ACCEPT_REQUEST");
                }
            }
        }
    }

    private void handleAcceptRequest(String[] parts) {
        String proposerId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];

        if (compareProposalNumbers(proposalNumber, highestProposalNumber) >= 0) {
            highestProposalNumber = parseProposalNumber(proposalNumber);
            acceptedProposalNumber = parseProposalNumber(proposalNumber);
            acceptedValue = candidate;
            // Count own ACCEPTED vote
            processAcceptVote(memberId, candidate);
            // Broadcast ACCEPTED to all nodes
            broadcastMessage("ACCEPTED:" + memberId + ":" + proposalNumber + ":" + candidate);
        }
    }

    private void handleAccepted(String[] parts) {
        String responderId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];

        System.out.println(memberId + " handling ACCEPTED: " + String.join(":", parts));
        processAcceptVote(responderId, candidate);
    }

    private void processAcceptVote(String voterId, String candidate) {
        synchronized (receivedAccepts) {
            receivedAccepts.add(voterId);
            if (receivedAccepts.size() >= 2) { // Majority
                System.out.println("CONSENSUS: " + candidate + " has been elected Council President!");
                isRunning = false;
                executor.shutdown();
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    private void broadcastMessage(String message) {
        for (String targetId : networkConfig.keySet()) {
            if (!targetId.equals(memberId)) {
                sendMessage(targetId, message);
            }
        }
    }

    private void sendMessage(String targetId, String message) {
        if (shouldDropMessage()) {
            System.out.println(memberId + " dropped message to " + targetId + ": " + message);
            return;
        }
        System.out.println(memberId + " sending to " + targetId + ": " + message);

        String[] hostPort = networkConfig.get(targetId).split(":");
        try (Socket socket = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(message);
        } catch (IOException e) {
            System.err.println(memberId + " failed to send to " + targetId + ": " + e.getMessage());
        }
    }

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

    private boolean shouldDropMessage() {
        if (profile.equals("failure") && random.nextDouble() < 0.3) {
            if (random.nextDouble() < 0.1) {
                isRunning = false;
                executor.shutdown();
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
                System.out.println(memberId + " has crashed!");
                System.exit(1);
            }
            return true;
        }
        return false;
    }

    private int parseProposalNumber(String proposalNumber) {
        return Integer.parseInt(proposalNumber.split("\\.")[0]);
    }

    private int compareProposalNumbers(String newProposal, int currentMax) {
        if (currentMax == -1) return 1;
        int newNum = parseProposalNumber(newProposal);
        return Integer.compare(newNum, currentMax);
    }

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