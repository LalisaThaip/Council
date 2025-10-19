package interfaces;

/**
 * PaxosNode defines the essential behaviors for a node participating
 * in the Paxos consensus algorithm. Each node can act as proposer,
 * acceptor, and learner depending on the implementation.
 */
public interface PaxosNode {

    /**
     * Starts the node, initializing any necessary state, network connections,
     * and background threads for listening or processing messages.
     */
    void start();

    /**
     * Proposes a candidate value for consensus. This method triggers
     * the Paxos proposal sequence, including sending PREPARE and
     * ACCEPT requests to other nodes.
     *
     * @param candidate the value that the node wants the cluster to agree on
     */
    void propose(String candidate);

    /**
     * Handles an incoming message from another Paxos node. Messages
     * potential inputs PREPARE, PROMISE, ACCEPT_REQUEST, ACCEPTED, etc.
     *
     * @param message the raw message received from the network
     */
    void receiveMessage(String message);

    /**
     * Sets the network profile for the node, determining how messages
     * are delivered or delayed. Profiles might include RELIABLE, LATENT,
     * FAILURE, or STANDARD.
     *
     * @param profile the name of the network profile to apply
     */
    void setProfile(String profile);
}