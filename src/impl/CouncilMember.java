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
    private volatile boolean hasReachedConsensus = false;
    private final Random random = new Random();
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> receivedPromises = new ArrayList<>();
    private final List<String> receivedAccepts = new ArrayList<>();

    public CouncilMember(String memberId, String configFile) {
        this.memberId = memberId;
        this.profile = "standard";
        this.networkConfig = NetworkConfig.loadConfig(configFile);
        this.hasReachedConsensus = false;
    }

    @Override
    public void start() {
        String[] hostPort = networkConfig.get(memberId).split(":");
        int port = Integer.parseInt(hostPort[1]);

        // Try up to 3 times if port is busy
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                serverSocket = new ServerSocket(port);
                System.out.println(memberId + " started on port " + port);
                executor.execute(this::runServer);
                executor.execute(this::readConsoleInput);
                return; // success
            } catch (BindException e) {
                System.err.println(memberId + " port " + port + " busy (attempt " + attempt + "), retrying...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                System.err.println(memberId + " failed to start: " + e.getMessage());
                return;
            }
        }

        System.err.println(memberId + " failed to start after multiple attempts (port likely still bound).");
    }


    @Override
    public void setProfile(String profile) {
        this.profile = profile.toLowerCase();
    }

    @Override
    public void propose(String candidate) {
        if (!isRunning || hasReachedConsensus) return;
        String proposalNumber = proposalCounter.incrementAndGet() + "." + memberId;
        receivedPromises.clear();
        receivedAccepts.clear();
        acceptedValue = candidate;

        broadcastMessage("PREPARE:" + memberId + ":" + proposalNumber + ":" + candidate);
    }

    @Override
    public void receiveMessage(String message) {
        System.out.println(memberId + " received: " + message);
        if (!isRunning || hasReachedConsensus || shouldDropMessage()) {
            System.out.println(memberId + " dropped message: " + message);
            return;
        }

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
        while (isRunning && !hasReachedConsensus) {
            try {
                Socket client = serverSocket.accept();
                executor.execute(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
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
                            // Ignore
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

    private void readConsoleInput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (isRunning && !hasReachedConsensus) {
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
            if (hasReachedConsensus) return;
            receivedPromises.add(responderId);
            if (receivedPromises.size() >= 3) { // Majority for 5 nodes
                if (acceptedValue != null) {
                    broadcastMessage("ACCEPT_REQUEST:" + memberId + ":" + proposalNumber + ":" + acceptedValue);
                    receivedPromises.clear();
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
            processAcceptVote(memberId, candidate);
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
            if (hasReachedConsensus) return;
            receivedAccepts.add(voterId);
            if (receivedAccepts.size() >= 3) { // Majority for 5 nodes
                System.out.println("CONSENSUS: " + candidate + " has been elected Council President!");
                hasReachedConsensus = true;
                isRunning = false;
                receivedAccepts.clear();
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
        if (!isRunning || hasReachedConsensus || shouldDropMessage()) {
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
                hasReachedConsensus = true;
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