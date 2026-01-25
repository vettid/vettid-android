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

    echo -e "${YELLOW}Step 2: Sending vault reset to enclave via NATS...${NC}"

    # Send command via SSM
    COMMAND_ID=$(aws ssm send-command \
        --instance-ids "$NATS_INSTANCE" \
        --document-name AWS-RunShellScript \
        --parameters "commands=[\"nats pub enclave.vault.reset '{\\\"user_guid\\\":\\\"$USER_GUID\\\"}' -s nats://localhost:4222 2>&1\"]" \
        --output json 2>&1 | jq -r '.Command.CommandId')

    if [ -z "$COMMAND_ID" ] || [ "$COMMAND_ID" == "null" ]; then
        echo -e "${RED}Failed to send SSM command${NC}"
        return 1
    fi

    echo "SSM Command ID: $COMMAND_ID"

    # Wait for command to complete
    sleep 3

    # Get result
    RESULT=$(aws ssm get-command-invocation \
        --command-id "$COMMAND_ID" \
        --instance-id "$NATS_INSTANCE" \
        --query '[Status,StandardOutputContent,StandardErrorContent]' \
        --output text 2>&1)

    echo "$RESULT"

    if echo "$RESULT" | grep -q "Published"; then
        echo -e "${GREEN}Enclave cleanup message sent${NC}"
        return 0
    else
        echo -e "${YELLOW}Enclave cleanup may have issues - check output above${NC}"
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
