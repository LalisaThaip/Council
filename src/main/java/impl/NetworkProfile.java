package main.java.impl;

/**
 * NetworkProfile defines different types of network behavior
 * that can be simulated in the system. This is typically used
 * to configure the NetworkSimulator or test how Paxos nodes
 * behave under different conditions.
 */
public enum NetworkProfile {
    /**
     * RELIABLE: All messages are delivered instantly and without loss.
     */
    RELIABLE,

    /**
     * LATENT: Messages experience delays (latency) before delivery.
     * This simulates slower networks.
     */
    LATENT,

    /**
     * FAILURE: Some messages may be lost or dropped, simulating
     * unreliable network conditions or failures.
     */
    FAILURE,

    /**
     * STANDARD: Default network behavior. Could be a mix of
     * normal latency and occasional minor delays, representing
     * typical conditions.
     */
    STANDARD
}
