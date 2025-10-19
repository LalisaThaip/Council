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
    private String highestProposalId = "";
    private String acceptedValue = null;
    private int acceptedProposalNumber = -1;
    private String acceptedProposalId = "";
    private volatile boolean isRunning = true;
    private volatile boolean hasReachedConsensus = false;
    private final Random random = new Random();
    private ServerSocket serverSocket;
    private ServerSocket inputSocket;
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
        String[] hostPort = networkConfig.get(memberId).split(":");
        int port = Integer.parseInt(hostPort[1]);
        int inputPort = port + 1000; // Use a different port for proposal input (e.g., 9001 for M1)

        // Start main server socket
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                serverSocket = new ServerSocket(port);
                System.out.println(memberId + " started on port " + port);
                break;
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

        // Start input socket
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                inputSocket = new ServerSocket(inputPort);
                System.out.println(memberId + " started input socket on port " + inputPort);
                break;
            } catch (BindException e) {
                System.err.println(memberId + " input port " + inputPort + " busy (attempt " + attempt + "), retrying...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                System.err.println(memberId + " failed to start input socket: " + e.getMessage());
                return;
            }
        }

        executor.execute(this::runServer);
        executor.execute(this::readConsoleInput);
    }

    @Override
    public void setProfile(String profile) {
        this.profile = profile.toLowerCase();
    }

    @Override
    public void propose(String candidate) {
        if (!isRunning || hasReachedConsensus) {
            System.out.println(memberId + " cannot propose: isRunning=" + isRunning + ", hasReachedConsensus=" + hasReachedConsensus);
            return;
        }
        String proposalNumber = proposalCounter.incrementAndGet() + "." + memberId;
        System.out.println(memberId + " proposing candidate " + candidate + " with proposal " + proposalNumber);
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
        if (parts.length < 3) {
            System.err.println(memberId + " invalid message format: " + message);
            return;
        }
        String type = parts[0];

        switch (type) {
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
                System.err.println(memberId + " unknown message type: " + type);
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
        while (isRunning && !hasReachedConsensus) {
            try (Socket client = inputSocket.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
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

    private void handlePrepare(String[] parts) {
        String proposerId = parts[1];
        String proposalNumber = parts[2];
        String candidate = parts[3];

        if (compareProposalNumbers(proposalNumber, highestProposalNumber, highestProposalId) > 0) {
            String[] numParts = proposalNumber.split("\\.");
            highestProposalNumber = Integer.parseInt(numParts[0]);
            highestProposalId = numParts[1];
            String response = "PROMISE:" + memberId + ":" + proposalNumber;
            if (acceptedValue != null) {
                response += ":" + acceptedProposalNumber + "." + acceptedProposalId + ":" + acceptedValue;
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
            if (receivedPromises.size() >= 5) { // Majority for 9 nodes
                if (acceptedValue != null) {
                    System.out.println(memberId + " received majority promises, sending ACCEPT_REQUEST for " + acceptedValue);
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

        if (compareProposalNumbers(proposalNumber, highestProposalNumber, highestProposalId) >= 0) {
            String[] numParts = proposalNumber.split("\\.");
            highestProposalNumber = Integer.parseInt(numParts[0]);
            highestProposalId = numParts[1];
            acceptedProposalNumber = highestProposalNumber;
            acceptedProposalId = highestProposalId;
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
            if (receivedAccepts.size() >= 5) { // Majority for 9 nodes
                System.out.println("CONSENSUS: " + candidate + " has been elected Council President!");
                hasReachedConsensus = true;
                isRunning = false;
                receivedAccepts.clear();
                executor.shutdown();
                try {
                    serverSocket.close();
                    inputSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    private void broadcastMessage(String message) {
        System.out.println(memberId + " broadcasting: " + message);
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
                    inputSocket.close();
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

    private int compareProposalNumbers(String newProposal, int currentMax, String currentId) {
        if (currentMax == -1) return 1;
        String[] newParts = newProposal.split("\\.");
        int newNum = Integer.parseInt(newParts[0]);
        if (newNum != currentMax) {
            return Integer.compare(newNum, currentMax);
        }
        return newParts[1].compareTo(currentId);
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