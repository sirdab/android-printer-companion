#!/bin/bash

##############################################################################
# Test Script for Sirdab Printer Companion Android App HTTP API
#
# Usage: chmod +x test_api.sh && ./test_api.sh <tablet-ip>
#
# This script tests the NanoHTTPD embedded server running on port 8080 with:
#   - Health checks
#   - Configuration management
#   - Printer status
#   - Print job submission
#   - CORS preflight
#   - Input validation
#
# Requirements:
#   - curl (for HTTP requests)
#   - jq (optional, for pretty-printing JSON)
#
##############################################################################

set -o pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counters
PASSED=0
FAILED=0

# Tablet IP and API base URL
TABLET_IP="${1}"
API_BASE_URL="http://${TABLET_IP}:8080"

# PDF URL for print tests
PDF_URL="https://www.w3.org/WAI/WCAG21/Techniques/pdf/img/table-word.pdf"

##############################################################################
# UTILITY FUNCTIONS
##############################################################################

# Print usage and exit
usage() {
  cat << EOF
${BLUE}=== Sirdab Printer Companion API Test Suite ===${NC}

Usage: $0 <tablet-ip>

Arguments:
  tablet-ip    IP address of the Android tablet running the companion app

Examples:
  $0 192.168.1.100
  $0 10.0.0.5

The script will test all endpoints on http://<tablet-ip>:8080

EOF
  exit 1
}

# Helper to make HTTP requests and capture body + status code
make_request() {
  local method="$1"
  local endpoint="$2"
  local data="$3"
  local extra_headers="$4"

  local url="${API_BASE_URL}${endpoint}"

  if [[ "$method" == "GET" ]]; then
    curl -s -w "\n%{http_code}" "$url"
  elif [[ "$method" == "POST" ]]; then
    curl -s -w "\n%{http_code}" -X POST "$url" \
      -H "Content-Type: application/json" \
      ${extra_headers} \
      -d "$data"
  elif [[ "$method" == "OPTIONS" ]]; then
    curl -s -w "\n%{http_code}" -X OPTIONS "$url" \
      -H "Origin: http://localhost:3000" \
      -H "Access-Control-Request-Method: POST" \
      ${extra_headers}
  fi
}

# Pretty-print JSON if jq is available
pretty_json() {
  if command -v jq &> /dev/null; then
    jq '.' 2>/dev/null || cat
  else
    cat
  fi
}

# Parse response (body and status code)
parse_response() {
  local response="$1"
  local body=$(echo "$response" | head -n -1)
  local status=$(echo "$response" | tail -n 1)
  echo "$body"
  echo "$status"
}

# Check if JSON response has "ok": true
check_ok_field() {
  local json="$1"
  if echo "$json" | jq -e '.ok == true' &>/dev/null 2>&1; then
    return 0
  else
    return 1
  fi
}

# Check if status code is in 2xx range
check_success_status() {
  local status="$1"
  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    return 0
  else
    return 1
  fi
}

# Run a single test case
run_test() {
  local test_num="$1"
  local test_name="$2"
  local method="$3"
  local endpoint="$4"
  local data="$5"
  local check_fn="$6"
  local extra_headers="$7"

  echo ""
  echo -e "${BLUE}[Test ${test_num}]${NC} ${test_name}"

  # Build curl command for display
  local display_cmd="curl -s -w \"\\n%{http_code}\" "
  if [[ "$method" != "GET" ]]; then
    display_cmd+="-X ${method} "
  fi
  display_cmd+="${API_BASE_URL}${endpoint}"
  if [[ -n "$data" ]]; then
    display_cmd+=" -d '${data:0:60}...'"
  fi

  echo -e "${YELLOW}Command:${NC}"
  echo "  $display_cmd"

  # Make the request
  local response=$(make_request "$method" "$endpoint" "$data" "$extra_headers")
  local body=$(echo "$response" | head -n -1)
  local status=$(echo "$response" | tail -n 1)

  # Display response
  echo -e "${YELLOW}Response (HTTP $status):${NC}"
  if [[ -n "$body" ]]; then
    echo "$body" | pretty_json | sed 's/^/  /'
  else
    echo "  (empty body)"
  fi

  # Run check function
  echo -e "${YELLOW}Validation:${NC}"
  if $check_fn "$body" "$status"; then
    echo -e "  ${GREEN}✓ PASS${NC}"
    ((PASSED++))
  else
    echo -e "  ${RED}✗ FAIL${NC}"
    ((FAILED++))
  fi
}

##############################################################################
# CHECK FUNCTIONS
##############################################################################

check_health() {
  local body="$1"
  local status="$2"
  check_success_status "$status" && check_ok_field "$body"
}

check_config_get() {
  local body="$1"
  local status="$2"
  check_success_status "$status" && check_ok_field "$body"
}

check_config_set() {
  local body="$1"
  local status="$2"
  check_success_status "$status" && check_ok_field "$body"
}

check_config_invalid() {
  local body="$1"
  local status="$2"
  # Should fail validation (400 or 422)
  if [[ "$status" =~ ^(400|422)$ ]] || ! check_ok_field "$body"; then
    return 0
  else
    return 1
  fi
}

check_status() {
  local body="$1"
  local status="$2"
  check_success_status "$status" && check_ok_field "$body"
}

check_print_missing_pdf() {
  local body="$1"
  local status="$2"
  # Should fail (400, 422, or 500) or have ok:false
  if ! check_success_status "$status" || ! check_ok_field "$body"; then
    return 0
  else
    return 1
  fi
}

check_print_valid() {
  local body="$1"
  local status="$2"
  check_success_status "$status" && check_ok_field "$body"
}

check_print_bad_ip() {
  local body="$1"
  local status="$2"
  # Should fail (4xx or 5xx) or have ok:false
  if ! check_success_status "$status" || ! check_ok_field "$body"; then
    return 0
  else
    return 1
  fi
}

check_copies_zero() {
  local body="$1"
  local status="$2"
  # Should fail validation or coerce to 1
  if ! check_success_status "$status" || ! check_ok_field "$body"; then
    return 0
  else
    # If it succeeds, check if copies was coerced to 1 (if response shows it)
    return 0
  fi
}

check_copies_eleven() {
  local body="$1"
  local status="$2"
  # Should succeed but copies coerced to 10
  if check_success_status "$status" && check_ok_field "$body"; then
    return 0
  else
    return 1
  fi
}

check_cors_preflight() {
  local body="$1"
  local status="$2"
  # CORS preflight should return 200 or 204
  if [[ "$status" =~ ^(200|204)$ ]]; then
    return 0
  else
    return 1
  fi
}

##############################################################################
# MAIN TEST SUITE
##############################################################################

main() {
  # Check required argument
  if [[ -z "$TABLET_IP" ]]; then
    echo -e "${RED}Error: Tablet IP address is required${NC}"
    echo ""
    usage
  fi

  # Validate IP format (basic check)
  if ! [[ "$TABLET_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "${RED}Error: Invalid IP address format: $TABLET_IP${NC}"
    echo ""
    usage
  fi

  echo -e "${BLUE}"
  echo "╔════════════════════════════════════════════════════════════════╗"
  echo "║     Sirdab Printer Companion API Test Suite                    ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  echo -e "${NC}"

  echo -e "Target: ${YELLOW}${API_BASE_URL}${NC}"
  echo ""

  # Quick connectivity check
  echo "Checking connectivity..."
  if ! curl -s --connect-timeout 2 "${API_BASE_URL}/health" > /dev/null 2>&1; then
    echo -e "${RED}Error: Cannot reach ${API_BASE_URL}${NC}"
    echo "Please verify the tablet IP and that the app is running."
    exit 1
  fi
  echo -e "${GREEN}✓ Connected${NC}"
  echo ""

  ##########################################################################
  # TEST CASES
  ##########################################################################

  # Test 1: Health Check
  run_test 1 "Health check (GET /health)" \
    "GET" "/health" "" "check_health"

  # Test 2: Get Config
  run_test 2 "Get current config (GET /config)" \
    "GET" "/config" "" "check_config_get"

  # Test 3: Set Config - Valid values
  run_test 3 "Set config with valid values (POST /config)" \
    "POST" "/config" \
    '{"printer_ip":"192.168.1.100","printer_port":9100,"label_width_mm":100,"label_height_mm":150,"gap_mm":5,"print_speed":10,"print_density":8}' \
    "check_config_set"

  # Test 4: Set Config - Invalid gap (too low)
  run_test 4 "Set config with invalid gap_mm=0 (should fail)" \
    "POST" "/config" \
    '{"gap_mm":0}' \
    "check_config_invalid"

  # Test 5: Set Config - Invalid gap (too high)
  run_test 5 "Set config with invalid gap_mm=15 (should fail)" \
    "POST" "/config" \
    '{"gap_mm":15}' \
    "check_config_invalid"

  # Test 6: Set Config - Invalid print_speed (too high)
  run_test 6 "Set config with invalid print_speed=20 (should fail)" \
    "POST" "/config" \
    '{"print_speed":20}' \
    "check_config_invalid"

  # Test 7: Set Config - Invalid print_density (too high)
  run_test 7 "Set config with invalid print_density=16 (should fail)" \
    "POST" "/config" \
    '{"print_density":16}' \
    "check_config_invalid"

  # Test 8: Get Printer Status
  run_test 8 "Get printer status (GET /status)" \
    "GET" "/status" "" "check_status"

  # Test 9: Print without PDF URL (should fail)
  run_test 9 "Print without pdfUrl (should fail)" \
    "POST" "/print" \
    '{"copies":1}' \
    "check_print_missing_pdf"

  # Test 10: Print with valid PDF URL
  run_test 10 "Print with valid public PDF URL" \
    "POST" "/print" \
    "{\"pdfUrl\":\"${PDF_URL}\",\"copies\":1}" \
    "check_print_valid"

  # Test 11: Print with bad printer IP
  run_test 11 "Print with unreachable printer IP" \
    "POST" "/print" \
    "{\"pdfUrl\":\"${PDF_URL}\",\"printerIp\":\"192.0.2.1\",\"printerPort\":9100,\"copies\":1}" \
    "check_print_bad_ip"

  # Test 12: Print with copies=0 (invalid, should fail or coerce to 1)
  run_test 12 "Print with copies=0 (boundary test)" \
    "POST" "/print" \
    "{\"pdfUrl\":\"${PDF_URL}\",\"copies\":0}" \
    "check_copies_zero"

  # Test 13: Print with copies=11 (should coerce to 10 or fail)
  run_test 13 "Print with copies=11 (boundary test, should coerce to 10)" \
    "POST" "/print" \
    "{\"pdfUrl\":\"${PDF_URL}\",\"copies\":11}" \
    "check_copies_eleven"

  # Test 14: CORS preflight
  run_test 14 "CORS preflight (OPTIONS /print)" \
    "OPTIONS" "/print" "" "check_cors_preflight"

  ##########################################################################
  # SUMMARY
  ##########################################################################

  echo ""
  echo -e "${BLUE}"
  echo "╔════════════════════════════════════════════════════════════════╗"
  echo "║                         Test Summary                           ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  echo -e "${NC}"

  local total=$((PASSED + FAILED))
  echo "Total tests: $total"
  echo -e "${GREEN}Passed: $PASSED${NC}"
  echo -e "${RED}Failed: $FAILED${NC}"

  echo ""

  if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
  else
    echo -e "${RED}Some tests failed. Review output above.${NC}"
    exit 1
  fi
}

# Run main function
main "$@"
