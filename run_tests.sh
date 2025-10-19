#!/bin/bash
# =========================================================
# Paxos Council Automated Test Harness
# Author: Lalisa
# Updated to handle all scenarios robustly
# =========================================================

# === Setup ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

CONFIG_FILE="network.config"
CLASS_DIR="classes"
SRC_DIR="src"
[ -d "src/main/java" ] && SRC_DIR="src/main/java"  # handle Maven-style layout

# Ensure network.config exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: $CONFIG_FILE not found!"
    exit 1
fi

# === Build Step ===
echo "Compiling project..."
rm -rf "$CLASS_DIR"
mkdir -p "$CLASS_DIR/main/java"

# Compile all .java files under $SRC_DIR, recursively
if ! find "$SRC_DIR" -name '*.java' -print0 | xargs -0 javac -d "$CLASS_DIR/main/java"; then
  echo "Compilation failed. Exiting."
  exit 1
fi
echo "Build successful."

# Resolve main class and classpath for CouncilMember (fixes ClassNotFound)
MAIN_CP="$CLASS_DIR/main/java"
MAIN_CLASS_FILE=$(find "$MAIN_CP" -type f -name 'CouncilMember.class' | head -n 1)
if [ -z "$MAIN_CLASS_FILE" ]; then
  echo "Error: CouncilMember.class not found under $MAIN_CP"
  exit 1
fi
MAIN_CLASS="${MAIN_CLASS_FILE#$MAIN_CP/}"
MAIN_CLASS="${MAIN_CLASS%.class}"
MAIN_CLASS="${MAIN_CLASS//\//.}"
echo "Detected main class: $MAIN_CLASS"

# === Helper Functions ===

# Clean up function
cleanup() {
    echo "Cleaning up processes and ports..."
    pkill -f 'java.*CouncilMember' 2>/dev/null || true
    sleep 3
    for port in {8001..8009} {9001..9009}; do
        if command -v lsof >/dev/null 2>&1; then
            if lsof -i :"$port" >/dev/null 2>&1; then
                lsof -ti :"$port" | xargs -r kill -9 2>/dev/null || true
            fi
        fi
    done
    rm -f M*.log M*.pid
}

start_members() {
    local count=$1
    local profile=$2
    echo "Starting $count members with profile $profile..."
    for i in $(seq 1 $count); do
        java -cp "$MAIN_CP" "$MAIN_CLASS" "M$i" --profile "$profile" > "M$i.log" 2>&1 &
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
        java -cp "$MAIN_CP" "$MAIN_CLASS" "M$i" --profile "${profiles[$((i-1))]}" > "M$i.log" 2>&1 &
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

check_majority_consensus() {
    local count=$1
    local expected_winner=$2
    local scenario=$3
    echo -e "\n=== $scenario Results ==="

    local reached=0

    for i in $(seq 1 $count); do
        if [ -f "M$i.log" ]; then
            if grep -q "CONSENSUS: $expected_winner has been elected Council President!" "M$i.log"; then
                echo "M$i: Consensus reached for $expected_winner"
                reached=$((reached+1))
            else
                echo "M$i: Consensus not found for $expected_winner"
            fi
        else
            echo "M$i: Log file missing"
        fi
    done

    local majority=$((count / 2 + 1))

    if [ $reached -ge $majority ]; then
        echo "$scenario: Successfully reached majority consensus on $expected_winner ($reached/$count nodes)"
    else
        echo "$scenario: Failed to reach majority consensus on $expected_winner ($reached/$count nodes)"
    fi
}

check_concurrent_majority() {
    local count=$1
    local scenario=$2
    echo -e "\n=== $scenario Results ==="

    # Collect all winners
    local winners=()
    for i in $(seq 1 $count); do
        if [ -f "M$i.log" ]; then
            winner_line=$(grep "CONSENSUS:.*has been elected Council President!" "M$i.log" | tail -1)
            if [ -n "$winner_line" ]; then
                winner=$(echo "$winner_line" | awk '{print $2}')
                winners+=("$winner")
            else
                winners+=("NONE")
            fi
        else
            winners+=("MISSING")
        fi
    done

    # Count occurrences manually (no associative array)
    local unique_winners=()
    local counts=()
    for w in "${winners[@]}"; do
        found=false
        for j in "${!unique_winners[@]}"; do
            if [ "${unique_winners[$j]}" = "$w" ]; then
                counts[$j]=$((counts[$j]+1))
                found=true
                break
            fi
        done
        if [ "$found" = false ]; then
            unique_winners+=("$w")
            counts+=("1")
        fi
    done

    local majority=$((count / 2 + 1))
    local majority_found=false
    for i in "${!unique_winners[@]}"; do
        if [ "${counts[$i]}" -ge $majority ] && [ "${unique_winners[$i]}" != "NONE" ] && [ "${unique_winners[$i]}" != "MISSING" ]; then
            echo "$scenario: Successfully reached majority consensus on ${unique_winners[$i]} (${counts[$i]}/$count nodes)"
            majority_found=true
        fi
    done

    if [ "$majority_found" = false ]; then
        echo "$scenario: Failed to reach majority consensus"
    fi

    # Print per-node status
    for i in $(seq 1 $count); do
        echo "M$i: ${winners[$((i-1))]}"
    done
}

# === SCENARIO 1: Ideal Network ===
echo -e "\n=== Scenario 1: Ideal Network ==="
cleanup
start_members 9 reliable
echo "Triggering proposal from M4 for M5"
echo "M5" | nc localhost 9004
sleep 10
check_majority_consensus 9 M5 "Scenario 1"
collate_logs 9
stop_members
sleep 2

# === SCENARIO 2: Concurrent Proposals ===
echo -e "\n=== Scenario 2: Concurrent Proposals ==="
cleanup
start_members 9 reliable
echo "Triggering concurrent proposals: M1 proposes M1, M8 proposes M8"
echo "M1" | nc localhost 9001 &
echo "M8" | nc localhost 9008 &
sleep 15
check_concurrent_majority 9 "Scenario 2"
collate_logs 9
stop_members
sleep 2

# === SCENARIO 3: Fault Tolerance ===
echo -e "\n=== Scenario 3: Fault Tolerance ==="
profiles=("reliable" "latent" "failure" "standard" "standard" "standard" "standard" "standard" "standard")

# --- Test 3a: Standard member proposes ---
echo -e "\n--- Test 3a: Standard member (M4) proposes ---"
cleanup
start_members_with_profiles 9 "${profiles[@]}"
echo "Triggering proposal from M4 for M4"
echo "M4" | nc localhost 9004
sleep 15
check_majority_consensus 9 M4 "Scenario 3a"
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
check_majority_consensus 9 M2 "Scenario 3b"
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

echo "Killing M3 to simulate crash"
if [ -f M3.pid ]; then
    kill -9 "$(cat M3.pid)" 2>/dev/null
    sleep 5
fi

echo "Triggering new proposal from M4 for M4"
echo "M4" | nc localhost 9004
sleep 15
check_majority_consensus 9 M4 "Scenario 3c"
collate_logs 9
stop_members

echo -e "\n=== All Scenarios Completed ==="