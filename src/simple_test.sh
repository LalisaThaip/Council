#!/bin/bash

# Script to start all 9 council members for manual testing
# Each member runs in the background and logs to a file

LOG_DIR="manual_logs"
mkdir -p $LOG_DIR

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Clean up any existing processes
echo -e "${YELLOW}Cleaning up existing processes...${NC}"
pkill -f "java CouncilMember" 2>/dev/null
sleep 2

# Parse command line arguments for profile
PROFILE=${1:-reliable}

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Starting 9 Council Members${NC}"
echo -e "${BLUE}Profile: $PROFILE${NC}"
echo -e "${BLUE}========================================${NC}\n"

# Start all 9 members
for i in {1..9}; do
    echo -e "${GREEN}Starting M$i with $PROFILE profile...${NC}"
    java CouncilMember M$i --profile $PROFILE > "$LOG_DIR/M$i.log" 2>&1 &
    sleep 0.5
done

echo -e "\n${GREEN}All members started!${NC}"
echo -e "${YELLOW}Logs are in: $LOG_DIR/${NC}\n"

echo "To propose a candidate:"
echo "  1. Find the process you want to propose from"
echo "  2. Send input via: echo 'M5' | java CouncilMember M1 --profile $PROFILE"
echo ""
echo "Or attach to a process and type the candidate name"
echo ""
echo -e "${YELLOW}To stop all members: pkill -f 'java CouncilMember'${NC}"
echo ""

# Keep script running to show live logs
echo "Press Ctrl+C to stop monitoring logs..."
echo ""

# Tail logs from all members
tail -f $LOG_DIR/M*.log