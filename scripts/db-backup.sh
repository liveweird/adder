#!/usr/bin/env bash
# Dump the Adder database (pg_dump, custom format) to a file on this machine.
#
#   scripts/db-backup.sh [OUTPUT.dump]
#
# The dump is taken INSIDE the Postgres container/pod (pg_dump needs no password over its local socket), streamed
# through ADDER_PG_EXEC and written here with mode 0600: it holds every user, message and password hash, so treat
# the file like the database itself. It never overwrites an existing file. The default name is adder-<UTC time>.dump
# in the current directory. A consistent snapshot: pg_dump runs in one transaction, so the app may keep running.
#
# Where to run it (ADDER_PG_EXEC = the command that runs a program in the Postgres container; word-split on purpose):
#   compose (default project):  nothing to set; the default is `docker exec -i adder-postgres`
#   compose, another project:   ADDER_PG_EXEC='docker compose -p NAME exec -T postgres'
#   Kubernetes:                 ADDER_PG_EXEC='kubectl -n adder exec -i deploy/postgres --'
# Other knobs: ADDER_DB_USER (default adder), ADDER_DB_NAME (default adder).
#
# This does NOT back up DATA_ENCRYPTION_KEY / JWT_SECRET: keep those separately (a restored database is useless
# for any encrypted column without its key). Runbook: .claude/docs/persistence.md "Backup and restore".
set -euo pipefail

db_user=${ADDER_DB_USER:-adder}
db_name=${ADDER_DB_NAME:-adder}
read -r -a pg_exec <<<"${ADDER_PG_EXEC:-docker exec -i adder-postgres}"

usage="usage: $0 [OUTPUT.dump]   (see the header of this script)"
case "${1:-}" in
    -h | --help) echo "$usage"; exit 0 ;;
esac
if [ "$#" -gt 1 ]; then
    echo "$usage" >&2
    exit 2
fi
out=${1:-adder-$(date -u +%Y%m%dT%H%M%SZ).dump}
if [ -e "$out" ]; then
    echo "refusing to overwrite existing $out" >&2
    exit 1
fi

umask 077
tmp=$(mktemp "${out}.partial.XXXXXX")
trap 'rm -f "$tmp"' EXIT

"${pg_exec[@]}" pg_dump -U "$db_user" -d "$db_name" -Fc >"$tmp"

# The archive must be a readable, non-empty pg_dump archive before it is called a backup.
entries=$("${pg_exec[@]}" pg_restore --list <"$tmp" | grep -c ' TABLE DATA ' || true)
if [ "$entries" -lt 1 ]; then
    echo "the dump lists no tables - not keeping it" >&2
    exit 1
fi

# `ln` fails if $out appeared meanwhile, so the "never overwrites" holds even against a concurrent run (mv would replace it).
if ! ln "$tmp" "$out"; then
    echo "refusing to overwrite $out" >&2
    exit 1
fi
rm -f "$tmp"
trap - EXIT
echo "wrote $out ($(wc -c <"$out" | tr -d ' ') bytes, $entries tables)"
