#!/bin/bash

# Compile Java files
mkdir -p classes
javac -d classes src/interfaces/PaxosNode.java src/impl/CouncilMember.java src/impl/NetworkConfig.java src/impl/NetworkProfile.java
if [ $? -ne 0 ]; then
    echo "Compilation failed"
    exit 1
fi

# Function to clean up processes
cleanup() {
    pkill -f 'java CouncilMember' 2>/dev/null
    rm -rf logs
}

# Create logs directory
mkdir -p logs

# Scenario 1: Ideal Network
echo "Running Scenario 1: Ideal Network"
cleanup
for i in {1..9}; do
    java -cp classes impl.CouncilMember M$i --profile reliable > logs/M$i.log 2>&1 &
    sleep 0.5
done
sleep 2
echo "M5" | java -cp classes impl.CouncilMember M4 --profile reliable > logs/M4_propose.log 2>&1 &
sleep 5
if grep -q "CONSENSUS: M5 has been elected Council President!" logs/*.log; then
    echo "Scenario 1 Passed"
else
    echo "Scenario 1 Failed"
fi
cleanup

# Scenario 2: Concurrent Proposals
echo "Running Scenario 2: Concurrent Proposals"
for i in {1..9}; do
    java -cp classes impl.CouncilMember M$i --profile reliable > logs/M$i.log 2>&1 &
    sleep 0.5
done
sleep 2
echo "M1" | java -cp classes impl.CouncilMember M1 --profile reliable > logs/M1_propose.log 2>&1 &
echo "M8" | java -cp classes impl.CouncilMember M8 --profile reliable > logs/M8_propose.log 2>&1 &
sleep 5
if grep -q "CONSENSUS:.*has been elected Council President!" logs/*.log; then
    echo "Scenario 2 Passed"
else
    echo "Scenario 2 Failed"
fi
cleanup

# Scenario 3: Fault Tolerance
echo "Running Scenario 3a: Standard Proposer"
for i in {1..9}; do
    java -cp classes impl.CouncilMember M$i --profile standard > logs/M$i.log 2>&1 &
    sleep 0.5
done
sleep 2
echo "M5" | java -cp classes impl.CouncilMember M4 --profile standard > logs/M4_propose.log 2>&1 &
sleep 8
if grep -q "CONSENSUS: M5 has been elected Council President!" logs/*.log; then
    echo "Scenario 3a Passed"
else
    echo "Scenario 3a Failed"
fi
cleanup

echo "Running Scenario 3b: Latent Proposer"
for i in {1..9}; do
    java -cp classes impl.CouncilMember M$i --profile standard > logs/M$i.log 2>&1 &
    sleep 0.5
done
sleep 2
echo "M5" | java -cp classes impl.CouncilMember M2 --profile latent > logs/M2_propose.log 2>&1 &
sleep 10
if grep -q "CONSENSUS: M5 has been elected Council President!" logs/*.log; then
    echo "Scenario 3b Passed"
else
    echo "Scenario 3b Failed"
fi
cleanup

echo "Running Scenario 3c: Failing Proposer"
for i in {1..2}; do
    java -cp classes impl.CouncilMember M$i --profile standard > logs/M$i.log 2>&1 &
    sleep 0.5
done
for i in {4..9}; do
    java -cp classes impl.CouncilMember M$i --profile standard > logs/M$i.log 2>&1 &
    sleep 0.5
done
java -cp classes impl.CouncilMember M3 --profile failure > logs/M3.log 2>&1 &
sleep 2
echo "M5" | java -cp classes impl.CouncilMember M3 --profile failure > logs/M3_propose.log 2>&1 &
sleep 2
echo "M5" | java -cp classes impl.CouncilMember M4 --profile standard > logs/M4_propose.log 2>&1 &
sleep 8
if grep -q "CONSENSUS: M5 has been elected Council President!" logs/*.log; then
    echo "Scenario 3c Passed"
else
    echo "Scenario 3c Failed"
fi
cleanup

echo "Testing complete"