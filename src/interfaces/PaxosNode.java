package interfaces;

public interface PaxosNode {
    void start();
    void propose(String candidate);
    void receiveMessage(String message);
    void setProfile(String profile);
}