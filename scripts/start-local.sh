#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  echo 'Create .env from .env.example and enter your Google OAuth client ID and secret.' >&2
  exit 1
fi
if grep -Eq 'replace-with-client|configure-google' .env; then
  echo 'Replace the Google OAuth placeholders in .env before starting.' >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo 'Docker is not running. Open Docker Desktop or run: colima start' >&2
  exit 1
fi
if docker compose version >/dev/null 2>&1; then
  exec docker compose up --build "$@"
elif command -v docker-compose >/dev/null 2>&1; then
  exec docker-compose up --build "$@"
else
  echo 'Docker Compose is required (docker compose or docker-compose).' >&2
  exit 1
fi
