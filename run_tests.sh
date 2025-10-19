#!/bin/bash
# =========================================================
# Paxos Council Automated Test Harness
# Author: Lalisa
# Updated to handle all scenarios robustly
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

# Clean up any existing Paxos processes and ports
cleanup() {
    echo "Cleaning up processes and ports..."
    pkill -f 'java.*CouncilMember' 2>/dev/null || true
    sleep 5
    for port in {8001..8009} {9001..9009}; do
        if lsof -i :$port > /dev/null; then
            echo "Port $port is still in use, attempting to free it..."
            lsof -i :$port | grep LISTEN | awk '{print $2}' | xargs -I {} kill -9 {} || true
            sleep 1
        fi
    done
    rm -f M*.log M*.pid
}

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
    local count=$1
    local profile=$2
    echo "Starting $count members with profile $profile..."
    for i in $(seq 1 $count); do
        java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "$profile" > "M$i.log" 2>&1 &
        echo $! > "M$i.pid"
        sleep 1
    done
    sleep 5
}

start_members_with_profiles() {
    local count=$1
    shift
    local profiles=("$@")
    echo "Starting $count members with specified profiles..."
    for i in $(seq 1 $count); do
        java -cp "$CLASS_DIR" impl.CouncilMember "M$i" --profile "${profiles[$((i-1))]}" > "M$i.log" 2>&1 &
        echo $! > "M$i.pid"
        sleep 1
    done
    sleep 5
}

stop_members() {
    echo "Stopping all members..."
    pkill -f 'java.*CouncilMember' 2>/dev/null || true
    sleep 5
    rm -f M*.pid
}

collate_logs() {
    local count=$1
    echo -e "\n=== Combined Log Output ==="
    for i in $(seq 1 $count); do
        if [ -f "M$i.log" ]; then
            echo -e "\n--- M$i LOG ---\n"
            cat "M$i.log"
        else
            echo -e "\n--- M$i LOG ---\nLog file missing"
        fi
    done
}

check_consensus() {
    local count=$1
    local expected_winner=$2
    local scenario=$3
    echo -e "\n=== $scenario Results ==="
    local consensus_found=true
    for i in $(seq 1 $count); do
        if [ -f "M$i.log" ]; then
            if grep -q "CONSENSUS: $expected_winner has been elected Council President!" "M$i.log"; then
                echo "M$i: Consensus reached for $expected_winner"
            else
                echo "M$i: Consensus not found for $expected_winner"
                consensus_found=false
            fi
        else
            echo "M$i: Log file missing"
            consensus_found=false
        fi
    done
    if [ "$consensus_found" = true ]; then
        echo "$scenario: Successfully reached consensus on $expected_winner"
    else
        echo "$scenario: Failed to reach consensus on $expected_winner"
    fi
}

check_concurrent_consensus() {
    local count=$1
    local scenario=$2
    echo -e "\n=== $scenario Results ==="
    local consensus_found=false
    local winner=""
    for i in $(seq 1 $count); do
        if [ -f "M$i.log" ]; then
            if grep -q "CONSENSUS:.*has been elected Council President!" "M$i.log"; then
                consensus_line=$(grep "CONSENSUS:.*has been elected Council President!" "M$i.log")
                current_winner=$(echo "$consensus_line" | awk '{print $2}')
                if [ -z "$winner" ]; then
                    winner="$current_winner"
                fi
                if [ "$current_winner" = "$winner" ]; then
                    echo "M$i: Consensus reached for $winner"
                    consensus_found=true
                else
                    echo "M$i: Inconsistent consensus ($current_winner != $winner)"
                    consensus_found=false
                fi
            else
                echo "M$i: Consensus not found"
                consensus_found=false
            fi
        else
            echo "M$i: Log file missing"
            consensus_found=false
        fi
    done
    if [ "$consensus_found" = true ]; then
        echo "$scenario: Successfully reached consensus on $winner"
    else
        echo "$scenario: Failed to reach consistent consensus"
    fi
}

# === SCENARIO 1: Ideal Network ===
echo -e "\n=== Scenario 1: Ideal Network ==="
cleanup
start_members 9 reliable

# Trigger proposal
echo "Triggering proposal from M4 for M5"
echo "M5" | nc localhost 9004
sleep 10

check_consensus 9 M5 "Scenario 1"
collate_logs 9
stop_members
echo "=== Scenario 1 Completed ==="
sleep 2

# === SCENARIO 2: Concurrent Proposals ===
echo -e "\n=== Scenario 2: Concurrent Proposals ==="
cleanup
start_members 9 reliable

# Trigger concurrent proposals
echo "Triggering concurrent proposals: M1 proposes M1, M8 proposes M8"
echo "M1" | nc localhost 9001 &
echo "M8" | nc localhost 9008 &
sleep 15

check_concurrent_consensus 9 "Scenario 2"
collate_logs 9
stop_members
echo "=== Scenario 2 Completed ==="
sleep 2

# === SCENARIO 3: Fault Tolerance ===
echo -e "\n=== Scenario 3: Fault Tolerance ==="

# Profiles: M1=reliable, M2=latent, M3=failure, M4-M9=standard
profiles=("reliable" "latent" "failure" "standard" "standard" "standard" "standard" "standard" "standard")

# --- Test 3a: Standard member proposes ---
echo -e "\n--- Test 3a: Standard member (M4) proposes ---"
cleanup
start_members_with_profiles 9 "${profiles[@]}"

echo "Triggering proposal from M4 for M4"
echo "M4" | nc localhost 9004
sleep 15

check_consensus 9 M4 "Scenario 3a"
collate_logs 9
stop_members
sleep 2

# --- Test 3b: Latent member proposes ---
echo -e "\n--- Test 3b: Latent member (M2) proposes ---"
cleanup
start_members_with_profiles 9 "${profiles[@]}"

echo "Triggering proposal from M2 for M2"
echo "M2" | nc localhost 9002
sleep 20

check_consensus 9 M2 "Scenario 3b"
collate_logs 9
stop_members
sleep 2

# --- Test 3c: Failing member proposes then crashes ---
echo -e "\n--- Test 3c: Failing member (M3) proposes then crashes ---"
cleanup
start_members_with_profiles 9 "${profiles[@]}"

echo "Triggering proposal from M3 for M3"
echo "M3" | nc localhost 9003
sleep 5

# Explicitly kill M3 to simulate crash
echo "Killing M3 to simulate crash"
if [ -f M3.pid ]; then
    kill -9 $(cat M3.pid) 2>/dev/null
    sleep 5
fi

echo "Triggering new proposal from M4 for M4"
echo "M4" | nc localhost 9004
sleep 15

check_consensus 9 M4 "Scenario 3c"
collate_logs 9
stop_members
echo "=== Scenario 3 Completed ==="

echo -e "\n=== All Scenarios Completed ==="