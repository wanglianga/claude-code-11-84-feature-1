#!/usr/bin/env bash
# E2E 入口：默认打本地 8080，可用 BASE_URL 覆盖
set -e
/opt/python-3.12/bin/python3 "$(dirname "$0")/run-e2e.py"
