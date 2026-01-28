#!/bin/bash
#
# Decommission Vault Script
#
# Completely removes a user's vault from both backend and enclave.
# Use this for testing re-enrollment or cleaning up test users.
#
# Usage:
#   ./scripts/decommission-vault.sh <user_guid>
#   ./scripts/decommission-vault.sh <user_guid> --clear-app
#   ./scripts/decommission-vault.sh --from-device [--clear-app]
#
# Options:
#   --clear-app     Also clear app data on connected Android device
#   --from-device   Extract user_guid from connected device's logs
#
# Prerequisites:
#   - AWS CLI configured with appropriate credentials
#   - Access to invoke Lambda functions
#   - Access to SSM (for enclave cleanup)
#   - adb (for --clear-app or --from-device)
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Lambda function name (find dynamically or use known name)
LAMBDA_NAME="VettID-ExtensibilityMonit-DecommissionVaultFn6B844-CyLoyW0vKHzI"

# NATS instance (first one in cluster)
NATS_INSTANCE="i-05a413c217065a2f1"

print_usage() {
    echo "Usage: $0 <user_guid> [--clear-app]"
    echo "       $0 --from-device [--clear-app]"
    echo ""
    echo "Options:"
    echo "  --clear-app     Also clear app data on connected Android device"
    echo "  --from-device   Extract user_guid from connected device's logs"
    echo ""
    echo "Examples:"
    echo "  $0 af44310d-2051-46a1-afd8-ee275b53f804"
    echo "  $0 af44310d-2051-46a1-afd8-ee275b53f804 --clear-app"
    echo "  $0 --from-device --clear-app"
}

extract_guid_from_device() {
    echo -e "${YELLOW}Extracting user_guid from device logs...${NC}"

    # Try to get user_guid from recent logs
    GUID=$(adb logcat -d | grep -o 'user_guid":"[a-f0-9-]\{36\}' | tail -1 | cut -d'"' -f3)

    if [ -z "$GUID" ]; then
        # Try owner space pattern
        GUID=$(adb logcat -d | grep -o 'OwnerSpace\.[a-f0-9-]\{36\}' | tail -1 | cut -d'.' -f2)
    fi

    if [ -z "$GUID" ]; then
        echo -e "${RED}Could not find user_guid in device logs${NC}"
        echo "Make sure the app has been used recently (enrollment attempted)"
        exit 1
    fi

    echo -e "${GREEN}Found user_guid: $GUID${NC}"
    echo "$GUID"
}

decommission_backend() {
    local USER_GUID=$1

    echo -e "${YELLOW}Step 1: Decommissioning backend (DynamoDB, S3)...${NC}"

    RESULT=$(aws lambda invoke \
        --function-name "$LAMBDA_NAME" \
        --cli-binary-format raw-in-base64-out \
        --payload "{\"pathParameters\":{\"user_guid\":\"$USER_GUID\"},\"headers\":{\"origin\":\"https://vettid.dev\"},\"requestContext\":{\"authorizer\":{\"jwt\":{\"claims\":{\"cognito:groups\":\"admin\",\"email\":\"script@vettid.dev\"}}}}}" \
        /tmp/decommission-result.json 2>&1)

    if [ $? -ne 0 ]; then
        echo -e "${RED}Failed to invoke Lambda: $RESULT${NC}"
        return 1
    fi

    BODY=$(cat /tmp/decommission-result.json | jq -r '.body' 2>/dev/null)
    if [ -z "$BODY" ] || [ "$BODY" == "null" ]; then
        BODY=$(cat /tmp/decommission-result.json)
    fi

    echo "$BODY" | jq . 2>/dev/null || echo "$BODY"

    # Check if successful
    if echo "$BODY" | grep -q '"statusCode":200\|"message":"Vault'; then
        echo -e "${GREEN}Backend cleanup complete${NC}"
        return 0
    else
        echo -e "${YELLOW}Backend cleanup may have issues - check output above${NC}"
        return 0  # Continue anyway
    fi
}

decommission_enclave() {
    local USER_GUID=$1

    echo -e "${YELLOW}Step 2: Clearing enclave credential by restarting enclave...${NC}"

    # Find the enclave instance
    ENCLAVE_INSTANCE=$(aws ec2 describe-instances \
        --filters "Name=tag:Name,Values=*Nitro*Enclave*" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null)

    if [ -z "$ENCLAVE_INSTANCE" ] || [ "$ENCLAVE_INSTANCE" == "None" ]; then
        echo -e "${YELLOW}Could not find enclave instance - trying alternative tag...${NC}"
        ENCLAVE_INSTANCE=$(aws ec2 describe-instances \
            --filters "Name=tag:Name,Values=*vettid*enclave*" "Name=instance-state-name,Values=running" \
            --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null)
    fi

    if [ -z "$ENCLAVE_INSTANCE" ] || [ "$ENCLAVE_INSTANCE" == "None" ]; then
        echo -e "${RED}Could not find enclave instance${NC}"
        return 1
    fi

    echo "Enclave instance: $ENCLAVE_INSTANCE"

    # Create restart script that:
    # 1. Terminates current enclave
    # 2. Finds and starts enclave with EIF
    # 3. Restarts parent process
    local RESTART_SCRIPT='#!/bin/bash
set -e

echo "=== Restarting enclave to clear credential ==="

# Get current enclave info
ENCLAVE_INFO=$(nitro-cli describe-enclaves)
ENCLAVE_ID=$(echo "$ENCLAVE_INFO" | jq -r ".[0].EnclaveID")
ENCLAVE_CID=$(echo "$ENCLAVE_INFO" | jq -r ".[0].EnclaveCID // 16")
CPU_COUNT=$(echo "$ENCLAVE_INFO" | jq -r ".[0].NumberOfCPUs // 2")
MEMORY=$(echo "$ENCLAVE_INFO" | jq -r ".[0].MemoryMiB // 6144")

if [ -n "$ENCLAVE_ID" ] && [ "$ENCLAVE_ID" != "null" ]; then
    echo "Terminating enclave: $ENCLAVE_ID"
    nitro-cli terminate-enclave --enclave-id "$ENCLAVE_ID" || true
    sleep 2
fi

# Find EIF file
EIF_PATH=$(find /opt/vettid -name "*.eif" 2>/dev/null | head -1)
if [ -z "$EIF_PATH" ]; then
    echo "ERROR: Could not find EIF file"
    exit 1
fi

echo "Starting enclave with: CID=$ENCLAVE_CID, CPUs=$CPU_COUNT, Memory=$MEMORY"
echo "EIF: $EIF_PATH"

nitro-cli run-enclave \
    --cpu-count "$CPU_COUNT" \
    --memory "$MEMORY" \
    --enclave-cid "$ENCLAVE_CID" \
    --eif-path "$EIF_PATH"

sleep 3

# Restart parent process to reconnect
echo "Restarting parent process..."
systemctl restart vettid-parent
sleep 3

# Verify
echo "=== Verification ==="
nitro-cli describe-enclaves
systemctl status vettid-parent --no-pager | head -10

echo "=== Enclave restart complete ==="
'

    # Base64 encode the script
    local SCRIPT_B64=$(echo "$RESTART_SCRIPT" | base64 -w0)

    # Send command via SSM
    COMMAND_ID=$(aws ssm send-command \
        --instance-ids "$ENCLAVE_INSTANCE" \
        --document-name AWS-RunShellScript \
        --parameters commands="[\"echo $SCRIPT_B64 | base64 -d > /tmp/restart-enclave.sh && chmod +x /tmp/restart-enclave.sh && /tmp/restart-enclave.sh\"]" \
        --timeout-seconds 120 \
        --output json 2>&1 | jq -r '.Command.CommandId')

    if [ -z "$COMMAND_ID" ] || [ "$COMMAND_ID" == "null" ]; then
        echo -e "${RED}Failed to send SSM command${NC}"
        return 1
    fi

    echo "SSM Command ID: $COMMAND_ID"
    echo "Waiting for enclave restart (this may take 20-30 seconds)..."

    # Wait for command to complete
    sleep 25

    # Get result
    RESULT=$(aws ssm get-command-invocation \
        --command-id "$COMMAND_ID" \
        --instance-id "$ENCLAVE_INSTANCE" \
        --query '[Status,StandardOutputContent,StandardErrorContent]' \
        --output text 2>&1)

    echo "$RESULT"

    if echo "$RESULT" | grep -qE "Enclave restart complete|RUNNING|active \(running\)"; then
        echo -e "${GREEN}Enclave restarted successfully - credential cleared${NC}"
        return 0
    else
        echo -e "${YELLOW}Enclave restart status unclear - check output above${NC}"
        return 0  # Continue anyway
    fi
}

clear_app_data() {
    echo -e "${YELLOW}Step 3: Clearing app data on device...${NC}"

    if ! command -v adb &> /dev/null; then
        echo -e "${RED}adb not found - skipping app clear${NC}"
        return 1
    fi

    # Check if device is connected
    if ! adb devices | grep -q "device$"; then
        echo -e "${RED}No Android device connected - skipping app clear${NC}"
        return 1
    fi

    adb shell pm clear com.vettid.app
    echo -e "${GREEN}App data cleared${NC}"

    # Optionally restart the app
    echo "Launching app..."
    adb shell am start -n com.vettid.app/.MainActivity
}

# Parse arguments
USER_GUID=""
CLEAR_APP=false
FROM_DEVICE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --clear-app)
            CLEAR_APP=true
            shift
            ;;
        --from-device)
            FROM_DEVICE=true
            shift
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            if [ -z "$USER_GUID" ]; then
                USER_GUID=$1
            fi
            shift
            ;;
    esac
done

# Get user_guid from device if requested
if [ "$FROM_DEVICE" = true ]; then
    USER_GUID=$(extract_guid_from_device)
fi

# Validate user_guid
if [ -z "$USER_GUID" ]; then
    echo -e "${RED}Error: user_guid is required${NC}"
    print_usage
    exit 1
fi

# Validate UUID format
if ! [[ "$USER_GUID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    echo -e "${RED}Error: Invalid user_guid format${NC}"
    echo "Expected: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    exit 1
fi

echo "========================================"
echo "VettID Vault Decommission"
echo "========================================"
echo "User GUID: $USER_GUID"
echo "Clear App: $CLEAR_APP"
echo "========================================"
echo ""

# Run decommission steps
decommission_backend "$USER_GUID"
echo ""

decommission_enclave "$USER_GUID"
echo ""

if [ "$CLEAR_APP" = true ]; then
    clear_app_data
    echo ""
fi

echo "========================================"
echo -e "${GREEN}Decommission complete!${NC}"
echo "========================================"
echo ""
echo "The user can now re-enroll with a fresh vault."
