# =========================================================
# Makefile for CouncilMember Maven project
# =========================================================

# Maven command
MVN = mvn

# Java test class
TEST_CLASS = test.TestCouncilMember

# =========================================================
# Targets
# =========================================================

# Compile project
compile:
	@echo "Compiling Maven project..."
	$(MVN) compile

# Run JUnit tests
test:
	@echo "Running JUnit tests for $(TEST_CLASS)..."
	$(MVN) test -Dtest=$(TEST_CLASS)

# Clean project
clean:
	@echo "Cleaning Maven project..."
	$(MVN) clean

# Package project (optional)
package:
	@echo "Packaging Maven project..."
	$(MVN) package

# Run the TestCouncilMember.java standalone (optional)
run-test:
	@echo "Running TestCouncilMember..."
	$(MVN) exec:java -Dexec.mainClass="$(TEST_CLASS)"

# Default target
all: compile test
