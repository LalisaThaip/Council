#!/bin/bash
cd /Users/lalisa/IdeaProjects/Council

# Forcefully kill all Java processes related to CouncilMember
pkill -9 -f 'java.*CouncilMember' || true
sleep 5  # Wait longer for processes to terminate and release ports

# Check and free ports 8001–8005
for port in 8001 8002 8003 8004 8005; do
    if lsof -i :$port > /dev/null; then
        echo "Port $port is still in use, attempting to free it..."
        lsof -i :$port | grep LISTEN | awk '{print $2}' | xargs -I {} kill -9 {} || true
        sleep 2
    fi
done

# Clean up logs and classes
rm -rf M1.log M2.log M3.log M4.log M5.log
rm -rf classes/*
mkdir -p classes

# Compile
javac -d classes src/interfaces/PaxosNode.java src/impl/CouncilMember.java src/impl/NetworkConfig.java src/impl/NetworkProfile.java
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Start nodes with increased sleep time
java -cp classes impl.CouncilMember M1 --profile reliable > M1.log 2>&1 &
sleep 5
java -cp classes impl.CouncilMember M2 --profile reliable > M2.log 2>&1 &
sleep 5
java -cp classes impl.CouncilMember M3 --profile reliable > M3.log 2>&1 &
sleep 5
java -cp classes impl.CouncilMember M4 --profile reliable > M4.log 2>&1 &
sleep 5
java -cp classes impl.CouncilMember M5 --profile reliable > M5.log 2>&1 &
sleep 5

# Propose M4 from M2
echo "M4" | java -cp classes impl.CouncilMember M2 --profile reliable >> M2.log 2>&1
sleep 6  # Allow ample time for consensus

# Display logs
cat M1.log M2.log M3.log M4.log M5.log

# Clean up
pkill -9 -f 'java.*CouncilMember' || true