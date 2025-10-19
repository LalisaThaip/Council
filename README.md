# Adelaide Suburbs Council Election – Paxos Consensus Implementation
### Author Lalisa Thaiprasert

## Overview

This project implements the Paxos consensus algorithm to simulate the election of a council president among nine council members (M1–M9) of the Adelaide Suburbs Council.

Each member acts as a Proposer, Acceptor, and Learner, communicating over TCP sockets.
The system ensures that only one member is elected president, even under unreliable network conditions, latency, or member failure.

## Project Structure 
```
Council/
├── network.config
├── run_tests.sh
└── src/
    └── main/
        └── java/
            ├── impl/
            │   ├── CouncilMember.java
            │   ├── Message.java
            │   ├── NetworkSimulator.java
            │   ├── PaxosNodeImpl.java
            │   └── ...
            └── interfaces/
                └── PaxosNode.java

```
`network.config` – Maps each member ID (M1–M9) to host and port.

`run_tests.sh` – Automated test harness to compile, start members, run Paxos scenarios, and collate logs.

`src/main/java/impl` – Contains implementation of the Paxos algorithm and council member logic.

`src/main/java/interfaces` – Contains interface definitions such as PaxosNode.


## Prerequisites 
Before running, ensure the following:

- Java 17+ installed and on PATH:
```
java -version
```
- Netcat (nc) is available for sending proposals (default macOS/Linux tool).

## How to Compile
Navigate to the root directory (Council) and type in a terminal
```
bash run_tests.sh
```
The script automatically:

1. Compiles all .java files under src/main/java/
2. Cleans up any old Java processes or log files
3. Launches 9 members in separate background processes
4. Runs the Paxos scenarios described below
5. Collates and displays log output at the end

## Configuration File
`network.config` defines hostnames and ports for each council member.

Example:
```
M1,localhost,9001
M2,localhost,9002
M3,localhost,9003
M4,localhost,9004
M5,localhost,9005
M6,localhost,9006
M7,localhost,9007
M8,localhost,9008
M9,localhost,9009
```
Each CouncilMember reads this file on startup to know where to connect.

### ⚠️ Port Availability Note ###

The network.config file defines the TCP ports each council member uses (default: 9001–9009).
The run_tests.sh script automatically attempts to free these ports before each run.
However, if any of these ports are already in use by another program, Paxos members may fail to start.
If that happens, either:

- Close the conflicting applications, or
- Edit network.config to use a different range of available ports.

## Test Scenarios (automated)
Your run_tests.sh script automatically runs the following scenarios:

### Scenario 1: Ideal Network
- All 9 members run with the reliable profile.
- M4 proposes M5 as president.

Expected Output:
```
CONSENSUS: M5 has been elected Council President!
```

#### Scenario 2: Concurrent Proposals
- All 9 members run with the reliable profile.
- M1 proposes M1 and M8 proposes M8 at the same time.

Expected Output:
```
Paxos correctly resolves the conflict and all nodes agree on a single winner.
```

#### Scenario 3: Fault Tolerance
- Members have mixed profiles:
```
Member	    Profile     	   Description
 M1 	    reliable    	  Fast responses
 M2	        latent	       High delay responses
 M3	        failure	        Crashes mid-test
M4–M9	    standard	     Moderate delays
```

#### 3a: Standard Member Proposes
- M4 proposes M4.
 - consensus reached successfully.

#### 3b: Latent Member Proposes
- M2 proposes M2 (high latency).
- Consensus still achieved.

#### 3c: Failing Member Proposes
- M3 proposes M3, then crashes.
- Another proposer (M4) reinitiates and consensus is achieved.
-  Consensus restored after failure.

## Log Files
Each member writes its output to a separate file:
```
M1.log
M2.log
...
M9.log
```
These logs show detailed Paxos message exchanges:
```
M2 received: PREPARE:M8:1.M8:M8M5 sending ACCEPTED to M3
M2 sending to M8: PROMISE:M2:1.M8
M2 broadcasting: ACCEPTED:M2:1.M8:M8
M2 received: ACCEPTED:M9:1.M8:M8
M2 handling ACCEPTED: ACCEPTED:M9:1.M8:M8
CONSENSUS: M8 has been elected Council President!
```
At the end of each scenario, the script collates and prints them in order.

## Cleanup
If needed, you can manually clean up all processes and logs with:
```
pkill -f 'java.*CouncilMember'
rm -f M*.log M*.pid
```

### Expected Output Example
```
=== Scenario 1 Results ===
M1: Consensus reached for M5
M2: Consensus reached for M5
M3: Consensus reached for M5
M4: Consensus reached for M5
M5: Consensus reached for M5
M6: Consensus reached for M5
M7: Consensus reached for M5
M8: Consensus reached for M5
M9: Consensus reached for M5
Scenario 1: Successfully reached majority consensus on M5 (9/9 nodes)
```