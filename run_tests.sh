#!/bin/bash
# =========================================================
# Paxos Council Automated Test Harness
# Author: Lalisa
# =========================================================

# === Setup ===
cd /Users/lalisa/IdeaProjects/Council || exit 1

CONFIG_FILE="network.config"
CLASS_DIR="classes"
SRC_DIR="src"

# Ensure network.config exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: $CONFIG_FILE not found!"
    exit 1
fi

# Clean up any existing Paxos processes
echo "Cleaning up any existing CouncilMember processes..."
pkill -f 'java.*CouncilMember' 2>/dev/null || true
sleep 1

# === Build Step ===
echo "Compiling project..."
rm -rf "$CLASS_DIR"
mkdir -p "$CLASS_DIR"
javac -d "$CLASS_DIR" "$SRC_DIR"/interfaces/PaxosNode.java "$SRC_DIR"/impl/*.java

# Check for compilation errors
if [ $? -ne 0 ]; then
    echo "Compilation failed. Exiting."
    exit 1
fi
echo "Build successful."

# === Helper Functions ===

start_members() {
    echo "Starting $1 members..."
    for i in $(seq 1 $1); do
        java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "$2" > "M$i.log" 2>&1 &
        sleep 0.5
    done
    sleep 2
}

stop_members() {
    echo "Stopping all members..."
    pkill -f 'java.*CouncilMember' 2>/dev/null || true
    sleep 1
}

collate_logs() {
    echo -e "\n=== Combined Log Output ==="
    for i in $(seq 1 $1); do
        echo -e "\n--- M$i LOG ---\n"
        cat "M$i.log"
    done
}

# === SCENARIO 1: Ideal Network ===
echo -e "\n=== Scenario 1: Ideal Network ==="
stop_members
start_members 9 reliable
sleep 2

# Trigger proposal (via input socket, e.g. 9004)
echo "M5" | nc localhost 9004
sleep 8

collate_logs 9
stop_members
echo "=== Scenario 1 Completed ==="
sleep 2

# === SCENARIO 2: Concurrent Proposals ===
echo -e "\n=== Scenario 2: Concurrent Proposals ==="
stop_members
start_members 9 reliable
sleep 2

# Trigger two proposals near-simultaneously
(echo "M1" | nc localhost 9001) &
sleep 0.5
(echo "M8" | nc localhost 9008) &
sleep 10

collate_logs 9
stop_members
echo "=== Scenario 2 Completed ==="
sleep 2

# === SCENARIO 3: Fault Tolerance ===
echo -e "\n=== Scenario 3: Fault Tolerance ==="
stop_members

# Profiles: M1=reliable, M2=latent, M3=failure, M4-M9=standard
profiles=("reliable" "latent" "failure" "standard" "standard" "standard" "standard" "standard" "standard")
for i in $(seq 1 9); do
    java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "${profiles[$((i-1))]}" > "M$i.log" 2>&1 &
    sleep 0.5
done
sleep 2

# --- Test 3a: Standard member proposes ---
echo -e "\n--- Test 3a: Standard member (M4) proposes ---"
echo "M4" | nc localhost 9004
sleep 10
collate_logs 9
stop_members
sleep 2

# --- Test 3b: Latent member proposes ---
echo -e "\n--- Test 3b: Latent member (M2) proposes ---"
for i in $(seq 1 9); do
    java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "${profiles[$((i-1))]}" > "M$i.log" 2>&1 &
    sleep 0.5
done
sleep 2
echo "M2" | nc localhost 9002
sleep 12
collate_logs 9
stop_members
sleep 2

# --- Test 3c: Failing member proposes ---
echo -e "\n--- Test 3c: Failing member (M3) proposes then crashes ---"
for i in $(seq 1 9); do
    java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "${profiles[$((i-1))]}" > "M$i.log" 2>&1 &
    sleep 0.5
done
sleep 2
echo "M3" | nc localhost 9003
sleep 5
# M3 should crash automatically per failure profile
sleep 8
collate_logs 9
stop_members
echo "=== Scenario 3 Completed ==="

echo -e "\n=== All Scenarios Completed Successfully ==="
